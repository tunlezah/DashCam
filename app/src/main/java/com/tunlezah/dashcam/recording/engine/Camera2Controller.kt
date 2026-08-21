package com.tunlezah.dashcam.recording.engine

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Owns the Camera2 device + capture session with exactly ONE output surface
 * (the GL pipeline's SurfaceTexture) — the safest stream combination on
 * LIMITED-level devices like the Moto G04 (docs/architecture.md §4).
 */
class Camera2Controller(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    interface ErrorListener {
        fun onCameraDisconnected()
        fun onCameraError(code: Int)
    }

    private val cameraManager get() = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    /**
     * Opens the camera and starts a repeating record request into [target].
     * Blocking (call from a coroutine on IO); throws on failure so the
     * orchestrator's retry/backoff logic owns recovery policy.
     */
    @SuppressLint("MissingPermission") // permission verified by orchestrator before start
    @Throws(Exception::class)
    fun start(
        cameraId: String,
        target: Surface,
        fps: Int,
        stabilization: Boolean,
        errorListener: ErrorListener,
    ) {
        stop()
        val t = HandlerThread("Camera2").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h

        val opened = CountDownLatch(1)
        var openError: Exception? = null
        var openedDevice: CameraDevice? = null

        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                openedDevice = camera
                opened.countDown()
            }

            override fun onDisconnected(camera: CameraDevice) {
                diagnostics.log("Camera", "disconnected")
                camera.close()
                if (opened.count > 0) {
                    openError = IllegalStateException("camera disconnected during open")
                    opened.countDown()
                } else {
                    errorListener.onCameraDisconnected()
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                diagnostics.log("Camera", "error $error")
                camera.close()
                if (opened.count > 0) {
                    openError = IllegalStateException("camera open error $error")
                    opened.countDown()
                } else {
                    errorListener.onCameraError(error)
                }
            }
        }, h)

        check(opened.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)) { "camera open timed out" }
        openError?.let { throw it }
        val cam = openedDevice ?: throw IllegalStateException("camera open failed")
        device = cam

        val sessionReady = CountDownLatch(1)
        var sessionError: Exception? = null
        var readySession: CameraCaptureSession? = null

        @Suppress("DEPRECATION") // single-surface session; SessionConfiguration adds nothing here
        cam.createCaptureSession(
            listOf(target),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    readySession = s
                    sessionReady.countDown()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    sessionError = IllegalStateException("capture session configure failed")
                    sessionReady.countDown()
                }
            },
            h,
        )
        check(sessionReady.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS)) { "session configure timed out" }
        sessionError?.let { throw it }
        val s = readySession ?: throw IllegalStateException("no capture session")
        session = s

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(target)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chooseFpsRange(characteristics, fps))
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            if (stabilization) {
                val modes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                if (modes?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true) {
                    set(
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                    )
                }
            }
        }
        s.setRepeatingRequest(request.build(), null, h)
        diagnostics.log("Camera", "recording session running (camera $cameraId, $fps fps)")
    }

    /**
     * Prefer a fixed [fps,fps] range (constant frame pacing for the encoder);
     * fall back to the narrowest range containing fps.
     */
    private fun chooseFpsRange(characteristics: CameraCharacteristics, fps: Int): Range<Int> {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?: return Range(fps, fps)
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        return ranges
            .filter { fps in it.lower..it.upper }
            .minByOrNull { it.upper - it.lower }
            ?: Range(fps, fps)
    }

    fun stop() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    companion object {
        private const val OPEN_TIMEOUT_S = 5L
    }
}
