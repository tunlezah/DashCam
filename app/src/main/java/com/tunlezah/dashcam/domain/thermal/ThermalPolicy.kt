package com.tunlezah.dashcam.domain.thermal

/**
 * The mitigation ladder: what the app sheds at each thermal state. Pure and
 * unit-tested. The ordering principle (docs/thermal-management.md):
 * shed *optional* work first, degrade *video quality* only when necessary,
 * stop recording only to prevent platform emergency shutdown.
 */
data class ThermalMitigations(
    /** Cap the preview render rate (encoder rate is unaffected). */
    val previewFpsCap: Int,
    /** Detach the preview surface entirely (UI shows a static banner). */
    val detachPreview: Boolean,
    /** Pause offline map rendering. */
    val pauseMap: Boolean,
    /** Suspend optional weather refresh. */
    val pauseWeather: Boolean,
    /** Multiply the GPS update interval (1 = normal). */
    val gpsIntervalMultiplier: Int,
    /** How many ProfileSelector.stepDown() steps to apply to the video profile. */
    val profileStepDowns: Int,
    /** Stop recording cleanly (only ever true at platform-emergency severity). */
    val stopRecording: Boolean,
)

object ThermalPolicy {

    fun mitigationsFor(state: ThermalState): ThermalMitigations = when (state) {
        ThermalState.NOMINAL -> ThermalMitigations(
            previewFpsCap = 30, detachPreview = false, pauseMap = false,
            pauseWeather = false, gpsIntervalMultiplier = 1,
            profileStepDowns = 0, stopRecording = false,
        )
        ThermalState.WARM -> ThermalMitigations(
            previewFpsCap = 15, detachPreview = false, pauseMap = false,
            pauseWeather = true, gpsIntervalMultiplier = 1,
            profileStepDowns = 0, stopRecording = false,
        )
        ThermalState.ELEVATED -> ThermalMitigations(
            previewFpsCap = 10, detachPreview = false, pauseMap = true,
            pauseWeather = true, gpsIntervalMultiplier = 2,
            profileStepDowns = 0, stopRecording = false,
        )
        ThermalState.HIGH -> ThermalMitigations(
            previewFpsCap = 5, detachPreview = true, pauseMap = true,
            pauseWeather = true, gpsIntervalMultiplier = 3,
            profileStepDowns = 1, stopRecording = false,
        )
        ThermalState.CRITICAL -> ThermalMitigations(
            previewFpsCap = 1, detachPreview = true, pauseMap = true,
            pauseWeather = true, gpsIntervalMultiplier = 5,
            profileStepDowns = 2, stopRecording = false,
        )
    }

    /**
     * Recording is only stopped when the platform reports EMERGENCY or worse
     * (the camera is about to be force-disabled anyway) — a clean stop with
     * finalized files beats a corrupt segment from a platform kill.
     */
    fun shouldStopForPlatformStatus(platformStatus: Int): Boolean =
        platformStatus >= android.os.PowerManager.THERMAL_STATUS_EMERGENCY
}
