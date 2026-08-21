package com.tunlezah.dashcam.domain.thermal

import android.os.PowerManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalClassifierTest {

    @Test
    fun `headroom ladder maps to expected states`() {
        assertThat(ThermalClassifier.fromHeadroom(0.3f)).isEqualTo(ThermalState.NOMINAL)
        assertThat(ThermalClassifier.fromHeadroom(0.7f)).isEqualTo(ThermalState.WARM)
        assertThat(ThermalClassifier.fromHeadroom(0.85f)).isEqualTo(ThermalState.ELEVATED)
        assertThat(ThermalClassifier.fromHeadroom(0.95f)).isEqualTo(ThermalState.HIGH)
        assertThat(ThermalClassifier.fromHeadroom(1.1f)).isEqualTo(ThermalState.CRITICAL)
    }

    @Test
    fun `nan or non-positive headroom is unusable`() {
        assertThat(ThermalClassifier.fromHeadroom(Float.NaN)).isNull()
        assertThat(ThermalClassifier.fromHeadroom(0f)).isNull()
    }

    @Test
    fun `platform status maps to expected states`() {
        assertThat(ThermalClassifier.fromStatus(PowerManager.THERMAL_STATUS_NONE))
            .isEqualTo(ThermalState.NOMINAL)
        assertThat(ThermalClassifier.fromStatus(PowerManager.THERMAL_STATUS_MODERATE))
            .isEqualTo(ThermalState.ELEVATED)
        assertThat(ThermalClassifier.fromStatus(PowerManager.THERMAL_STATUS_SEVERE))
            .isEqualTo(ThermalState.HIGH)
        assertThat(ThermalClassifier.fromStatus(PowerManager.THERMAL_STATUS_EMERGENCY))
            .isEqualTo(ThermalState.CRITICAL)
        assertThat(ThermalClassifier.fromStatus(-1)).isNull()
    }

    @Test
    fun `battery temperature fallback ladder`() {
        assertThat(ThermalClassifier.fromBatteryTemp(30f)).isEqualTo(ThermalState.NOMINAL)
        assertThat(ThermalClassifier.fromBatteryTemp(39f)).isEqualTo(ThermalState.WARM)
        assertThat(ThermalClassifier.fromBatteryTemp(42f)).isEqualTo(ThermalState.ELEVATED)
        assertThat(ThermalClassifier.fromBatteryTemp(45f)).isEqualTo(ThermalState.HIGH)
        assertThat(ThermalClassifier.fromBatteryTemp(50f)).isEqualTo(ThermalState.CRITICAL)
        assertThat(ThermalClassifier.fromBatteryTemp(Float.NaN)).isNull()
    }

    @Test
    fun `merge takes the most severe available signal`() {
        // Optimistic headroom must not mask a pessimistic platform status.
        val (state, source) = ThermalClassifier.classify(
            headroom = 0.3f,
            platformStatus = PowerManager.THERMAL_STATUS_SEVERE,
            batteryTempC = 30f,
        )
        assertThat(state).isEqualTo(ThermalState.HIGH)
        assertThat(source).isEqualTo(ThermalSignalSource.STATUS_API)
    }

    @Test
    fun `no signals defaults to nominal`() {
        val (state, source) = ThermalClassifier.classify(Float.NaN, -1, Float.NaN)
        assertThat(state).isEqualTo(ThermalState.NOMINAL)
        assertThat(source).isEqualTo(ThermalSignalSource.NONE)
    }
}
