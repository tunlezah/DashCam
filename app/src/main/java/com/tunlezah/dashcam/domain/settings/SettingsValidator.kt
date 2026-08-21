package com.tunlezah.dashcam.domain.settings

/**
 * Clamps every settings value into its safe range. Applied on every read and
 * write so a corrupted/hand-edited preference store can never push the app
 * into an absurd configuration (e.g. a pre-event buffer larger than the loop,
 * or a loop allocation that would fill the device).
 */
object SettingsValidator {

    val SEGMENT_MINUTES_OPTIONS = listOf(1, 3, 5, 10)
    val PRE_EVENT_OPTIONS = listOf(10, 15, 30, 45, 60)
    val POST_EVENT_OPTIONS = listOf(30, 60, 90, 120)
    val LOOP_SIZE_OPTIONS_GIB = listOf(2L, 5L, 10L, 20L, 30L)

    const val STARTUP_DELAY_MIN = 0
    const val STARTUP_DELAY_MAX = 30
    const val MIN_LOOP_BYTES = 1L * DashcamSettings.GIB
    const val MAX_LOOP_BYTES = 128L * DashcamSettings.GIB
    const val MIN_PROTECTED_BYTES = 512L * 1024 * 1024
    const val MAX_PROTECTED_BYTES = 64L * DashcamSettings.GIB
    const val UNPLUG_DELAY_MIN = 5
    const val UNPLUG_DELAY_MAX = 600
    const val BATTERY_FLOOR_MIN = 5
    const val BATTERY_FLOOR_MAX = 50
    const val GPS_INTERVAL_MIN_MS = 500L
    const val GPS_INTERVAL_MAX_MS = 10_000L
    const val MAX_CUSTOM_LABEL_LENGTH = 24
    val FPS_OPTIONS = listOf(24, 25, 30, 60)
    val BITRATE_RANGE_BPS = 1_000_000..80_000_000

    fun validate(s: DashcamSettings): DashcamSettings = s.copy(
        manualFps = s.manualFps.coerceToAllowed(FPS_OPTIONS, default = 30),
        manualBitrateBps = if (s.manualBitrateBps == 0) 0
        else s.manualBitrateBps.coerceIn(BITRATE_RANGE_BPS),
        segmentMinutes = s.segmentMinutes.coerceToAllowed(SEGMENT_MINUTES_OPTIONS, default = 3),
        startupDelaySeconds = s.startupDelaySeconds.coerceIn(STARTUP_DELAY_MIN, STARTUP_DELAY_MAX),
        preEventSeconds = s.preEventSeconds.coerceToAllowed(PRE_EVENT_OPTIONS, default = 30),
        postEventSeconds = s.postEventSeconds.coerceToAllowed(POST_EVENT_OPTIONS, default = 60),
        loopMaxBytes = s.loopMaxBytes.coerceIn(MIN_LOOP_BYTES, MAX_LOOP_BYTES),
        protectedBudgetBytes = s.protectedBudgetBytes.coerceIn(MIN_PROTECTED_BYTES, MAX_PROTECTED_BYTES),
        unplugStopDelaySeconds = s.unplugStopDelaySeconds.coerceIn(UNPLUG_DELAY_MIN, UNPLUG_DELAY_MAX),
        batteryFloorPercent = s.batteryFloorPercent.coerceIn(BATTERY_FLOOR_MIN, BATTERY_FLOOR_MAX),
        gpsUpdateIntervalMs = s.gpsUpdateIntervalMs.coerceIn(GPS_INTERVAL_MIN_MS, GPS_INTERVAL_MAX_MS),
        overlayCustomLabel = s.overlayCustomLabel.take(MAX_CUSTOM_LABEL_LENGTH),
        mapMaxFps = s.mapMaxFps.coerceIn(5, 30),
    )

    /**
     * The pre-event window can never exceed what the loop can actually hold:
     * it is limited to (segment length + one full segment) because protection
     * works on whole segments plus the in-progress one.
     */
    fun effectivePreEventSeconds(s: DashcamSettings): Int =
        s.preEventSeconds.coerceAtMost(s.segmentMinutes * 60 + 60)

    private fun Int.coerceToAllowed(allowed: List<Int>, default: Int): Int =
        if (this in allowed) this else allowed.minByOrNull { kotlin.math.abs(it - this) } ?: default
}
