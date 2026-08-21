package com.tunlezah.dashcam.domain.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class PowerState(
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val isPluggedIn: Boolean = false,
    /** BatteryManager.BATTERY_PLUGGED_* or 0. */
    val plugType: Int = 0,
    val batteryTempC: Float = Float.NaN,
    val voltageMv: Int = -1,
)

enum class PowerEvent { PLUGGED_IN, UNPLUGGED }

/**
 * Battery/charging observer. Uses the sticky ACTION_BATTERY_CHANGED broadcast
 * plus context-registered POWER_CONNECTED/DISCONNECTED receivers (Android does
 * not deliver these to manifest receivers — docs/research §9), so plug events
 * are only observable while the app or its recording service is alive; the
 * configurable plug/unplug behaviours are documented with that limitation.
 */
class PowerMonitor(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    private val _state = MutableStateFlow(PowerState())
    val state: StateFlow<PowerState> = _state

    private val _events = MutableSharedFlow<PowerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<PowerEvent> = _events

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> _state.value = parse(intent)
                Intent.ACTION_POWER_CONNECTED -> {
                    refresh()
                    diagnostics.log("Power", "power connected")
                    _events.tryEmit(PowerEvent.PLUGGED_IN)
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    refresh()
                    diagnostics.log("Power", "power disconnected")
                    _events.tryEmit(PowerEvent.UNPLUGGED)
                }
            }
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun refresh() {
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (sticky != null) _state.value = parse(sticky)
    }

    private fun parse(intent: Intent): PowerState {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return PowerState(
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            isPluggedIn = plugged != 0,
            plugType = plugged,
            batteryTempC = if (temp == Int.MIN_VALUE) Float.NaN else temp / 10f,
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1),
        )
    }
}
