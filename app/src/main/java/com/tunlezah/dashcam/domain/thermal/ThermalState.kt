package com.tunlezah.dashcam.domain.thermal

/**
 * The app's internal thermal severity ladder. Coarser than Android's seven
 * PowerManager statuses because mitigation decisions only need five rungs.
 */
enum class ThermalState {
    NOMINAL,
    WARM,
    ELEVATED,
    HIGH,
    CRITICAL;

    val severity: Int get() = ordinal
}

/** Where a thermal reading came from — shown in diagnostics. */
enum class ThermalSignalSource { HEADROOM_API, STATUS_API, BATTERY_TEMPERATURE, SIMULATED, NONE }

data class ThermalSnapshot(
    val state: ThermalState,
    /** getThermalHeadroom(30) value, NaN when unsupported. */
    val headroom: Float,
    /** PowerManager.THERMAL_STATUS_* value, -1 when unknown. */
    val platformStatus: Int,
    /** Battery temperature in °C, NaN when unknown. */
    val batteryTempC: Float,
    val source: ThermalSignalSource,
    val timestampMs: Long,
) {
    companion object {
        val UNKNOWN = ThermalSnapshot(
            state = ThermalState.NOMINAL,
            headroom = Float.NaN,
            platformStatus = -1,
            batteryTempC = Float.NaN,
            source = ThermalSignalSource.NONE,
            timestampMs = 0,
        )
    }
}
