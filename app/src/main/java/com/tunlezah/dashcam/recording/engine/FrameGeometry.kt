package com.tunlezah.dashcam.recording.engine

import android.opengl.Matrix

/**
 * Orientation math for the recording pipeline.
 *
 * Strategy (reworked after on-device feedback): the encoder always receives
 * the camera buffer EXACTLY as the sensor produces it — no pixel rotation, no
 * cropping. Upright display is achieved the way every stock camera app does
 * it: a standard MP4 rotation metadata value computed from the documented
 * Camera2 relative-rotation formula. Players (ExoPlayer, VLC, gallery apps,
 * editors) apply it universally, and the recording can never be ruined by a
 * render-transform bug — the only orientation-sensitive rendering left is the
 * preview and the burned-in overlay, both cosmetic and correctable.
 *
 * Terminology:
 *  - sensorOrientation: CameraCharacteristics.SENSOR_ORIENTATION (0/90/180/270)
 *  - mountRotation: how the DEVICE is physically rotated from natural
 *    (portrait) orientation, derived from the accelerometer gravity vector —
 *    a dashcam runs with the screen possibly off in a windshield cradle, so
 *    Display rotation is not reliable.
 */
object FrameGeometry {

    /**
     * Device rotation (0/90/180/270) from gravity in device coordinates.
     * Android device axes: +x right, +y up (portrait). Gravity pulls down, so
     * upright portrait reads +9.8 on +y. 90 = device rotated counter-clockwise
     * (landscape, Surface.ROTATION_90 equivalent).
     */
    fun mountRotationFromGravity(ax: Float, ay: Float): Int {
        return if (kotlin.math.abs(ay) >= kotlin.math.abs(ax)) {
            if (ay >= 0) 0 else 180
        } else {
            if (ax >= 0) 90 else 270
        }
    }

    /**
     * The MP4 rotation metadata value: degrees the stored frames must be
     * rotated CLOCKWISE at display time to appear world-upright. This is the
     * standard Camera2 relative-rotation formula.
     */
    fun bufferRotationDegrees(sensorOrientation: Int, mountRotation: Int, facingFront: Boolean): Int {
        return if (facingFront) {
            (sensorOrientation + mountRotation) % 360
        } else {
            (sensorOrientation - mountRotation + 360) % 360
        }
    }

    /** Width of the frame as displayed after rotation is applied. */
    fun uprightWidth(streamWidth: Int, streamHeight: Int, rotationDegrees: Int): Int =
        if (rotationDegrees % 180 == 0) streamWidth else streamHeight

    /** Height of the frame as displayed after rotation is applied. */
    fun uprightHeight(streamWidth: Int, streamHeight: Int, rotationDegrees: Int): Int =
        if (rotationDegrees % 180 == 0) streamHeight else streamWidth

    /**
     * Texture matrix for the ENCODER pass: the SurfaceTexture transform only —
     * the recorded frames are the raw buffer, untouched.
     */
    fun encoderTexMatrix(surfaceTextureMatrix: FloatArray): FloatArray =
        surfaceTextureMatrix.copyOf()

    /**
     * Texture matrix for the PREVIEW pass: shows the frame world-upright,
     * i.e. applies the same rotation a video player will apply from metadata.
     *
     * Derivation (unit-tested): "display = buffer rotated θ clockwise" in
     * post-SurfaceTexture texture space (origin bottom-left) is a texcoord
     * rotation of +θ (counter-clockwise-positive, android.opengl.Matrix
     * convention) about the texture centre. For θ=90 the corner mapping is
     * (s,t) → (1−t, s).
     */
    fun previewTexMatrix(surfaceTextureMatrix: FloatArray, rotationDegrees: Int): FloatArray {
        if (rotationDegrees % 360 == 0) return surfaceTextureMatrix.copyOf()
        val rot = FloatArray(16)
        Matrix.setIdentityM(rot, 0)
        Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
        Matrix.rotateM(rot, 0, rotationDegrees.toFloat(), 0f, 0f, 1f)
        Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, surfaceTextureMatrix, 0, rot, 0)
        return result
    }

    /**
     * NDC rectangle [left, bottom, right, top] where the burned-in overlay
     * strip must be drawn IN BUFFER SPACE so that, after the player applies
     * the rotation metadata, it appears as a horizontal strip along the
     * bottom of the upright video.
     *
     * Buffer-space mapping (derived pixel-by-pixel, unit-tested):
     *   θ=0   → bottom edge      θ=90  → right edge (vertical)
     *   θ=180 → top edge         θ=270 → left edge (vertical)
     *
     * @param stripFraction the strip thickness as a fraction of the UPRIGHT
     *   frame height (e.g. 1/12).
     */
    fun overlayRectNdc(rotationDegrees: Int, stripFraction: Float): FloatArray {
        // The strip is stripFraction of the upright height; along whichever
        // buffer axis that maps to, the NDC thickness is 2 × stripFraction.
        val t = 2f * stripFraction
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> floatArrayOf(1f - t, -1f, 1f, 1f)   // right edge
            270 -> floatArrayOf(-1f, -1f, -1f + t, 1f) // left edge
            180 -> floatArrayOf(-1f, 1f - t, 1f, 1f)   // top edge
            else -> floatArrayOf(-1f, -1f, 1f, -1f + t) // bottom edge
        }
    }
}
