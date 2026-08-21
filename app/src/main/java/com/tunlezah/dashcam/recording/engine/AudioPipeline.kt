package com.tunlezah.dashcam.recording.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Optional microphone path: AudioRecord → AAC MediaCodec → [SegmentSink].
 * Only constructed when the user has enabled audio AND granted RECORD_AUDIO.
 * PTS use the same CLOCK_MONOTONIC base as camera frames (System.nanoTime),
 * so A/V stay aligned across segment rotation.
 *
 * Failure policy: any audio error tears down audio ONLY — video recording is
 * never interrupted by a microphone problem.
 */
@androidx.media3.common.util.UnstableApi
class AudioPipeline(
    private val sink: SegmentSink,
    private val diagnostics: DiagnosticsLog,
) {

    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null

    @Volatile
    private var running = false
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission") // caller verifies RECORD_AUDIO before constructing
    fun start(): Boolean {
        return try {
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING)
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE, CHANNEL_CONFIG, ENCODING,
                (minBuffer * 4).coerceAtLeast(16 * 1024),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord init failed" }

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            audioRecord = record
            codec = encoder
            encoder.start()
            record.startRecording()
            running = true
            captureThread = thread(name = "AudioPipeline") { loop(record, encoder) }
            diagnostics.log("Audio", "microphone pipeline started (${SAMPLE_RATE}Hz mono AAC)")
            true
        } catch (e: Exception) {
            diagnostics.log("Audio", "start failed: ${e.message} — recording continues without audio")
            stop()
            false
        }
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(4096)
        try {
            while (running) {
                // Feed PCM.
                val read = record.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val inIndex = encoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer = encoder.getInputBuffer(inIndex) ?: continue
                        inBuf.clear()
                        val len = read.coerceAtMost(inBuf.remaining())
                        inBuf.put(pcm, 0, len)
                        encoder.queueInputBuffer(inIndex, 0, len, System.nanoTime() / 1000, 0)
                    }
                }
                // Drain AAC.
                var outIndex = encoder.dequeueOutputBuffer(info, 0)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sink.setAudioFormat(encoder.outputFormat)
                    } else {
                        val buf = encoder.getOutputBuffer(outIndex)
                        if (buf != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            sink.writeAudioSample(buf, info)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex = encoder.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (e: Exception) {
            if (running) diagnostics.log("Audio", "pipeline error: ${e.message} — audio stopped, video unaffected")
        }
    }

    fun stop() {
        running = false
        captureThread?.join(2_000)
        captureThread = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val BITRATE = 96_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
