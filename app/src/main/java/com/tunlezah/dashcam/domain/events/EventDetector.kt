package com.tunlezah.dashcam.domain.events

import com.tunlezah.dashcam.data.db.EventType
import com.tunlezah.dashcam.domain.settings.EventSensitivity
import kotlin.math.abs
import kotlin.math.sqrt

/** One accelerometer sample (device coordinates, m/s² including gravity). */
data class AccelSample(val timestampMs: Long, val x: Float, val y: Float, val z: Float)

/** A classified detection produced by [EventDetector]. */
data class DetectedEvent(
    val timestampMs: Long,
    val type: EventType,
    /** 0..1 — how confident the classifier is that this deserves protection. */
    val confidence: Float,
    val peakMagnitude: Float,
    /** True when the event should protect footage at the current sensitivity. */
    val shouldProtect: Boolean,
)

/**
 * Multi-stage, accelerometer-only impact detector (docs/event-detection.md).
 *
 * Designed for the Moto G04 baseline: NO gyroscope, NO magnetometer — every
 * feature is derived from the 3-axis accelerometer alone, and it must be
 * mount-orientation independent (the vertical axis is estimated from the
 * low-passed gravity vector, not assumed from device orientation).
 *
 * Stages:
 *  1. Gravity tracking (low-pass) → linear acceleration split into
 *     vertical / horizontal components relative to gravity.
 *  2. Trigger: |linear| exceeds the sensitivity threshold → open a
 *     classification window and keep sampling.
 *  3. Classification over the window (peak, duration above half-peak,
 *     vertical-vs-horizontal energy ratio, oscillation count):
 *       - IMPACT: strong horizontal component, sustained energy
 *       - POTHOLE: vertical, very short single spike with rebound
 *       - SPEED_BUMP: vertical, longer, moderate peak
 *       - DEVICE_MOVEMENT: gravity direction shifts persistently (phone
 *         knocked/removed from cradle)
 *  4. Separate sustained-deceleration track for HARD_BRAKING (no spike, but
 *     ~0.6 g+ horizontal held for ≥800 ms).
 *  5. Cooldown so one bad road doesn't spam events.
 *
 * External corroboration: [reportSpeed] lets the GPS pipeline feed Doppler
 * speed; a large speed drop right after a trigger raises confidence.
 *
 * This is an assistance mechanism, NOT a certified crash detector — the
 * accuracy limits are documented in docs/event-detection.md.
 */
