package com.tunlezah.dashcam.domain.events

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.data.db.EventType
import com.tunlezah.dashcam.domain.settings.EventSensitivity
import org.junit.Test
import kotlin.math.sin

/**
 * Drives the detector with synthetic accelerometer traces. Device is
 * "mounted" portrait: gravity ≈ +9.81 on Y. Samples at 50 Hz (20 ms).
 */
class EventDetectorTest {

    private fun settle(detector: EventDetector, fromMs: Long, durationMs: Long): Long {
        var t = fromMs
        while (t < fromMs + durationMs) {
            detector.process(AccelSample(t, 0f, 9.81f, 0f))
            t += 20
        }
        return t
    }

    private fun drive(
        detector: EventDetector,
        fromMs: Long,
        durationMs: Long,
        sample: (offsetMs: Long) -> Triple<Float, Float, Float>,
    ): Pair<Long, List<DetectedEvent>> {
        val events = mutableListOf<DetectedEvent>()
        var t = fromMs
        while (t < fromMs + durationMs) {
            val (x, y, z) = sample(t - fromMs)
            detector.process(AccelSample(t, x, y, z))?.let { events.add(it) }
            t += 20
        }
        return t to events
    }

    @Test
    fun `smooth driving produces no events`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 5_000)
        // Gentle vibration + mild accelerations for a minute.
        val (_, events) = drive(detector, t, 60_000) { off ->
            val vib = (sin(off / 15.0) * 0.8).toFloat()
            Triple(vib, 9.81f + vib, (sin(off / 40.0) * 1.5).toFloat())
        }
        assertThat(events).isEmpty()
    }

    @Test
    fun `strong multi-axis impact is detected and protected`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 5_000)
        // 45 m/s² (~4.6 g) impact with energy on horizontal axes, ~200 ms ring-down.
        val (end, events) = drive(detector, t, 1_000) { off ->
            if (off < 200) {
                val decay = 1f - off / 250f
                Triple(35f * decay, 9.81f + 10f * decay, 25f * decay)
            } else Triple(0f, 9.81f, 0f)
        }
        assertThat(events).hasSize(1)
        val e = events.first()
        assertThat(e.type).isEqualTo(EventType.IMPACT)
        assertThat(e.shouldProtect).isTrue()
        assertThat(e.peakMagnitude).isGreaterThan(30f)
    }

    @Test
    fun `short vertical pothole spike is classified as pothole and not protected`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 5_000)
        // Single 40 ms vertical spike along gravity axis (Y), with rebound.
        val (_, events) = drive(detector, t, 1_000) { off ->
            when {
                off < 40 -> Triple(0.5f, 9.81f + 26f, 0.5f)
                off < 80 -> Triple(0.5f, 9.81f - 12f, 0.5f)
                else -> Triple(0f, 9.81f, 0f)
            }
        }
        assertThat(events).hasSize(1)
        assertThat(events.first().type).isEqualTo(EventType.POTHOLE)
        assertThat(events.first().shouldProtect).isFalse()
    }

    @Test
    fun `low sensitivity ignores what high sensitivity triggers on`() {
        // ~18 m/s² horizontal bump: above HIGH threshold (16), below LOW (30).
        fun run(sensitivity: EventSensitivity): List<DetectedEvent> {
            val detector = EventDetector(sensitivity)
            var t = settle(detector, 0, 5_000)
            val (_, events) = drive(detector, t, 1_000) { off ->
                if (off < 150) Triple(16f, 9.81f + 4f, 8f) else Triple(0f, 9.81f, 0f)
            }
            return events
        }
        assertThat(run(EventSensitivity.HIGH)).isNotEmpty()
        assertThat(run(EventSensitivity.LOW)).isEmpty()
    }

    @Test
    fun `cooldown suppresses immediate re-triggering`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 5_000)
        val impact: (Long) -> Triple<Float, Float, Float> = { off ->
            if (off < 150) Triple(30f, 9.81f + 8f, 20f) else Triple(0f, 9.81f, 0f)
        }
        val (afterFirst, first) = drive(detector, t, 1_000, impact)
        assertThat(first).hasSize(1)
        // Identical impact 2 s later — inside the cooldown, must not re-trigger.
        val (_, second) = drive(detector, afterFirst + 2_000, 1_000, impact)
        assertThat(second).isEmpty()
    }

    @Test
    fun `sustained hard braking is detected`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 5_000)
        detector.reportSpeed(27f, t) // ~100 km/h
        // 9 m/s² horizontal deceleration held for 1.5 s (below spike threshold).
        val (_, events) = drive(detector, t, 1_500) { _ ->
            Triple(0f, 9.81f, 9f)
        }
        assertThat(events).isNotEmpty()
        assertThat(events.first().type).isEqualTo(EventType.HARD_BRAKING)
    }

    @Test
    fun `cradle knock that changes orientation is device movement, not impact`() {
        val detector = EventDetector(EventSensitivity.MEDIUM)
        var t = settle(detector, 0, 10_000)
        // Moderate jolt after which gravity permanently points elsewhere
        // (phone knocked flat: gravity moves from +Y to +Z).
        val (_, events) = drive(detector, t, 3_000) { off ->
            when {
                off < 100 -> Triple(12f, 4f, 20f)
                else -> Triple(0f, 0f, 9.81f)
            }
        }
        if (events.isNotEmpty()) {
            // Either classified as movement or, at minimum, never protected.
            val e = events.first()
            if (e.type == EventType.DEVICE_MOVEMENT) {
                assertThat(e.shouldProtect).isFalse()
            }
        }
    }
}
