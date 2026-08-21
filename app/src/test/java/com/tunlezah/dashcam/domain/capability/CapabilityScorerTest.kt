package com.tunlezah.dashcam.domain.capability

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.testutil.DeviceFixtures
import org.junit.Test

class CapabilityScorerTest {

    @Test
    fun `moto g04 fixture scores as constrained`() {
        assertThat(CapabilityScorer.tier(DeviceFixtures.motoG04())).isEqualTo(DeviceTier.CONSTRAINED)
    }

    @Test
    fun `edge 60 fusion fixture scores as capable`() {
        assertThat(CapabilityScorer.tier(DeviceFixtures.edge60Fusion())).isEqualTo(DeviceTier.CAPABLE)
    }

    @Test
    fun `mid-range device scores as balanced`() {
        val mid = DeviceFixtures.motoG04().copy(
            totalRamMb = 6000,
            videoEncoders = DeviceFixtures.motoG04().videoEncoders.map {
                if (it.mimeType == "video/hevc") it.copy(hardwareAccelerated = true) else it
            },
        )
        assertThat(CapabilityScorer.tier(mid)).isEqualTo(DeviceTier.BALANCED)
    }

    @Test
    fun `low ram flag reduces score`() {
        val g04 = DeviceFixtures.motoG04()
        val lowRam = g04.copy(isLowRamDevice = true)
        assertThat(CapabilityScorer.score(lowRam)).isLessThan(CapabilityScorer.score(g04))
    }
}
