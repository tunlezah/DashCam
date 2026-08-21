package com.tunlezah.dashcam.recording.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

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

    // ------------- buffer rotation -------------

    @Test
    fun `back camera portrait needs 90 degree rotation (sensor orientation 90)`() {
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

    // ------------- output geometry -------------

    @Test
    fun `landscape mount records the native landscape stream`() {
        val g = FrameGeometry.solve(1920, 1080, rotationDegrees = 0, cropTo16x9Landscape = true)
        assertThat(g.outputWidth).isEqualTo(1920)
        assertThat(g.outputHeight).isEqualTo(1080)
        assertThat(g.cropTop).isEqualTo(0f)
        assertThat(g.cropBottom).isEqualTo(1f)
    }

    @Test
    fun `portrait mount with 16x9 crop produces a landscape band`() {
        val g = FrameGeometry.solve(1920, 1080, rotationDegrees = 90, cropTo16x9Landscape = true)
        // Upright image is 1080×1920; the 16:9 band is 1080 wide.
        assertThat(g.outputWidth).isEqualTo(1080)
        assertThat(g.outputHeight).isEqualTo(606) // 1080*9/16 = 607 → align2 → 606
        assertThat(g.cropBottom - g.cropTop).isWithin(0.01f).of(607f / 1920f)
        // Band sits slightly above centre (sky bias), never out of range.
        assertThat(g.cropTop).isAtLeast(0f)
        assertThat(g.cropBottom).isAtMost(1f)
        assertThat(g.cropTop).isLessThan(0.5f - (607f / 1920f) / 2f + 0.001f)
    }

    @Test
    fun `portrait mount full frame keeps the whole portrait image`() {
        val g = FrameGeometry.solve(1920, 1080, rotationDegrees = 90, cropTo16x9Landscape = false)
        assertThat(g.outputWidth).isEqualTo(1080)
        assertThat(g.outputHeight).isEqualTo(1920)
        assertThat(g.cropTop).isEqualTo(0f)
    }

    @Test
    fun `output dimensions are always even`() {
        for (rotation in listOf(0, 90, 180, 270)) {
            for (crop in listOf(true, false)) {
                val g = FrameGeometry.solve(1919, 1079, rotation, crop)
                assertThat(g.outputWidth % 2).isEqualTo(0)
                assertThat(g.outputHeight % 2).isEqualTo(0)
            }
        }
    }
}
