package com.tunlezah.dashcam.recording.engine

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4OrientationData
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.FragmentedMp4Muxer
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * The gapless segment writer (docs/architecture.md §4).
 *
 * One encoder feeds many files: the sink watches video presentation
 * timestamps, and once the current segment reaches its target duration it
 * asks the encoder for a sync frame (via [SyncFrameRequester]) and rotates to
 * a new muxer exactly at the next keyframe. Every encoded frame lands in
 * exactly one file; PTS are rebased so each file starts at zero.
 *
 * Fragmented MP4 (Media3 FragmentedMp4Muxer, 1 s fragments) keeps the
 * in-progress file valid to the last fragment if power dies mid-crash.
 *
 * Thread-safety: video frames arrive on the encoder drain thread, audio on the
 * audio drain thread — all mutation is guarded by [lock]. Muxer close (the
 * only potentially slow call) happens inline on the video thread; for fMP4 it
 * only flushes the final fragment (small, bounded) rather than writing a moov
 * for the whole file.
 */
@androidx.media3.common.util.UnstableApi
class SegmentSink(
    private val diagnostics: DiagnosticsLog,
    private val syncFrameRequester: SyncFrameRequester,
    private val planner: SegmentFilePlanner,
    private val listener: Listener,
) {

    fun interface SyncFrameRequester {
        fun requestSyncFrame()
    }

    /**
     * Supplies the next segment file. MUST be non-blocking: the orchestrator
     * keeps one pre-approved (space-ensured) plan ready at all times and
     * refills asynchronously after each rotation. Returning null means storage
     * is exhausted — the sink stops writing and reports.
     */
    fun interface SegmentFilePlanner {
        fun takePlannedFile(startWallMs: Long): File?
    }

    interface Listener {
        fun onSegmentStarted(file: File, startWallMs: Long)
        fun onSegmentFinalized(file: File, startWallMs: Long, endWallMs: Long, sizeBytes: Long)
        fun onSinkStarved()
        fun onSinkError(error: Exception)
    }

    private val lock = Any()

    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null

    private var muxer: FragmentedMp4Muxer? = null
    private var stream: FileOutputStream? = null
    private var videoTrackId = -1
    private var audioTrackId = -1
    private var currentFile: File? = null
    private var segmentStartWallMs = 0L
    private var segmentStartPtsUs = -1L
    private var segmentDurationUs = 3 * 60 * 1_000_000L
    private var syncRequested = false
    private var starvedReported = false
    private var location: Pair<Double, Double>? = null
    private var rotationDegrees = 0

    @Volatile
    var segmentsWritten: Long = 0
        private set

    fun configure(segmentMinutes: Int, embedLocation: Boolean, rotationDegrees: Int = this.rotationDegrees) {
        synchronized(lock) {
            segmentDurationUs = segmentMinutes * 60 * 1_000_000L
            if (!embedLocation) location = null
            this.rotationDegrees = rotationDegrees
        }
    }

    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) { videoFormat = format }
    }

    fun setAudioFormat(format: MediaFormat) {
        synchronized(lock) { audioFormat = format }
    }

    /** Latest GPS fix; stamped into each new segment when enabled. */
    fun updateLocation(latitude: Double, longitude: Double, embed: Boolean) {
        synchronized(lock) {
            location = if (embed) latitude to longitude else null
        }
    }

    /**
     * Camera frame timestamps are not guaranteed to share CLOCK_MONOTONIC with
     * the audio pipeline (SENSOR_INFO_TIMESTAMP_SOURCE may be UNKNOWN on
     * budget devices). This EMA offset maps monotonic-clock audio PTS into the
     * video timebase; jitter is ≤ one frame interval, well under lip-sync
     * perception thresholds.
     */
    @Volatile
    private var videoMinusMonotonicUs = Long.MIN_VALUE

    fun writeVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val nowUs = System.nanoTime() / 1000
            val offset = info.presentationTimeUs - nowUs
            videoMinusMonotonicUs = if (videoMinusMonotonicUs == Long.MIN_VALUE) offset
            else (videoMinusMonotonicUs * 7 + offset) / 8

            if (muxer == null) {
                // First segment (or restart after starvation) begins on a keyframe.
                if (!isKeyFrame) {
                    if (!syncRequested) {
                        syncFrameRequester.requestSyncFrame()
                        syncRequested = true
                    }
                    return
                }
                if (!openSegment(info.presentationTimeUs)) return
            } else if (shouldRotate(info.presentationTimeUs)) {
                if (!syncRequested) {
                    syncFrameRequester.requestSyncFrame()
                    syncRequested = true
                }
                if (isKeyFrame) {
                    closeSegment(info.presentationTimeUs)
                    if (!openSegment(info.presentationTimeUs)) return
                }
            }

            val m = muxer ?: return
            try {
                m.writeSampleData(
                    videoTrackId,
                    buffer,
                    BufferInfo(info.presentationTimeUs - segmentStartPtsUs, info.size, info.flags),
                )
            } catch (e: Exception) {
                handleWriteError(e)
            }
        }
    }

    fun writeAudioSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val m = muxer ?: return
            if (audioTrackId < 0) return
            if (videoMinusMonotonicUs == Long.MIN_VALUE) return
            // Audio PTS are CLOCK_MONOTONIC; map into the video timebase first.
            var pts = info.presentationTimeUs + videoMinusMonotonicUs - segmentStartPtsUs
            if (pts < 0) return // audio predating this segment's first video frame
            // Keep the track strictly monotonic despite EMA jitter.
            if (pts <= lastAudioPtsUs) pts = lastAudioPtsUs + 1
            lastAudioPtsUs = pts
            try {
                m.writeSampleData(audioTrackId, buffer, BufferInfo(pts, info.size, info.flags))
            } catch (e: Exception) {
                handleWriteError(e)
            }
        }
    }

    /** Finalize the current segment (recording stop). */
    fun finish() {
        synchronized(lock) {
            closeSegment(lastVideoPtsUs)
        }
    }

    private var lastVideoPtsUs = 0L
    private var lastAudioPtsUs = -1L

    private fun shouldRotate(ptsUs: Long): Boolean {
        lastVideoPtsUs = ptsUs
        return ptsUs - segmentStartPtsUs >= segmentDurationUs
    }

    private fun openSegment(firstPtsUs: Long): Boolean {
        val vf = videoFormat ?: return false
        val startWallMs = System.currentTimeMillis()
        val file = planner.takePlannedFile(startWallMs)
        if (file == null) {
            if (!starvedReported) {
                starvedReported = true
                diagnostics.log("Sink", "no planned segment file — storage exhausted?")
                listener.onSinkStarved()
            }
            return false
        }
        starvedReported = false
        return try {
            val out = FileOutputStream(file)
            val m = FragmentedMp4Muxer.Builder(out.channel)
                .setFragmentDurationMs(FRAGMENT_DURATION_MS)
                .build()
            // Standard MP4 display rotation — frames are stored sensor-native
            // and players rotate at display time (docs/architecture.md §11).
            if (rotationDegrees != 0) {
                m.addMetadataEntry(Mp4OrientationData(rotationDegrees))
            }
            location?.let { (lat, lon) ->
                runCatching { m.addMetadataEntry(Mp4LocationData(lat.toFloat(), lon.toFloat())) }
            }
            videoTrackId = m.addTrack(MediaFormatUtil.createFormatFromMediaFormat(vf))
            audioTrackId = audioFormat?.let { af ->
                runCatching { m.addTrack(MediaFormatUtil.createFormatFromMediaFormat(af)) }
                    .getOrElse { -1 }
            } ?: -1
            muxer = m
            stream = out
            currentFile = file
            segmentStartWallMs = startWallMs
            segmentStartPtsUs = firstPtsUs
            lastAudioPtsUs = -1
            syncRequested = false
            listener.onSegmentStarted(file, startWallMs)
            true
        } catch (e: Exception) {
            handleWriteError(e)
            false
        }
    }

    private fun closeSegment(endPtsUs: Long) {
        val m = muxer ?: return
        val file = currentFile
        val startWall = segmentStartWallMs
        muxer = null
        try {
            m.close()
            stream?.fd?.sync()
            stream?.close()
        } catch (e: Exception) {
            diagnostics.log("Sink", "segment close error: ${e.message}")
        } finally {
            stream = null
        }
        if (file != null) {
            segmentsWritten++
            val durationMs = ((endPtsUs - segmentStartPtsUs) / 1000).coerceAtLeast(0)
            listener.onSegmentFinalized(file, startWall, startWall + durationMs, file.length())
        }
        currentFile = null
    }

    private fun handleWriteError(e: Exception) {
        diagnostics.log("Sink", "write error: ${e.message}")
        // Abandon the current muxer; the file stays on disk for recovery.
        runCatching { muxer?.close() }
        runCatching { stream?.close() }
        muxer = null
        stream = null
        currentFile = null
        listener.onSinkError(e)
    }

    companion object {
        const val FRAGMENT_DURATION_MS = 1_000L
    }
}
