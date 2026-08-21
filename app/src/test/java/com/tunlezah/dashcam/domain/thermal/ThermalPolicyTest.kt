package com.tunlezah.dashcam.domain.thermal

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalPolicyTest {

    @Test
    fun `nominal state applies no mitigations`() {
        val m = ThermalPolicy.mitigationsFor(ThermalState.NOMINAL)
        assertThat(m.profileStepDowns).isEqualTo(0)
        assertThat(m.detachPreview).isFalse()
        assertThat(m.pauseMap).isFalse()
        assertThat(m.pauseWeather).isFalse()
        assertThat(m.stopRecording).isFalse()
    }

    @Test
    fun `mitigations grow monotonically with severity`() {
        var lastStepDowns = -1
        var lastPreviewCap = Int.MAX_VALUE
        var lastGpsMultiplier = 0
        for (state in ThermalState.entries) {
            val m = ThermalPolicy.mitigationsFor(state)
            assertThat(m.profileStepDowns).isAtLeast(lastStepDowns)
            assertThat(m.previewFpsCap).isAtMost(lastPreviewCap)
            assertThat(m.gpsIntervalMultiplier).isAtLeast(lastGpsMultiplier)
            lastStepDowns = m.profileStepDowns
            lastPreviewCap = m.previewFpsCap
            lastGpsMultiplier = m.gpsIntervalMultiplier
        }
    }

    @Test
    fun `optional features shed before video quality`() {
        // Weather pauses at WARM, before any profile step-down…
        assertThat(ThermalPolicy.mitigationsFor(ThermalState.WARM).pauseWeather).isTrue()
        assertThat(ThermalPolicy.mitigationsFor(ThermalState.WARM).profileStepDowns).isEqualTo(0)
        // …map pauses at ELEVATED, still before quality degrades…
        assertThat(ThermalPolicy.mitigationsFor(ThermalState.ELEVATED).pauseMap).isTrue()
        assertThat(ThermalPolicy.mitigationsFor(ThermalState.ELEVATED).profileStepDowns).isEqualTo(0)
        // …video quality only degrades at HIGH.
        assertThat(ThermalPolicy.mitigationsFor(ThermalState.HIGH).profileStepDowns).isAtLeast(1)
    }

    @Test
    fun `recording never stops via the mitigation ladder itself`() {
        for (state in ThermalState.entries) {
            assertThat(ThermalPolicy.mitigationsFor(state).stopRecording).isFalse()
        }
    }

    @Test
    fun `stop only at platform emergency or worse`() {
        assertThat(ThermalPolicy.shouldStopForPlatformStatus(PowerManager.THERMAL_STATUS_SEVERE)).isFalse()
        assertThat(ThermalPolicy.shouldStopForPlatformStatus(PowerManager.THERMAL_STATUS_CRITICAL)).isFalse()
        assertThat(ThermalPolicy.shouldStopForPlatformStatus(PowerManager.THERMAL_STATUS_EMERGENCY)).isTrue()
        assertThat(ThermalPolicy.shouldStopForPlatformStatus(PowerManager.THERMAL_STATUS_SHUTDOWN)).isTrue()
    }
}
