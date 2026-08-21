package com.tunlezah.dashcam.domain.thermal

import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * A source of raw thermal readings. Production uses [AndroidThermalSource];
 * tests and the in-app thermal simulation harness use [SimulatedThermalSource].
 */
interface ThermalSource {
    /** Emits raw readings; the engine classifies and applies hysteresis. */
    val readings: StateFlow<RawThermalReading>
    fun start()
    fun stop()
}

data class RawThermalReading(
    val headroom: Float = Float.NaN,
    val platformStatus: Int = -1,
    val batteryTempC: Float = Float.NaN,
    val timestampMs: Long = 0,
)

/**
 * Merges thermal signals into a hysteresis-stabilised [ThermalSnapshot] stream.
 *
 * Escalation is immediate (never delay reacting to heat); de-escalation
 * requires the underlying assessment to stay at the lower state for
 * [DEESCALATE_HOLD_MS] so quality doesn't oscillate at a threshold.
 */
class ThermalEngine(
    private val source: ThermalSource,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val _snapshot = MutableStateFlow(ThermalSnapshot.UNKNOWN)
    val snapshot: StateFlow<ThermalSnapshot> = _snapshot

    private var candidateState: ThermalState? = null
    private var candidateSinceMs: Long = 0

    fun start() {
        source.start()
        scope.launch {
            source.readings.collect { raw -> onReading(raw) }
        }
    }

    fun stop() = source.stop()

    internal fun onReading(raw: RawThermalReading) {
        if (raw.timestampMs == 0L) return
        val (assessed, signalSource) =
            ThermalClassifier.classify(raw.headroom, raw.platformStatus, raw.batteryTempC)
        val current = _snapshot.value.state
        val now = if (raw.timestampMs > 0) raw.timestampMs else clock()

        val next: ThermalState = when {
            assessed.severity > current.severity -> {
                candidateState = null
                assessed // escalate immediately
            }
            assessed.severity < current.severity -> {
                if (candidateState == null || assessed.severity > (candidateState?.severity ?: -1)) {
                    // Track the highest of the recent lower readings as the
                    // de-escalation candidate.
                    candidateState = assessed
                    candidateSinceMs = now
                }
                if (now - candidateSinceMs >= DEESCALATE_HOLD_MS) {
                    val settled = candidateState ?: assessed
                    candidateState = null
                    settled
                } else current
            }
            else -> {
                candidateState = null
                current
            }
        }

        if (next != current) {
            diagnostics.log(
                "Thermal",
                "state $current -> $next (source=$signalSource headroom=${raw.headroom} " +
                    "status=${raw.platformStatus} battC=${raw.batteryTempC})",
            )
        }
        _snapshot.value = ThermalSnapshot(
            state = next,
            headroom = raw.headroom,
            platformStatus = raw.platformStatus,
            batteryTempC = raw.batteryTempC,
            source = signalSource,
            timestampMs = now,
        )
    }

    companion object {
        const val DEESCALATE_HOLD_MS = 60_000L
    }
}

/**
 * Test/simulation source: inject any sequence of thermal conditions. Used by
 * the unit tests and by the in-app "thermal simulation" developer tool so the
 * whole mitigation ladder can be exercised without physical heat
 * (docs/thermal-management.md — simulated vs measured is always labelled).
 */
class SimulatedThermalSource : ThermalSource {
    override val readings = MutableStateFlow(RawThermalReading())
    override fun start() = Unit
    override fun stop() = Unit

    fun inject(reading: RawThermalReading) {
        readings.value = reading
    }

    fun injectState(state: ThermalState, timestampMs: Long) {
        val headroom = when (state) {
            ThermalState.NOMINAL -> 0.5f
            ThermalState.WARM -> 0.7f
            ThermalState.ELEVATED -> 0.85f
            ThermalState.HIGH -> 0.95f
            ThermalState.CRITICAL -> 1.05f
        }
        inject(RawThermalReading(headroom = headroom, timestampMs = timestampMs))
    }
}

/** Convenience delay-based poller used by the Android source. */
internal suspend fun pollLoop(intervalMs: Long, body: suspend () -> Unit) {
    while (true) {
        body()
        delay(intervalMs)
    }
}
