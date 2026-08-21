# Thermal Management

The single most important non-recording subsystem (brief §23). A phone in a
windshield cradle faces a triple threat: direct sun (cabin surfaces reach
65–80 °C), charging heat, and continuous ISP+encode load. Dedicated dashcams
solve this with supercapacitors and 75 °C auto-shutdown; a phone app must solve
it with **restraint and early, graduated mitigation**.

## What generates heat in this app (and what was done about it)

| Source | Mitigation built in |
|---|---|
| Video encoding | Hardware encoder only; modest Auto bitrates; live bitrate reduction under load |
| ISP / camera | 1080p30 Auto cap; single camera output stream (GL fan-out, no duplicate streams) |
| Display | Preview FPS capping, full preview detach, screen-off recording support, OLED true-black theme (saves power on the Edge's pOLED; on the G04's LCD it is aesthetic only — the backlight doesn't care) |
| GPS | 1 Hz baseline, interval multiplied under thermal load |
| Overlay compositing | One small Canvas render + texture upload per second, one blended quad per frame |
| Map rendering | OFF by default; FPS-capped; **paused** at ELEVATED |
| Weather | Cached 30 min; **suspended** at WARM |
| Charging | Documented guidance (avoid fast-charging in direct sun); battery temperature is monitored as a thermal signal |
| ML inference | Not used — rejected for exactly this reason (docs/event-detection.md) |

## Signal sources (best available wins, most severe wins)

1. **`PowerManager.getThermalHeadroom(30)`** — forecast-based; lets the app act
   *before* throttling. Polled every 15 s (the API rate-limits at ~10 s), plus
   the **API 36 headroom listener** where available (no polling cost).
   Budget-device reality (ADPF-documented): headroom may be NaN or frozen —
   the source disables itself after 3 NaN samples or 5 stale samples
   (headroom ≥ 0.85 while status reads NONE).
2. **`addThermalStatusListener`** — reactive but broadly supported.
3. **Battery temperature** (`ACTION_BATTERY_CHANGED`) — last resort; ladder
   calibrated to Li-ion comfort (< 38 °C nominal … ≥ 47 °C critical).

`ThermalClassifier.classify` merges all available signals and **the most
severe assessment wins** — an optimistic stale source can never mask a
pessimistic one (unit-tested).

## State machine with hysteresis

Five states: NOMINAL → WARM → ELEVATED → HIGH → CRITICAL.
**Escalation is immediate; de-escalation requires the lower assessment to hold
for 60 s** and settles at the *highest* of the recent lower readings — so
quality never oscillates at a threshold (unit-tested, including flapping
inputs).

## The mitigation ladder (`ThermalPolicy`)

Ordering principle: **shed optional work first, degrade video quality only
when necessary, stop recording only to pre-empt a platform emergency.**

| State | Preview | Map | Weather | GPS interval | Video profile |
|---|---|---|---|---|---|
| NOMINAL | 30 fps | on | on | 1× | recommended |
| WARM | 15 fps | on | **paused** | 1× | unchanged |
| ELEVATED | 10 fps | **paused** | paused | 2× | unchanged |
| HIGH | **detached** | paused | paused | 3× | **−1 step** (bitrate −30 %, live) |
| CRITICAL | detached | paused | paused | 5× | **−2 steps** (720p queued for the next segment boundary) |

Recording is stopped **only** when the platform reports
`THERMAL_STATUS_EMERGENCY` or worse — at that level the OS may force-disable
the camera anyway, and a clean stop with finalized files beats a killed process
with a torn segment. The user is told exactly why via the notification and
main screen.

Profile step-downs apply the `ProfileSelector.stepDown` ladder: bitrate cuts
happen **live** (`PARAMETER_KEY_VIDEO_BITRATE`, zero interruption); geometry
changes (720p) wait for the next keyframe-aligned segment boundary, so no
footage is lost to the mitigation itself. Recovery to full quality follows the
same boundary rule after the 60 s de-escalation hold.

Every transition and every mitigation is written to the diagnostics decision
log.

## Simulation harness (testing without hardware)

`SimulatedThermalSource` injects any headroom/status/battery sequence into the
real engine — the unit tests drive the full ladder (escalation, hold-time
de-escalation, flapping, settle-at-highest) with virtual timestamps. The
policy table itself is asserted monotonic (mitigations only grow with
severity) and "optional features shed before video quality" is a test, not a
comment.

## Honesty statement (brief §46)

- **Measured**: nothing yet — no physical target devices were available during
  development. No temperature numbers in this repository are measurements.
- **Simulated**: the entire mitigation ladder, via `SimulatedThermalSource`.
- **Documented by manufacturer / platform**: thermal status semantics, headroom
  rate limits, headroom NaN behaviour (Android ADPF docs); BlackVue's 75 °C
  shutdown convention (industry reference point).
- **Theoretically expected**: the T606's low heat output (12 nm, low clocks —
  reviewer-reported), the Dimensity 7300's 4 nm efficiency.

The diagnostics screen reports live headroom/status/battery-temperature and
the decision log specifically so that real-device testing (docs/testing.md,
docs/benchmarking.md) can validate or recalibrate the thresholds. The
classifier thresholds live in one file (`ThermalClassifier`) to make
recalibration a one-line-per-threshold change.
