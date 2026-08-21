package com.tunlezah.dashcam.recording.engine

import android.opengl.Matrix

/**
 * Pure orientation/crop math for the GL stage (unit tested — this is where a
 * sign error silently records sideways video).
 *
 * Terminology:
 *  - sensorOrientation: CameraCharacteristics.SENSOR_ORIENTATION (0/90/180/270)
 *  - mountRotation: how the DEVICE is physically rotated from natural
 *    (portrait) orientation, derived from the accelerometer gravity vector
 *    because a dashcam runs with a locked UI and often a switched-off screen —
 *    the Display rotation is useless for a windshield cradle.
 */
object FrameGeometry {

    /**
     * Device rotation (0/90/180/270) from gravity in device coordinates.
     * Android device axes: +x right, +y up (portrait), z out of the screen.
     * Gravity pulls DOWN, so when upright portrait the accelerometer reads
     * +9.8 on +y.
     *
     * 90 = device rotated counter-clockwise (landscape, left edge up —
     * Surface.ROTATION_90 equivalent).
     */
    fun mountRotationFromGravity(ax: Float, ay: Float): Int {
        return if (kotlin.math.abs(ay) >= kotlin.math.abs(ax)) {
            if (ay >= 0) 0 else 180
        } else {
            if (ax >= 0) 90 else 270
        }
    }

    /**
     * How many degrees the camera buffer content must be rotated clockwise to
     * appear world-upright. Standard Camera2 relative-rotation formula.
     */
    fun bufferRotationDegrees(sensorOrientation: Int, mountRotation: Int, facingFront: Boolean): Int {
        return if (facingFront) {
            (sensorOrientation + mountRotation) % 360
        } else {
            (sensorOrientation - mountRotation + 360) % 360
        }
    }

    /**
     * Output (encoded) size for a given camera stream size, rotation, and crop
     * policy. When the upright image is portrait (rotation 90/270 relative to
     * the landscape stream) and a 16:9 crop is requested, the result is a
     * landscape band cut from the portrait image: width = stream short side,
     * height = width * 9/16 — see docs/architecture.md §11 for the physics.
     */
    data class OutputGeometry(
        val outputWidth: Int,
        val outputHeight: Int,
        /** Rotation applied in the shader (degrees clockwise). */
        val rotationDegrees: Int,
        /** Crop of the upright image, normalized [0..1]: left, top, right, bottom. */
        val cropLeft: Float,
        val cropTop: Float,
        val cropRight: Float,
        val cropBottom: Float,
    )

    fun solve(
        streamWidth: Int,
        streamHeight: Int,
        rotationDegrees: Int,
        cropTo16x9Landscape: Boolean,
        /** 0 = crop band centred; negative moves it up (sky), positive down (bonnet). */
        verticalBias: Float = -0.08f,
    ): OutputGeometry {
        val sideways = rotationDegrees == 90 || rotationDegrees == 270
        val uprightWidth = if (sideways) streamHeight else streamWidth
        val uprightHeight = if (sideways) streamWidth else streamHeight

        if (!sideways || !cropTo16x9Landscape) {
            return OutputGeometry(
                outputWidth = align2(uprightWidth),
                outputHeight = align2(uprightHeight),
                rotationDegrees = rotationDegrees,
                cropLeft = 0f, cropTop = 0f, cropRight = 1f, cropBottom = 1f,
            )
        }

        // Portrait upright image (e.g. 1080×1920): cut a 16:9 landscape band.
        val outW = uprightWidth
        val outH = (uprightWidth * 9 / 16)
        val bandFraction = outH.toFloat() / uprightHeight
        val centre = 0.5f + verticalBias
        var top = centre - bandFraction / 2f
        top = top.coerceIn(0f, 1f - bandFraction)
        return OutputGeometry(
            outputWidth = align2(outW),
            outputHeight = align2(outH),
            rotationDegrees = rotationDegrees,
            cropLeft = 0f,
            cropTop = top,
            cropRight = 1f,
            cropBottom = top + bandFraction,
        )
    }

    /**
     * Composes the texture-coordinate matrix handed to the OES shader:
     * final = surfaceTextureMatrix × rotation × crop, all in texture space.
     * The crop rect is specified on the UPRIGHT image; rotation maps upright
     * coordinates back onto the raw buffer the SurfaceTexture matrix expects.
     */
    fun composeTexMatrix(
        surfaceTextureMatrix: FloatArray,
        geometry: OutputGeometry,
    ): FloatArray {
        val crop = FloatArray(16)
        Matrix.setIdentityM(crop, 0)
        // Texture space: (0,0) is the first row of the buffer. The upright-image
        // crop [left,top,right,bottom] maps to a scale+translate.
        val sx = geometry.cropRight - geometry.cropLeft
        val sy = geometry.cropBottom - geometry.cropTop
        Matrix.translateM(crop, 0, geometry.cropLeft, geometry.cropTop, 0f)
        Matrix.scaleM(crop, 0, sx, sy, 1f)

        val rot = FloatArray(16)
        Matrix.setIdentityM(rot, 0)
        if (geometry.rotationDegrees != 0) {
            // Rotate about the texture centre. Output quad texcoords are in
            // upright-image space; rotating by -degrees maps them onto raw
            // buffer coordinates.
            Matrix.translateM(rot, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(rot, 0, -geometry.rotationDegrees.toFloat(), 0f, 0f, 1f)
            Matrix.translateM(rot, 0, -0.5f, -0.5f, 0f)
        }

        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, rot, 0, crop, 0)
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, surfaceTextureMatrix, 0, tmp, 0)
        return result
    }

    private fun align2(v: Int): Int = v and 1.inv()
}
