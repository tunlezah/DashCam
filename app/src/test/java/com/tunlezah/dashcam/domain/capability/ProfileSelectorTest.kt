package com.tunlezah.dashcam.domain.capability

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.domain.settings.QualityMode
import com.tunlezah.dashcam.domain.settings.VideoCodec
import com.tunlezah.dashcam.domain.settings.VideoResolution
import com.tunlezah.dashcam.testutil.DeviceFixtures
import org.junit.Test

class ProfileSelectorTest {

    private val auto = DashcamSettings(qualityMode = QualityMode.AUTO)

    @Test
    fun `auto on moto g04 is 1080p30 h264 at constrained bitrate`() {
        val p = ProfileSelector.select(DeviceFixtures.motoG04(), auto, DeviceTier.CONSTRAINED)
        assertThat(p.width).isEqualTo(1920)
        assertThat(p.height).isEqualTo(1080)
        assertThat(p.fps).isEqualTo(30)
        assertThat(p.codec).isEqualTo(VideoCodec.H264)
        assertThat(p.bitrateBps).isEqualTo(10_000_000)
    }

    @Test
    fun `auto on edge 60 fusion stays at 1080p30 h264 but higher bitrate`() {
        val p = ProfileSelector.select(DeviceFixtures.edge60Fusion(), auto, DeviceTier.CAPABLE)
        assertThat(p.height).isEqualTo(1080)
        assertThat(p.fps).isEqualTo(30)
        assertThat(p.codec).isEqualTo(VideoCodec.H264)
        assertThat(p.bitrateBps).isEqualTo(14_000_000)
    }

    @Test
    fun `manual 4k on moto g04 is clamped to 1080p`() {
        val settings = auto.copy(
            qualityMode = QualityMode.MANUAL,
            manualResolution = VideoResolution.UHD_2160,
        )
        val p = ProfileSelector.select(DeviceFixtures.motoG04(), settings, DeviceTier.CONSTRAINED)
        assertThat(p.height).isEqualTo(1080)
        assertThat(p.rationale).contains("clamped")
    }

    @Test
    fun `manual 4k on edge 60 fusion is honoured`() {
        val settings = auto.copy(
            qualityMode = QualityMode.MANUAL,
            manualResolution = VideoResolution.UHD_2160,
        )
        val p = ProfileSelector.select(DeviceFixtures.edge60Fusion(), settings, DeviceTier.CAPABLE)
        assertThat(p.width).isEqualTo(3840)
        assertThat(p.height).isEqualTo(2160)
    }

    @Test
    fun `manual hevc without hardware encoder falls back to h264`() {
        val settings = auto.copy(qualityMode = QualityMode.MANUAL, manualCodec = VideoCodec.HEVC)
        val p = ProfileSelector.select(DeviceFixtures.motoG04(), settings, DeviceTier.CONSTRAINED)
        assertThat(p.codec).isEqualTo(VideoCodec.H264)
        assertThat(p.rationale).contains("no hardware encoder")
    }

    @Test
    fun `manual hevc with hardware encoder is honoured`() {
        val settings = auto.copy(qualityMode = QualityMode.MANUAL, manualCodec = VideoCodec.HEVC)
        val p = ProfileSelector.select(DeviceFixtures.edge60Fusion(), settings, DeviceTier.CAPABLE)
        assertThat(p.codec).isEqualTo(VideoCodec.HEVC)
    }

    @Test
    fun `manual 60fps on moto g04 is clamped to camera max`() {
        val settings = auto.copy(qualityMode = QualityMode.MANUAL, manualFps = 60)
        val p = ProfileSelector.select(DeviceFixtures.motoG04(), settings, DeviceTier.CONSTRAINED)
        assertThat(p.fps).isEqualTo(30)
    }

    @Test
    fun `step down reduces bitrate first then resolution then reaches floor`() {
        var p = ProfileSelector.select(DeviceFixtures.motoG04(), auto, DeviceTier.CONSTRAINED)
        // Step 1: bitrate reduction, geometry unchanged.
        val s1 = ProfileSelector.stepDown(p)!!
        assertThat(s1.height).isEqualTo(1080)
        assertThat(s1.bitrateBps).isLessThan(p.bitrateBps)

        // Keep stepping: must terminate at the floor (null), never loop forever.
        var current: RecordingProfile? = s1
        var steps = 0
        while (current != null && steps < 20) {
            current = ProfileSelector.stepDown(current)
            steps++
        }
        assertThat(current).isNull()
        assertThat(steps).isLessThan(20)
    }

    @Test
    fun `unknown capabilities fall back to 720p rather than failing`() {
        val blind = DeviceFixtures.motoG04().copy(cameras = emptyList(), videoEncoders = emptyList())
        val p = ProfileSelector.select(blind, auto, DeviceTier.CONSTRAINED)
        assertThat(p.height).isEqualTo(720)
    }
}
