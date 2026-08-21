package com.tunlezah.dashcam.domain.thermal

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Production thermal source combining:
 *  - a thermal status listener (event-driven, API 29+)
 *  - headroom polling every [HEADROOM_POLL_MS] (the API is rate-limited to
 *    ~1/10 s; polling faster returns NaN — see research §4)
 *  - the headroom *listener* on API 36+ (no polling cost)
 *  - battery temperature from the sticky ACTION_BATTERY_CHANGED broadcast
 *
 * Staleness detection per ADPF guidance: if headroom reads ≥ 0.85 while the
 * platform status stays NONE for several samples, the headroom value is
 * considered garbage and ignored from then on.
 */
class AndroidThermalSource(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
) : ThermalSource {

    override val readings = MutableStateFlow(RawThermalReading())

    private val powerManager get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private var pollJob: Job? = null
    private var lastStatus: Int = -1
    private var headroomSupported = true
    private var suspiciousHeadroomSamples = 0

    private val statusListener = PowerManager.OnThermalStatusChangedListener { status ->
        lastStatus = status
        publish(headroom = Float.NaN)
    }

    // API 36+ push-based headroom updates (no polling cost). Kept alongside the
    // poll loop: the listener only fires on changes, the poll provides liveness.
    private var headroomListener: PowerManager.OnThermalHeadroomChangedListener? = null

    override fun start() {
        runCatching { powerManager.addThermalStatusListener(statusListener) }
            .onFailure { diagnostics.log("Thermal", "status listener unavailable: ${it.message}") }
        lastStatus = runCatching { powerManager.currentThermalStatus }.getOrDefault(-1)

        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val listener = PowerManager.OnThermalHeadroomChangedListener { headroom, _, _, _ ->
                    publish(headroom)
                }
                powerManager.addThermalHeadroomListener(context.mainExecutor, listener)
                headroomListener = listener
                diagnostics.log("Thermal", "API 36 headroom listener registered")
            }.onFailure { diagnostics.log("Thermal", "headroom listener unavailable: ${it.message}") }
        }

        pollJob = scope.launch {
            pollLoop(HEADROOM_POLL_MS) {
                val headroom = readHeadroom()
                publish(headroom)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        runCatching { powerManager.removeThermalStatusListener(statusListener) }
        if (Build.VERSION.SDK_INT >= 36) {
            headroomListener?.let { runCatching { powerManager.removeThermalHeadroomListener(it) } }
            headroomListener = null
        }
    }

    private fun readHeadroom(): Float {
        if (!headroomSupported) return Float.NaN
        val value = runCatching { powerManager.getThermalHeadroom(FORECAST_SECONDS) }
            .getOrDefault(Float.NaN)
        if (value.isNaN()) {
            // First NaN could be rate limiting; persistent NaN means unsupported.
            suspiciousHeadroomSamples++
            if (suspiciousHeadroomSamples >= 3) {
                headroomSupported = false
                diagnostics.log("Thermal", "headroom API unsupported on this device; using status/battery")
            }
            return Float.NaN
        }
        // Stale-value heuristic: high headroom but platform says NONE repeatedly.
        if (value >= 0.85f && lastStatus == PowerManager.THERMAL_STATUS_NONE) {
            suspiciousHeadroomSamples++
            if (suspiciousHeadroomSamples >= 5) {
                headroomSupported = false
                diagnostics.log("Thermal", "headroom API returning stale values; disabled")
                return Float.NaN
            }
        } else {
            suspiciousHeadroomSamples = 0
        }
        return value
    }

    private fun batteryTempC(): Float {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return if (tenths == Int.MIN_VALUE) Float.NaN else tenths / 10f
    }

    private fun publish(headroom: Float) {
        readings.value = RawThermalReading(
            headroom = headroom,
            platformStatus = lastStatus,
            batteryTempC = batteryTempC(),
            timestampMs = System.currentTimeMillis(),
        )
    }

    companion object {
        /** Poll interval respects the platform's ~10 s headroom rate limit. */
        const val HEADROOM_POLL_MS = 15_000L
        const val FORECAST_SECONDS = 30

        @Suppress("unused")
        val HEADROOM_LISTENER_API = if (Build.VERSION.SDK_INT >= 36) 36 else -1
    }
}
