# Event / Accident Detection

## Constraints that shaped the design

- The **Moto G04 has no gyroscope and no magnetometer** (research-CONFIRMED by
  two sources). Everything must derive from the 3-axis accelerometer.
- The phone's mount orientation is arbitrary — thresholds must be
  orientation-independent.
- The G04's CPU budget while encoding 1080p30 leaves no sensible room for
  continuous camera ML (see "Why no ML" below).

## Pipeline (`EventDetector`, ~50 Hz samples on a dedicated thread)

1. **Gravity separation** — two low-pass trackers:
   - *Slow gravity* extracts linear acceleration. Its update rate collapses
     (α 0.02 → 0.002) whenever deviation exceeds 3 m/s², so sustained braking
     is not absorbed into the gravity estimate. (A single-alpha filter with
     τ≈1 s literally eats a 1.5 s brake event — caught by a unit test during
     development and fixed.)
   - *Fast gravity* (α 0.05) tracks orientation for cradle-knock detection
     against a calm-time reference.
   Linear acceleration is split into vertical/horizontal components **relative
   to gravity**, making all thresholds mount-orientation independent.
2. **Trigger** — |linear| above the sensitivity threshold opens a 500 ms
   classification window.
3. **Classification** over the window (peak, time-above-half-peak,
   horizontal-vs-vertical energy ratio, gravity-direction shift):

   | Signature | Classified as | Protects? |
   |---|---|---|
   | Strong horizontal energy, sustained ≥ 60 ms | IMPACT | yes (confidence-gated) |
   | Vertical, single ≤ 100 ms spike with rebound | POTHOLE | no |
   | Vertical, longer/softer | SPEED_BUMP | no |
   | Orientation permanently shifted > 25°, sub-severe peak | DEVICE_MOVEMENT (cradle knock) | no |
   | Anything with a **severe** peak | IMPACT (confidence ≥ 0.85) | yes — missing a real crash is worse than protecting a huge pothole |

4. **Hard braking** — an independent track: ≥ 7.5 m/s² (~0.77 g) horizontal
   held ≥ 800 ms without a spike → HARD_BRAKING (moderate confidence).
5. **Corroboration** — GPS Doppler speed feeds the detector; a real speed drop
   right after a trigger raises confidence.
6. **Cooldown** — 10 s after any evaluation, 30 s after a protection, so a bad
   road cannot spam events.

## Sensitivity (hardware-dashcam convention: Low / Medium / High)

| Level | Trigger | Severe | Protect confidence |
|---|---|---|---|
| HIGH (city) | 16 m/s² (~1.6 g) | 30 | ≥ 0.50 |
| MEDIUM (default) | 22 m/s² (~2.2 g) | 40 | ≥ 0.60 |
| LOW (highway) | 30 m/s² (~3.1 g) | 50 | ≥ 0.70 |

## Protection mechanics (`EventProtector`)

The loop is always recording, so pre-event footage already exists on disk —
protection is *retroactive marking*, never reconstruction:

1. The event row is journalled **first** (crash mid-protection is re-driven at
   startup: `recoverPendingEvents`).
2. Segments overlapping `[t − pre, t]` are protected immediately — including
   the in-progress segment (the index query treats a null end time as
   ongoing), which also covers an impact at a segment boundary (unit-tested).
3. After `post` seconds (+2 s grace so the then-current segment is included),
   the post-window is protected and the event completes.

Defaults 30 s pre / 60 s post; options 10–60 s / 30–120 s, clamped by
`SettingsValidator`. The manual Protect button follows the identical path with
confidence 1.0.

## Why no ML

Camera-based collision ML on the G04 would contend with the encoder for the
2×A75 cores and the single-core Mali GPU, directly heating the device the
thermal engine is trying to cool, for marginal benefit over the inertial
signature at dashcam mounting positions. Rejected per the brief's thermal
budget rule; revisitable on CAPABLE-tier devices as a user-approved extra.

## Honest limitations

- This is an **assistance mechanism, not a certified crash detector**. It will
  miss some genuine events (especially low-speed scrapes with weak inertial
  signatures) and will occasionally protect non-events (severe potholes).
- Without a gyroscope, rotation-dominant events (spinouts without strong
  linear acceleration) are under-detected on the G04.
- Thresholds were calibrated against physics (g-levels from crash/pothole
  literature and hardware-dashcam conventions) and synthetic traces — not yet
  against real-vehicle recordings. The diagnostics log records every
  evaluation (type, confidence, peak) to support field recalibration.
- The manual Protect button exists precisely because no detector is complete.
