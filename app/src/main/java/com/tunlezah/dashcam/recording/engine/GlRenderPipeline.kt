package com.tunlezah.dashcam.recording.engine

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.util.concurrent.atomic.AtomicLong

/**
 * The frame router (docs/architecture.md §4): a single camera output
 * (SurfaceTexture) is fanned out on a dedicated GL thread to
 *  1. the encoder input surface (always, at full rate — this IS the recording)
 *  2. the preview surface (optional, frame-rate capped, detachable at runtime
 *     for screen-off/thermal shedding without touching camera or encoder)
 *
 * The overlay strip is drawn into the ENCODER path only when burn-in is
 * enabled; the preview always shows it (UI feedback costs nothing extra).
 */
class GlRenderPipeline(
    private val diagnostics: DiagnosticsLog,
) {

    /** Provides the current overlay bitmap + monotonically increasing version. */
    interface OverlaySource {
        fun currentOverlay(): Pair<Bitmap, Long>?
        /** Normalized device-coord rect of the overlay quad: left, bottom, right, top. */
        fun overlayRect(): FloatArray
    }

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var egl: EglCore? = null
    private var encoderEglSurface: EGLSurface? = null
    private var previewEglSurface: EGLSurface? = null
    private var oesProgram: OesTextureProgram? = null
    private var overlayProgram: OverlayQuadProgram? = null

    private var cameraTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    var cameraSurface: Surface? = null
        private set

    private var geometry: FrameGeometry.OutputGeometry? = null
    private val stMatrix = FloatArray(16)

    @Volatile
    private var burnInOverlay = true

    @Volatile
    private var overlaySource: OverlaySource? = null

    @Volatile
    private var previewFpsCap = 30
    private var lastPreviewFrameNs = 0L

    @Volatile
    private var previewWidth = 0

    @Volatile
    private var previewHeight = 0

    /** Frames rendered to the encoder — watchdog + diagnostics. */
    val framesRendered = AtomicLong(0)

    /** Frames intentionally skipped on the preview path (thermal cap). */
    val previewFramesSkipped = AtomicLong(0)

    /**
     * Bring the pipeline up. Must be called before the camera session is
     * created. [encoderSurface] is the MediaCodec input surface.
     */
    fun start(
        encoderSurface: Surface,
        streamWidth: Int,
        streamHeight: Int,
        outputGeometry: FrameGeometry.OutputGeometry,
        burnIn: Boolean,
        overlays: OverlaySource?,
    ) {
        val t = HandlerThread("GlPipeline", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        geometry = outputGeometry
        burnInOverlay = burnIn
        overlaySource = overlays

        val initDone = java.util.concurrent.CountDownLatch(1)
        var initError: Exception? = null
        h.post {
            try {
                val core = EglCore().apply { init() }
                egl = core
                val encSurface = core.createWindowSurface(encoderSurface)
                encoderEglSurface = encSurface
                core.makeCurrent(encSurface)

                oesProgram = OesTextureProgram().apply { init() }
                overlayProgram = OverlayQuadProgram().apply { init() }

                val ids = IntArray(1)
                GLES20.glGenTextures(1, ids, 0)
                cameraTexId = ids[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

                val st = SurfaceTexture(cameraTexId)
                st.setDefaultBufferSize(streamWidth, streamHeight)
                st.setOnFrameAvailableListener({ onFrameAvailable() }, h)
                surfaceTexture = st
                cameraSurface = Surface(st)
                diagnostics.log("GL", "pipeline up: stream ${streamWidth}x$streamHeight -> " +
                    "${outputGeometry.outputWidth}x${outputGeometry.outputHeight} rot=${outputGeometry.rotationDegrees}")
            } catch (e: Exception) {
                initError = e
            } finally {
                initDone.countDown()
            }
        }
        initDone.await()
        initError?.let { throw it }
    }

    /** Attach/detach the preview surface at any time. */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        val h = handler ?: return
        h.post {
            val core = egl ?: return@post
            previewEglSurface?.let { core.releaseSurface(it) }
            previewEglSurface = null
            if (surface != null && surface.isValid) {
                runCatching {
                    previewEglSurface = core.createWindowSurface(surface)
                    previewWidth = width
                    previewHeight = height
                }.onFailure { diagnostics.log("GL", "preview surface attach failed: ${it.message}") }
            }
        }
    }

    fun setPreviewFpsCap(fps: Int) {
        previewFpsCap = fps.coerceIn(1, 60)
    }

    fun setBurnInOverlay(enabled: Boolean) {
        burnInOverlay = enabled
    }

    private fun onFrameAvailable() {
        val core = egl ?: return
        val st = surfaceTexture ?: return
        val encSurface = encoderEglSurface ?: return
        val geo = geometry ?: return
        try {
            core.makeCurrent(encSurface)
            st.updateTexImage()
            st.getTransformMatrix(stMatrix)
            val texMatrix = FrameGeometry.composeTexMatrix(stMatrix, geo)

            // --- Encoder pass (the recording) ---
            GLES20.glViewport(0, 0, geo.outputWidth, geo.outputHeight)
            oesProgram?.draw(cameraTexId, texMatrix)
            val source = overlaySource
            if (source != null) {
                val overlay = source.currentOverlay()
                if (overlay != null) {
                    overlayProgram?.maybeUpload(overlay.first, overlay.second)
                    if (burnInOverlay) overlayProgram?.draw(source.overlayRect())
                }
            }
            core.setPresentationTime(encSurface, st.timestamp)
            core.swapBuffers(encSurface)
            framesRendered.incrementAndGet()

            // --- Preview pass (optional, capped) ---
            val preview = previewEglSurface
            if (preview != null) {
                val now = System.nanoTime()
                val minInterval = 1_000_000_000L / previewFpsCap
                if (now - lastPreviewFrameNs >= minInterval) {
                    lastPreviewFrameNs = now
                    core.makeCurrent(preview)
                    GLES20.glViewport(0, 0, previewWidth, previewHeight)
                    oesProgram?.draw(cameraTexId, texMatrix)
                    // Preview always shows the overlay so the driver sees what's stamped.
                    if (source != null) overlayProgram?.draw(source.overlayRect())
                    if (!core.swapBuffers(preview)) {
                        diagnostics.log("GL", "preview swap failed; detaching")
                        core.makeCurrent(encSurface)
                        core.releaseSurface(preview)
                        previewEglSurface = null
                    }
                } else {
                    previewFramesSkipped.incrementAndGet()
                }
            }
        } catch (e: Exception) {
            diagnostics.log("GL", "frame error: ${e.message}")
        }
    }

    fun stop() {
        val h = handler
        val t = thread
        if (h != null) {
            val done = java.util.concurrent.CountDownLatch(1)
            val posted = h.post {
                runCatching {
                    surfaceTexture?.setOnFrameAvailableListener(null)
                    cameraSurface?.release()
                    surfaceTexture?.release()
                    oesProgram?.release()
                    overlayProgram?.release()
                    val core = egl
                    if (core != null) {
                        previewEglSurface?.let { core.releaseSurface(it) }
                        encoderEglSurface?.let { core.releaseSurface(it) }
                        core.release()
                    }
                }
                done.countDown()
            }
            if (posted) done.await()
        }
        t?.quitSafely()
        thread = null
        handler = null
        egl = null
        encoderEglSurface = null
        previewEglSurface = null
        surfaceTexture = null
        cameraSurface = null
        oesProgram = null
        overlayProgram = null
    }
}
