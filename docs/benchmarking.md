# Benchmarking

## Status honesty

No physical Moto G04 or Edge 60 Fusion was available during development, so
this document defines the **methodology and instrumentation** and records only
values that are arithmetic (bitrate → storage) or platform-documented. There
are **no fabricated CPU, thermal or battery numbers** here. The app itself is
the benchmark harness: the diagnostics screen exposes every counter needed and
exports reports, so the first hours on real hardware produce the real table.

## Instrumentation built into the app

| Metric | Source |
|---|---|
| Frames rendered / dropped-preview counters | `GlRenderPipeline.framesRendered`, `previewFramesSkipped` |
| Encoder output liveness | `VideoEncoderCore.encodedFrameCount` + 5 s watchdog |
| Segments written, recovery counts | `SegmentSink`, `StartupRecovery` |
| Thermal headroom / status / battery temperature over time | diagnostics decision log (timestamped) |
| Profile changes & mitigation actions | decision log (every transition, with cause) |
| Storage consumption per hour | segment index (sizes + wall times) |
| Battery | `PowerMonitor` (%, charging, `BATTERY_PROPERTY_CURRENT_NOW` shows negative-while-charging on weak 12 V adapters) |

External measurements to pair with the on-device log:
`adb shell dumpsys batterystats`, `dumpsys thermalservice`,
`/sys/class/thermal/thermal_zone*` where readable, and a plug-through USB
power meter for true draw.

## Benchmark matrix (to execute on hardware; brief §45)

Each profile runs ≥ 60 min, windshield-mounted, engine running, and records:
avg/max CPU (top), thermal headroom curve, thermal status events, battery Δ%,
frames rendered vs expected (fps × time), encoder failures, file GB/h, and
whether the mitigation ladder engaged.

| Profile | Configuration |
|---|---|
| A | 1080p30 H.264, map off (G04 Auto) |
| B | 1080p30 HEVC, map off (Edge manual) |
| C | 720p30 H.264 (floor profile) |
| D | 1440p/4K30 where supported (Edge manual) |
| E | A + map enabled |
| F | A + GPS + all overlays + map |
| G | A, screen on full brightness |
| H | A, screen off |
| I | A, charging (15 W G04 / 68 W Edge — expect the 68 W case to be the thermal worst case) |
| J | A, battery only |

## Expected outcomes and the decisions they gate (stated before measurement)

- **A vs C on the G04**: if A shows sustained headroom < 0.65 or repeated WARM+
  in ≤ 25 °C ambient, the Auto bitrate for CONSTRAINED drops from 10 → 8 Mbps
  (one constant in `ProfileSelector`).
- **D on the Edge**: only if 4K30 sustains 60 min in warm conditions without
  reaching HIGH would Auto ever be allowed above 1080p on CAPABLE — the second-
  pass review (docs/architecture review §59) currently keeps Auto at 1080p on
  the evidence available.
- **G vs H**: quantifies the screen's share of heat → informs the default
  keep-screen-on recommendation in onboarding docs.
- **E/F vs A**: if the map costs > ~15 % CPU or measurably worsens headroom on
  the G04, the CONSTRAINED default (map off) is confirmed and documented with
  numbers.
- **I vs J**: quantifies charging heat; may motivate a "slow-charge while
  recording" user recommendation (the app cannot control charge rate).

## Storage arithmetic (not measurement — bitrate math)

| Profile | GB/hour | Hours in 5 GB loop |
|---|---|---|
| 720p30 H.264 5 Mbps | 2.25 | 2.2 |
| 1080p30 H.264 10 Mbps | 4.5 | 1.1 |
| 1080p30 H.264 14 Mbps | 6.3 | 0.8 |
| 1080p30 HEVC 9 Mbps | 4.1 | 1.2 |
| 1440p30 H.264 24 Mbps | 10.8 | 0.46 |
| 4K30 H.264 45 Mbps | 20.3 | 0.25 |

(+2–4 % container overhead; AAC audio adds 43 MB/h when enabled.)

This table justifies the 5 GB default (≈1 h of Auto loop — commute coverage)
and shows why 4K needs a 20–30 GB allocation to be useful.

## Gap measurement protocol (segment continuity)

Film a running stopwatch (another phone) across ≥ 3 segment boundaries; step
through the last/first frames of adjacent files. Pass: no missing stopwatch
frame at any boundary (the keyframe-aligned design should show the next
segment's first frame exactly one frame-time after the previous segment's
last). This validates the central architecture claim on real hardware.