class EventDetector(
    sensitivity: EventSensitivity = EventSensitivity.MEDIUM,
) {

    var sensitivity: EventSensitivity = sensitivity
        set(value) {
            field = value
            thresholds = Thresholds.forSensitivity(value)
        }

    private var thresholds = Thresholds.forSensitivity(sensitivity)

    // --- Stage 1: gravity tracking ---
    // Two trackers, deliberately different time constants:
    //  * SLOW gravity extracts linear acceleration. Its update rate collapses
    //    while large linear acceleration is present, so sustained braking is
    //    not absorbed into the gravity estimate (a single-alpha filter with
    //    τ≈1s eats a 1.5 s brake event — caught by unit test).
    //  * FAST gravity follows orientation quickly and is compared against a
    //    calm-time reference to detect the phone being knocked from its cradle.
    private var gx = 0f
    private var gy = 0f
    private var gz = 9.81f
    private var fgx = 0f
    private var fgy = 0f
    private var fgz = 9.81f
    private var gravityInitialized = false

    // Reference gravity for cradle-movement detection.
    private var refGx = 0f
    private var refGy = 0f
    private var refGz = 9.81f
    private var refSetMs = 0L

    // --- Stage 2/3: classification window ---
    private var windowOpen = false
    private var windowStartMs = 0L
    private val window = ArrayList<Feature>(128)

    // --- Stage 4: braking track ---
    private var brakingStartMs = 0L
    private var brakingActive = false

    // --- Stage 5: cooldown ---
    private var cooldownUntilMs = 0L

    // GPS corroboration.
    private var lastSpeedMps = Float.NaN
    private var lastSpeedTimeMs = 0L
    private var speedAtTriggerMps = Float.NaN

    private data class Feature(
        val tMs: Long,
        val magnitude: Float,
        val vertical: Float,
        val horizontal: Float,
    )

    fun reportSpeed(speedMps: Float, timestampMs: Long) {
        lastSpeedMps = speedMps
        lastSpeedTimeMs = timestampMs
    }

    /** Feed one sample; returns a classified event when one completes. */
    fun process(sample: AccelSample): DetectedEvent? {
        updateGravity(sample)
        if (!gravityInitialized) return null

        val gMag = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(0.1f)
        val ux = gx / gMag
        val uy = gy / gMag
        val uz = gz / gMag

        val lx = sample.x - gx
        val ly = sample.y - gy
        val lz = sample.z - gz
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        val vertical = lx * ux + ly * uy + lz * uz
        val hx = lx - vertical * ux
        val hy = ly - vertical * uy
        val hz = lz - vertical * uz
        val horizontal = sqrt(hx * hx + hy * hy + hz * hz)

        val now = sample.timestampMs

        // Sustained-braking track runs independently of the spike window.
        val brakingEvent = trackBraking(horizontal, magnitude, now)
        if (brakingEvent != null) return brakingEvent

        if (windowOpen) {
            window.add(Feature(now, magnitude, vertical, horizontal))
            if (now - windowStartMs >= WINDOW_MS) {
                return closeWindowAndClassify(now)
            }
            return null
        }

        if (now < cooldownUntilMs) return null

        if (magnitude >= thresholds.triggerMps2) {
            windowOpen = true
            windowStartMs = now
            window.clear()
            window.add(Feature(now, magnitude, vertical, horizontal))
            speedAtTriggerMps =
                if (now - lastSpeedTimeMs < SPEED_FRESH_MS) lastSpeedMps else Float.NaN
        }
        return null
    }

    private fun updateGravity(sample: AccelSample) {
        if (!gravityInitialized) {
            gx = sample.x; gy = sample.y; gz = sample.z
            fgx = gx; fgy = gy; fgz = gz
            refGx = gx; refGy = gy; refGz = gz
            refSetMs = sample.timestampMs
            gravityInitialized = true
            return
        }
        // Deviation from the slow gravity estimate: while it is large, real
        // linear acceleration is happening and the slow tracker nearly freezes.
        val dx = sample.x - gx
        val dy = sample.y - gy
        val dz = sample.z - gz
        val deviation = sqrt(dx * dx + dy * dy + dz * dz)
        val alpha = if (deviation > GRAVITY_GATE_MPS2) GRAVITY_ALPHA_GATED else GRAVITY_ALPHA
        gx += alpha * dx
        gy += alpha * dy
        gz += alpha * dz

        fgx += FAST_GRAVITY_ALPHA * (sample.x - fgx)
        fgy += FAST_GRAVITY_ALPHA * (sample.y - fgy)
        fgz += FAST_GRAVITY_ALPHA * (sample.z - fgz)

        // Refresh the reference orientation slowly while things are calm.
        if (sample.timestampMs - refSetMs > REF_REFRESH_MS && !windowOpen && deviation < GRAVITY_GATE_MPS2) {
            refGx = fgx; refGy = fgy; refGz = fgz
            refSetMs = sample.timestampMs
        }
    }

    private fun trackBraking(horizontal: Float, magnitude: Float, now: Long): DetectedEvent? {
        val braking = horizontal >= BRAKING_MPS2 && magnitude < thresholds.triggerMps2
        if (braking) {
            if (!brakingActive) {
                brakingActive = true
                brakingStartMs = now
            } else if (now - brakingStartMs >= BRAKING_HOLD_MS && now >= cooldownUntilMs) {
                brakingActive = false
                cooldownUntilMs = now + COOLDOWN_MS
                val speedDrop = speedDropSinceTrigger()
                val confidence = (0.55f + speedDrop * 0.05f).coerceIn(0.4f, 0.9f)
                return DetectedEvent(
                    timestampMs = now,
                    type = EventType.HARD_BRAKING,
                    confidence = confidence,
                    peakMagnitude = horizontal,
                    shouldProtect = confidence >= thresholds.protectConfidence,
                )
            }
        } else {
            brakingActive = false
        }
        return null
    }

    private fun closeWindowAndClassify(now: Long): DetectedEvent {
        windowOpen = false
        cooldownUntilMs = now + COOLDOWN_MS

        val peak = window.maxOf { it.magnitude }
        val peakFeature = window.first { it.magnitude == peak }
        val halfPeak = peak / 2f
        val aboveHalfMs = spanAbove(halfPeak)
        val verticalEnergy = window.sumOf { (it.vertical * it.vertical).toDouble() }
        val horizontalEnergy = window.sumOf { (it.horizontal * it.horizontal).toDouble() }
        val horizontalRatio =
            (horizontalEnergy / (verticalEnergy + horizontalEnergy + 1e-6)).toFloat()
        val oscillations = countZeroCrossings()
        val orientationShift = gravityShiftDegrees()

        val type: EventType
        var confidence: Float

        when {
            // Phone knocked / pulled from cradle: orientation permanently changed.
            orientationShift > CRADLE_SHIFT_DEGREES && peak < thresholds.severeMps2 -> {
                type = EventType.DEVICE_MOVEMENT
                confidence = 0.3f
            }
            // Real impacts put significant energy in the horizontal plane and
            // keep it there (crumple/secondary motion) — not a single spike.
            horizontalRatio >= IMPACT_HORIZONTAL_RATIO && aboveHalfMs >= IMPACT_MIN_DURATION_MS -> {
                type = EventType.IMPACT
                confidence = 0.5f +
                    0.2f * ((peak - thresholds.triggerMps2) / thresholds.triggerMps2).coerceIn(0f, 1f) +
                    0.1f * (aboveHalfMs / 300f).coerceAtMost(1f) +
                    0.05f * speedDropSinceTrigger().coerceAtMost(4f)
            }
            // Vertical, extremely short, oscillating: pothole / expansion joint.
            horizontalRatio < POTHOLE_HORIZONTAL_RATIO && aboveHalfMs < POTHOLE_MAX_DURATION_MS -> {
                type = EventType.POTHOLE
                confidence = 0.3f
            }
            // Vertical, longer and softer: speed bump.
            horizontalRatio < POTHOLE_HORIZONTAL_RATIO -> {
                type = EventType.SPEED_BUMP
                confidence = 0.3f
            }
            else -> {
                // Ambiguous mixed signature: treat as possible impact but with
                // reduced confidence; a severe peak overrides the ambiguity.
                type = EventType.IMPACT
                confidence = if (peak >= thresholds.severeMps2) 0.75f else 0.45f
            }
        }

        // A very large peak is protected regardless of classification doubts:
        // missing a real crash is far worse than protecting a huge pothole.
        if (peak >= thresholds.severeMps2 && type != EventType.DEVICE_MOVEMENT) {
            confidence = maxOf(confidence, 0.85f)
        }
        confidence = confidence.coerceIn(0f, 1f)

        val protect = when (type) {
            EventType.IMPACT, EventType.HARD_BRAKING -> confidence >= thresholds.protectConfidence
            else -> false
        }
        if (protect) cooldownUntilMs = now + PROTECTED_COOLDOWN_MS

        // Unused but informative for diagnostics/tests.
        @Suppress("UNUSED_EXPRESSION")
        oscillations

        return DetectedEvent(
            timestampMs = peakFeature.tMs,
            type = type,
            confidence = confidence,
            peakMagnitude = peak,
            shouldProtect = protect,
        )
    }

    private fun spanAbove(threshold: Float): Long {
        var first = -1L
        var last = -1L
        for (f in window) {
            if (f.magnitude >= threshold) {
                if (first < 0) first = f.tMs
                last = f.tMs
            }
        }
        return if (first < 0) 0 else (last - first).coerceAtLeast(SAMPLE_SPACING_MS)
    }

    private fun countZeroCrossings(): Int {
        var crossings = 0
        for (i in 1 until window.size) {
            if (window[i].vertical > 0 != window[i - 1].vertical > 0) crossings++
        }
        return crossings
    }

    private fun gravityShiftDegrees(): Float {
        val dot = fgx * refGx + fgy * refGy + fgz * refGz
        val m1 = sqrt(fgx * fgx + fgy * fgy + fgz * fgz)
        val m2 = sqrt(refGx * refGx + refGy * refGy + refGz * refGz)
        if (m1 < 0.1f || m2 < 0.1f) return 0f
        val cos = (dot / (m1 * m2)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }

    private fun speedDropSinceTrigger(): Float {
        if (speedAtTriggerMps.isNaN() || lastSpeedMps.isNaN()) return 0f
        return (speedAtTriggerMps - lastSpeedMps).coerceAtLeast(0f)
    }

    private data class Thresholds(
        /** Linear-acceleration magnitude that opens a classification window. */
        val triggerMps2: Float,
        /** Peak above which protection happens regardless of classification. */
        val severeMps2: Float,
        /** Minimum confidence for auto-protection. */
        val protectConfidence: Float,
    ) {
        companion object {
            fun forSensitivity(s: EventSensitivity) = when (s) {
                // ~1.6 g trigger / ~3.1 g severe
                EventSensitivity.HIGH -> Thresholds(16f, 30f, 0.5f)
                // ~2.2 g trigger / ~4.1 g severe (hardware dashcam "medium")
                EventSensitivity.MEDIUM -> Thresholds(22f, 40f, 0.6f)
                // ~3.1 g trigger / ~5.1 g severe (highway setting)
                EventSensitivity.LOW -> Thresholds(30f, 50f, 0.7f)
            }
        }
    }

    companion object {
        const val WINDOW_MS = 500L
        const val COOLDOWN_MS = 10_000L
        const val PROTECTED_COOLDOWN_MS = 30_000L
        const val GRAVITY_ALPHA = 0.02f
        const val GRAVITY_ALPHA_GATED = 0.002f
        const val GRAVITY_GATE_MPS2 = 3f
        const val FAST_GRAVITY_ALPHA = 0.05f
        const val REF_REFRESH_MS = 5_000L
        const val SPEED_FRESH_MS = 3_000L
        const val SAMPLE_SPACING_MS = 20L
        const val BRAKING_MPS2 = 7.5f
        const val BRAKING_HOLD_MS = 800L
        const val IMPACT_HORIZONTAL_RATIO = 0.45f
        const val IMPACT_MIN_DURATION_MS = 60L
        const val POTHOLE_HORIZONTAL_RATIO = 0.35f
        const val POTHOLE_MAX_DURATION_MS = 100L
        const val CRADLE_SHIFT_DEGREES = 25f
    }
}
