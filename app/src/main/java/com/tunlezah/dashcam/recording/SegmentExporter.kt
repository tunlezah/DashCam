package com.tunlezah.dashcam.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.provider.MediaStore
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Exports a recorded segment for sharing: remuxes the on-disk fragmented MP4
 * into a classic MP4 (maximum player compatibility — docs/storage.md), then
 * publishes it to MediaStore/Movies with the IS_PENDING pattern so other apps
 * only ever see the finished file. No re-encode: this is a container rewrite,
 * seconds of sequential I/O per segment.
 */
class SegmentExporter(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    /** Returns the MediaStore URI string on success, null on failure. */
    suspend fun exportToGallery(source: File): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DashCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        val ok = runCatching {
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                remux(source) { MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
            } ?: false
        }.getOrElse {
            diagnostics.log("Export", "failed: ${it.message}")
            false
        }
        if (ok) {
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            diagnostics.log("Export", "published ${source.name}")
            uri.toString()
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Remux to a local file (used before ACTION_SEND sharing). */
    suspend fun exportToFile(source: File, dest: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            remux(source) { MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) }
        }.getOrElse {
            diagnostics.log("Export", "failed: ${it.message}")
            dest.delete()
            false
        }
    }

    private fun remux(source: File, muxerFactory: () -> MediaMuxer): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val m = muxerFactory()
            muxer = m
            val trackMap = IntArray(extractor.trackCount)
            var maxBuffer = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                trackMap[i] = m.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBuffer = maxOf(maxBuffer, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                extractor.selectTrack(i)
            }
            m.start()
            val buffer = ByteBuffer.allocateDirect(maxBuffer)
            val info = MediaCodec.BufferInfo()
            while (true) {
                info.size = extractor.readSampleData(buffer, 0)
                if (info.size < 0) break
                info.presentationTimeUs = extractor.sampleTime
                info.offset = 0
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                m.writeSampleData(trackMap[extractor.sampleTrackIndex], buffer, info)
                extractor.advance()
            }
            m.stop()
            return true
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }
}
