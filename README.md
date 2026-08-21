# DashCam

A **privacy-first, offline-first Android dashcam** built for reliability on real
hardware — from the entry-level **Motorola Moto G04 (Android 14)** up to the
**Motorola Edge 60 Fusion** and Android 16.

The design priority, in order: **recording reliability → thermal safety →
storage safety → everything else.** Optional features (offline map, weather,
overlays) are architecturally incapable of stopping the recording pipeline.

## Highlights

- **Gapless loop recording** — a persistent Camera2 + MediaCodec pipeline with
  keyframe-aligned segment rotation: no frames lost at segment boundaries
  (1 / 3 / 5 / 10-minute segments, 3 min default).
- **Crash-durable files** — segments are fragmented MP4 (valid to the last
  ~1 s fragment even through power loss), with automatic startup recovery and
  quarantine of unreadable files. Exports remux to classic MP4 for universal
  playback.
- **Event protection** — accelerometer impact detection (works without a
  gyroscope — the Moto G04 has none) with pothole/speed-bump/hard-braking
  classification, configurable **30 s pre-event / 60 s post-event** protection,
  and a one-tap manual protect button.
- **Thermal management first-class** — thermal headroom forecasting + status +
  battery-temperature fallback drive a graduated mitigation ladder (shed
  optional features → reduce preview → reduce bitrate live → step the profile
  down at a segment boundary). Includes a simulation harness so the whole
  ladder is unit-tested without physical heat.
- **Automatic device profiling** — resolution/codec/bitrate chosen from what
  the actual camera, encoders, RAM and sensors report at runtime, never from
  the model name. Auto = *best sustainable dashcam profile*, not maximum specs.
- **No SIM, no internet, no cloud** — GNSS-only GPS speed/track (GPX export),
  optional fully-offline OpenStreetMap panel (PMTiles), optional weather that
  silently disappears when offline. Nothing is ever uploaded.
- **Storage discipline** — 5 GB loop by default (configurable 2–30 GB+),
  preemptive oldest-first eviction, a hard device-space reserve, and a budget
  with warnings for protected footage.
- **Modern UI** — Jetpack Compose, portrait-mount-first layout, Light / Dark /
  System / OLED true-black themes, live diagnostics with exportable reports.

## Requirements

- Android 14 (API 34) or newer. Tested targets: Moto G04 class (hard minimum)
  and Edge 60 Fusion class.
- No Google Play services required.

## Building

```bash
./gradlew :app:assembleDebug      # sideloadable debug APK
./gradlew :app:testDebugUnitTest  # 79 JVM unit tests
./gradlew :app:lintDebug
```

See [docs/build.md](docs/build.md) for toolchain details, CI, and how to supply
release-signing credentials safely (no keys ever live in this repo).

## Documentation

| Document | Contents |
|---|---|
| [docs/architecture.md](docs/architecture.md) | Design decisions, module map, pipeline diagram |
| [docs/research.md](docs/research.md) | Index of the research that drove the design |
| [docs/feature-research.md](docs/feature-research.md) | Dashcam feature landscape + scope decisions |
| [docs/device-profiles.md](docs/device-profiles.md) | Capability profiling & Auto quality algorithm |
| [docs/thermal-management.md](docs/thermal-management.md) | Thermal engine, mitigation ladder, simulation |
| [docs/storage.md](docs/storage.md) | Loop management, file safety, recovery |
| [docs/event-detection.md](docs/event-detection.md) | Impact detection design & honest limitations |
| [docs/offline-maps.md](docs/offline-maps.md) | Offline map setup (Australian PMTiles extracts) |
| [docs/privacy.md](docs/privacy.md) | Permissions & data-handling guarantees |
| [docs/testing.md](docs/testing.md) | Test strategy & manual test matrix |
| [docs/benchmarking.md](docs/benchmarking.md) | Benchmark methodology & profile justification |
| [docs/troubleshooting.md](docs/troubleshooting.md) | OEM quirks, screen-off recording, heat |

## Honest limitations

- **No headless auto-start.** Android 14+ forbids starting a camera service
  from the background; recording starts when you open the app (automatic by
  default) or from the notification. No app can truthfully promise more.
- **Screen-off recording is OEM-dependent.** The app detects a stalled camera
  and falls back with an explanation rather than failing silently.
- **No parking mode.** A phone on a windshield cannot safely do 24/7
  surveillance (heat, battery chemistry, platform limits) — documented instead
  of promised.
- Thermal behaviour on the exact target devices has been **simulated and
  reasoned, not measured** — the diagnostics screen exists to collect real
  measurements once the APK runs on hardware. No fabricated benchmarks.

## License & attribution

Application code © the repository owner. Map data, when used, is
© [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors (ODbL).
Weather, when enabled, from [Open-Meteo](https://open-meteo.com/).
