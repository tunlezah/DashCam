package com.tunlezah.dashcam.recording.engine

import android.opengl.Matrix
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FrameGeometryTest {

    // ------------- mount rotation from gravity -------------

    @Test
    fun `portrait upright reads gravity on +y`() {
        assertThat(FrameGeometry.mountRotationFromGravity(0.2f, 9.7f)).isEqualTo(0)
    }

    @Test
    fun `upside down portrait`() {
        assertThat(FrameGeometry.mountRotationFromGravity(0.1f, -9.7f)).isEqualTo(180)
    }

    @Test
    fun `landscape left edge down reads gravity on +x`() {
        assertThat(FrameGeometry.mountRotationFromGravity(9.6f, 0.5f)).isEqualTo(90)
    }

    @Test
    fun `landscape right edge down reads gravity on -x`() {
        assertThat(FrameGeometry.mountRotationFromGravity(-9.6f, 0.5f)).isEqualTo(270)
    }

    // ------------- rotation metadata (Camera2 relative-rotation formula) -------------

    @Test
    fun `back camera portrait needs 90 degree metadata (sensor orientation 90)`() {
        assertThat(FrameGeometry.bufferRotationDegrees(90, 0, facingFront = false)).isEqualTo(90)
    }

    @Test
    fun `back camera landscape mount needs no rotation`() {
        assertThat(FrameGeometry.bufferRotationDegrees(90, 90, facingFront = false)).isEqualTo(0)
    }

    @Test
    fun `back camera reverse landscape needs 180`() {
        assertThat(FrameGeometry.bufferRotationDegrees(90, 270, facingFront = false)).isEqualTo(180)
    }

    @Test
    fun `front camera portrait (sensor orientation 270)`() {
        assertThat(FrameGeometry.bufferRotationDegrees(270, 0, facingFront = true)).isEqualTo(270)
    }

    // ------------- upright display dimensions -------------

    @Test
    fun `upright dims swap only for sideways rotations`() {
        assertThat(FrameGeometry.uprightWidth(1920, 1080, 0)).isEqualTo(1920)
        assertThat(FrameGeometry.uprightHeight(1920, 1080, 0)).isEqualTo(1080)
        assertThat(FrameGeometry.uprightWidth(1920, 1080, 90)).isEqualTo(1080)
        assertThat(FrameGeometry.uprightHeight(1920, 1080, 90)).isEqualTo(1920)
        assertThat(FrameGeometry.uprightWidth(1920, 1080, 180)).isEqualTo(1920)
        assertThat(FrameGeometry.uprightWidth(1920, 1080, 270)).isEqualTo(1080)
    }

    // ------------- texture matrices -------------

    private fun identity(): FloatArray = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private fun apply(m: FloatArray, s: Float, t: Float): Pair<Float, Float> {
        val v = floatArrayOf(s, t, 0f, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, m, 0, v, 0)
        return out[0] to out[1]
    }

    @Test
    fun `encoder matrix is the surface-texture transform untouched`() {
        val st = identity()
        val m = FrameGeometry.encoderTexMatrix(st)
        assertThat(m.toList()).isEqualTo(st.toList())
    }

    /**
     * "Display = buffer rotated 90° clockwise" corner mapping in texture space
     * (origin bottom-left): display(0,0) samples buffer(1,0); display(1,0) →
     * buffer(1,1); display(0,1) → buffer(0,0); display(1,1) → buffer(0,1).
     * I.e. (s,t) → (1−t, s).
     */
    @Test
    fun `preview matrix for 90 maps corners like a player rotating clockwise`() {
        val m = FrameGeometry.previewTexMatrix(identity(), 90)
        val tolerance = 1e-4f
        for ((input, expected) in listOf(
            (0f to 0f) to (1f to 0f),
            (1f to 0f) to (1f to 1f),
            (0f to 1f) to (0f to 0f),
            (1f to 1f) to (0f to 1f),
        )) {
            val (s, t) = apply(m, input.first, input.second)
            assertThat(s).isWithin(tolerance).of(expected.first)
            assertThat(t).isWithin(tolerance).of(expected.second)
        }
    }

    @Test
    fun `preview matrix for 180 maps corners to their opposites`() {
        val m = FrameGeometry.previewTexMatrix(identity(), 180)
        val (s, t) = apply(m, 0f, 0f)
        assertThat(s).isWithin(1e-4f).of(1f)
        assertThat(t).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `preview matrix for 270 is the inverse of 90`() {
        val m90 = FrameGeometry.previewTexMatrix(identity(), 90)
        val m270 = FrameGeometry.previewTexMatrix(identity(), 270)
        // 270 applied after 90 should be identity on any point.
        val (s1, t1) = apply(m90, 0.25f, 0.75f)
        val (s2, t2) = apply(m270, s1, t1)
        assertThat(s2).isWithin(1e-4f).of(0.25f)
        assertThat(t2).isWithin(1e-4f).of(0.75f)
    }

    @Test
    fun `preview matrix for 0 is a passthrough`() {
        val m = FrameGeometry.previewTexMatrix(identity(), 0)
        val (s, t) = apply(m, 0.3f, 0.7f)
        assertThat(s).isWithin(1e-4f).of(0.3f)
        assertThat(t).isWithin(1e-4f).of(0.7f)
    }

    // ------------- preview aspect-fit (letterbox) -------------

    @Test
    fun `aspect fit letterboxes a wide image in a tall viewport`() {
        // 16:9 image into a 1:1 viewport → full width, reduced height.
        val r = FrameGeometry.aspectFitRectNdc(1920, 1080, 1000, 1000)
        assertThat(r[0]).isWithin(1e-4f).of(-1f)
        assertThat(r[2]).isWithin(1e-4f).of(1f)
        assertThat(r[3]).isWithin(1e-4f).of(1080f / 1920f)
        assertThat(r[1]).isWithin(1e-4f).of(-1080f / 1920f)
    }

    @Test
    fun `aspect fit pillarboxes a tall image in a wide viewport`() {
        // 9:16 upright image into a 300dp-ish wide box → full height, reduced width.
        val r = FrameGeometry.aspectFitRectNdc(1080, 1920, 1000, 750)
        assertThat(r[1]).isWithin(1e-4f).of(-1f)
        assertThat(r[3]).isWithin(1e-4f).of(1f)
        val expectedHalfWidth = (1080f / 1920f) / (1000f / 750f)
        assertThat(r[2]).isWithin(1e-4f).of(expectedHalfWidth)
    }

    @Test
    fun `aspect fit with matching aspect fills the viewport`() {
        val r = FrameGeometry.aspectFitRectNdc(1920, 1080, 960, 540)
        assertThat(r.toList()).isEqualTo(listOf(-1f, -1f, 1f, 1f))
    }

    @Test
    fun `aspect fit degrades safely on zero dimensions`() {
        val r = FrameGeometry.aspectFitRectNdc(0, 0, 100, 100)
        assertThat(r.toList()).isEqualTo(listOf(-1f, -1f, 1f, 1f))
    }

    // ------------- overlay placement in buffer space -------------

    @Test
    fun `overlay strip sits on the edge that becomes the display bottom`() {
        val f = 1f / 12f
        val t = 2f * f
        // θ=0: bottom edge of the buffer.
        assertThat(FrameGeometry.overlayRectNdc(0, f).toList())
            .isEqualTo(listOf(-1f, -1f, 1f, -1f + t))
        // θ=90 (portrait mount, back camera): right edge, vertical.
        assertThat(FrameGeometry.overlayRectNdc(90, f).toList())
            .isEqualTo(listOf(1f - t, -1f, 1f, 1f))
        // θ=180: top edge.
        assertThat(FrameGeometry.overlayRectNdc(180, f).toList())
            .isEqualTo(listOf(-1f, 1f - t, 1f, 1f))
        // θ=270: left edge, vertical.
        assertThat(FrameGeometry.overlayRectNdc(270, f).toList())
            .isEqualTo(listOf(-1f, -1f, -1f + t, 1f))
    }
}
