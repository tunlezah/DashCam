package com.tunlezah.dashcam.domain.thermal

import android.os.PowerManager

/**
 * Pure classification logic (unit tested): raw thermal signals → [ThermalState].
 *
 * Priority order (best signal wins):
 *  1. Thermal headroom — forecast-based, lets the app act BEFORE throttling.
 *  2. Platform thermal status — reactive but widely supported.
 *  3. Battery temperature — last resort on devices where both APIs are absent
 *     or lie (common on budget hardware; see docs/research/android-media-apis.md §4).
 *
 * Headroom thresholds: 1.0 == the SEVERE-throttling threshold by definition,
 * so the ladder is placed to trigger mitigation well before that.
 */
object ThermalClassifier {

    fun fromHeadroom(headroom: Float): ThermalState? {
        if (headroom.isNaN() || headroom <= 0f) return null
        return when {
            headroom < 0.65f -> ThermalState.NOMINAL
            headroom < 0.80f -> ThermalState.WARM
            headroom < 0.90f -> ThermalState.ELEVATED
            headroom < 1.00f -> ThermalState.HIGH
            else -> ThermalState.CRITICAL
        }
    }

    fun fromStatus(status: Int): ThermalState? = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> ThermalState.NOMINAL
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalState.WARM
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalState.ELEVATED
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalState.HIGH
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalState.CRITICAL
        else -> null
    }

    /**
     * Battery temperature ladder. Li-ion comfort ceiling is ~45 °C; hardware
     * dashcams self-shutdown around 70–75 °C internal (battery reads lower).
     */
    fun fromBatteryTemp(tempC: Float): ThermalState? {
        if (tempC.isNaN() || tempC < -20f) return null
        return when {
            tempC < 38f -> ThermalState.NOMINAL
            tempC < 41f -> ThermalState.WARM
            tempC < 44f -> ThermalState.ELEVATED
            tempC < 47f -> ThermalState.HIGH
            else -> ThermalState.CRITICAL
        }
    }

    /**
     * Merge available signals: the most severe assessment wins (a stale/optimistic
     * source must never mask a pessimistic one), and the winning source is recorded.
     */
    fun classify(
        headroom: Float,
        platformStatus: Int,
        batteryTempC: Float,
    ): Pair<ThermalState, ThermalSignalSource> {
        val candidates = listOfNotNull(
            fromHeadroom(headroom)?.let { it to ThermalSignalSource.HEADROOM_API },
            fromStatus(platformStatus)?.let { it to ThermalSignalSource.STATUS_API },
            fromBatteryTemp(batteryTempC)?.let { it to ThermalSignalSource.BATTERY_TEMPERATURE },
        )
        return candidates.maxByOrNull { it.first.severity }
            ?: (ThermalState.NOMINAL to ThermalSignalSource.NONE)
    }
}
