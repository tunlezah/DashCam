package com.tunlezah.dashcam.recording.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * The persistent video encoder session. Configured ONCE per recording run;
 * segments never stop it (segment cuts happen downstream in [SegmentSink]).
 *
 * Surface-input MediaCodec: the GL pipeline renders into [inputSurface];
 * a dedicated drain thread pushes encoded buffers to [Listener].
 */
class VideoEncoderCore(
    private val diagnostics: DiagnosticsLog,
) {

    interface Listener {
        /** Called once when the encoder emits its real output format (with csd). */
        fun onOutputFormat(format: MediaFormat)
        fun onEncodedFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo)
        fun onEncoderError(error: Exception)
    }

    var inputSurface: Surface? = null
        private set

    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null

    @Volatile
    private var running = false

    /** Count of encoded frames — the watchdog reads this. */
    @Volatile
    var encodedFrameCount: Long = 0
        private set

    fun configure(
        mimeType: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateBps: Int,
        iFrameIntervalSeconds: Int = 1,
    ) {
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
            // VBR keeps quality steady across dark/complex scenes; encoders fall
            // back internally when a mode is unsupported.
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
            )
        }
        val encoder = MediaCodec.createEncoderByType(mimeType)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        codec = encoder
        diagnostics.log("Encoder", "configured ${encoder.name} ${width}x$height@$fps ${bitrateBps / 1_000_000}Mbps $mimeType")
    }

    fun start(listener: Listener) {
        val encoder = codec ?: error("configure() first")
        encoder.start()
        running = true
        encodedFrameCount = 0
        drainThread = thread(name = "EncoderDrain", priority = Thread.MAX_PRIORITY - 2) {
            drainLoop(encoder, listener)
        }
    }

    /** Ask for a sync frame at the next opportunity (segment boundary cut). */
    fun requestSyncFrame() {
        val encoder = codec ?: return
        runCatching {
            val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }
            encoder.setParameters(params)
        }
    }

    /** Live bitrate change — the cheapest thermal mitigation (no reconfigure). */
    fun updateBitrate(bitrateBps: Int) {
        val encoder = codec ?: return
        runCatching {
            val params = Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitrateBps) }
            encoder.setParameters(params)
            diagnostics.log("Encoder", "bitrate updated to ${bitrateBps / 1_000_000} Mbps")
        }
    }

    fun stop() {
        running = false
        drainThread?.join(3_000)
        drainThread = null
        codec?.let { encoder ->
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
        }
        codec = null
        inputSurface?.release()
        inputSurface = null
    }

    private fun drainLoop(encoder: MediaCodec, listener: Listener) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        listener.onOutputFormat(encoder.outputFormat)
                    index >= 0 -> {
                        val buffer = encoder.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            // csd arrives inside the format; config buffers are skipped.
                            listener.onEncodedFrame(buffer, info)
                            encodedFrameCount++
                        }
                        encoder.releaseOutputBuffer(index, false)
                    }
                }
            }
            // Drain what's left after signalling stop.
            var index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            var guard = 0
            while (index >= 0 && guard++ < 64) {
                val buffer = encoder.getOutputBuffer(index)
                if (buffer != null && info.size > 0 &&
                    info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                ) {
                    listener.onEncodedFrame(buffer, info)
                }
                encoder.releaseOutputBuffer(index, false)
                index = encoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            }
        } catch (e: Exception) {
            if (running) {
                diagnostics.log("Encoder", "drain error: ${e.message}")
                listener.onEncoderError(e)
            }
        }
    }

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
