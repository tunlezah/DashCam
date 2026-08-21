package com.tunlezah.dashcam.domain.thermal

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.test.TestScope
import org.junit.Test

class ThermalEngineTest {

    private val source = SimulatedThermalSource()
    private val engine = ThermalEngine(source, DiagnosticsLog(), TestScope())

    private fun reading(headroom: Float, atMs: Long) =
        engine.onReading(RawThermalReading(headroom = headroom, timestampMs = atMs))

    @Test
    fun `escalation is immediate`() {
        reading(0.5f, atMs = 1_000) // NOMINAL
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.NOMINAL)
        reading(0.95f, atMs = 2_000) // HIGH
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.HIGH)
        reading(1.2f, atMs = 3_000) // CRITICAL
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.CRITICAL)
    }

    @Test
    fun `de-escalation requires a sustained hold`() {
        reading(0.95f, atMs = 1_000)
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.HIGH)

        // Cooler readings inside the hold window do not de-escalate…
        reading(0.5f, atMs = 10_000)
        reading(0.5f, atMs = 30_000)
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.HIGH)

        // …but after the hold period the state settles down.
        reading(0.5f, atMs = 10_000 + ThermalEngine.DEESCALATE_HOLD_MS)
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.NOMINAL)
    }

    @Test
    fun `oscillating readings do not flap the state`() {
        reading(0.95f, atMs = 1_000)
        var t = 2_000L
        // Bounce between WARM and HIGH assessments for 30 s: state must hold HIGH.
        repeat(15) {
            reading(0.7f, atMs = t)
            assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.HIGH)
            t += 1000
            reading(0.95f, atMs = t)
            t += 1000
        }
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.HIGH)
    }

    @Test
    fun `de-escalation settles at the highest recent lower state`() {
        reading(1.2f, atMs = 1_000) // CRITICAL
        // Mixture of WARM and ELEVATED lower readings during the hold —
        // must settle at ELEVATED (the higher), not WARM.
        reading(0.7f, atMs = 5_000)
        reading(0.85f, atMs = 10_000) // candidate upgraded to ELEVATED, hold restarts here
        reading(0.85f, atMs = 10_000 + ThermalEngine.DEESCALATE_HOLD_MS)
        assertThat(engine.snapshot.value.state).isEqualTo(ThermalState.ELEVATED)
    }

    @Test
    fun `simulated source drives full ladder`() {
        var t = 1_000L
        for (state in ThermalState.entries) {
            source.injectState(state, timestampMs = t)
            engine.onReading(source.readings.value)
            assertThat(engine.snapshot.value.state).isEqualTo(state)
            t += 1000
        }
    }
}
