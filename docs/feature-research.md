# Feature Research & Scope Decisions

Derived from `docs/research/dashcam-landscape.md` (Android apps + dedicated hardware
dashcams surveyed 2026-08-21). Every feature found in the landscape is categorised
against this project's priorities: recording reliability first, thermal safety
second, everything else after.

Categories: **Must have** (core spec, implemented) · **Should have** (implemented —
low risk, high value) · **Nice to have** (NOT implemented — requires user approval,
see §2) · **Do not implement** (with reasoning).

## 1. Feature matrix

| Feature | Common in dashcams | Useful here | Technical difficulty | Thermal risk | Recommendation |
|---|---|---|---|---|---|
| Loop recording, 1/3/5/10-min segments | Universal (3 min default) | Essential | Medium (gapless requires custom pipeline) | Low | **Must have** — implemented |
| Automatic storage management + cap | Universal | Essential | Medium | None | **Must have** — implemented (5 GB default) |
| Locked/protected event folder | Universal (RO/EVENT folder) | Essential | Low | None | **Must have** — implemented |
| G-sensor event detection (3 sensitivity levels) | Universal | Essential | Medium-high (no gyro on Moto G04; accel-only classification) | Low | **Must have** — implemented |
| Buffered pre/post-event capture | Standard (10–30 s pre) | Essential | Medium (segment+index architecture) | Low | **Must have** — implemented (30 s pre / 60 s post defaults) |
| Manual protect button | Universal | Essential | Low | None | **Must have** — implemented |
| GPS speed/time stamp burned into video | Universal (togglable) | High | Medium (GL overlay stage) | Low (1 Hz texture update) | **Must have** — implemented |
| GPS track logging (GPX export) | Standard (embedded or sidecar) | High | Low | Low | **Must have** — implemented (GPX 1.1 + speed extension) |
| Screen-off recording | N/A (hardware) / rare in apps | High | Medium (FGS typing, OEM variance) | Negative (saves heat) | **Must have** — implemented with watchdog + honest OEM caveats |
| Charging/power-state behaviour | Universal (ignition sense) | High | Low | None | **Must have** — implemented (configurable plug/unplug actions) |
| Thermal monitoring + proactive degradation | Hardware: temp cutoff at ~75 °C; apps: none do it well | Very high — the app's differentiator | Medium | Negative (it prevents heat) | **Must have** — implemented (policy ladder + simulation harness) |
| Auto quality profile per device | Rare (apps hard-code) | Very high | Medium | Negative | **Must have** — implemented (runtime capability profiler) |
| Battery floor / stop-below-% | Hardware analog: low-voltage cutoff | High | Low | None | **Must have** — implemented |
| In-app recording review/playback | Common in apps | High | Low (ExoPlayer) | Low (not while recording) | **Should have** — implemented |
| Export/share clip (universal MP4 remux) | Common | High | Low-medium | Low (short burst) | **Should have** — implemented |
| Diagnostics screen + report export | Rare | High for field testing | Low | None | **Should have** — implemented |
| Offline map panel | Apps: playback-map only; hardware: none | Medium | High (native lib, offline data) | Medium — bounded (FPS cap, off by default on low tier) | **Should have** — implemented per brief §29, OFF by default |
| Weather overlay | Absent in both | Low-medium | Low | Low (cached, optional) | **Should have** — implemented per brief §19 (optional) |
| HDR video recording | Standard in hardware (WDR) | Medium | High (device-specific 10-bit profiles) | Medium-high | **Nice to have** — needs approval; runtime-gated, Edge-class only |
| Front (cabin) camera recording | Common (2CH hardware) | Medium | Medium | Medium | **Should have (single-camera switch)** — implemented as optional camera selection; simultaneous dual-camera is **Nice to have** (needs approval + on-device verification of `getConcurrentCameraIds`) |
| Parking mode (motion/time-lapse surveillance) | Standard in hardware | Low on a phone | Very high (can't start camera FGS from background; heat; battery) | High | **Do not implement** — platform forbids headless start; overnight heat/battery risk; honest documentation instead |
| Cloud upload / accounts | Nexar/BlackVue ecosystems | Against spec | — | — | **Do not implement** — privacy-first, offline-first spec |
| Voice control ("save that") | Garmin/Viofo/Nextbase | Medium | High (offline ASR = CPU/thermal cost) | High | **Do not implement now** — thermal budget; candidate for approval later |
| ADAS (lane departure, collision warning) | 70mai etc. | Low | Very high (continuous ML) | Very high on Moto G04 | **Do not implement** — thermal/CPU budget; false-alert liability |
| Emergency SOS auto-call | Nextbase 622GW | Medium | High (liability, false triggers) | Low | **Do not implement** — safety-critical claims need certification-grade detection; detector is explicitly assistance-only |
| Time-lapse recording | Common in hardware | Low-medium | Medium | Low | **Nice to have** — needs approval |
| Bluetooth/dock auto-start triggers | AutoBoy | Medium | Low-medium (still can't start camera FGS from background — prompt only) | None | **Nice to have** — needs approval; would be notification-prompt based |
| Speed-camera alerts | Hybrid apps | Low | High (data licensing, legality varies by AU state) | Low | **Do not implement** — legal risk (illegal in Victoria), data licensing |
| CPL filter | Hardware accessory | N/A (physical) | — | — | Documented in troubleshooting (windscreen reflections) |
| Registration-plate stamp text | Droid Dashcam | Medium | Trivial | None | **Should have** — implemented as optional custom overlay label |
| Motion detection while parked | Hardware standard | Low on phone | High | High | **Do not implement** — see parking mode |

## 2. Features awaiting user approval (not implemented)

Per the project brief (§36), these are proposed, not built. Each is summarised with
what approval would entail:

1. **Simultaneous dual-camera (road + cabin) recording**
   - Benefit: 2-channel evidence like premium hardware dashcams.
   - Complexity: high — second capture session/encoder, per-device
     `getConcurrentCameraIds()` gating; UI for dual preview.
   - Thermal: high — second ISP+encoder load; must be blocked on CONSTRAINED tier.
   - Storage: ~1.7× consumption. Privacy: records vehicle occupants — needs clear
     in-app disclosure. Recommendation: approve for CAPABLE-tier devices only.
2. **HDR (10-bit) recording** on capable devices
   - Benefit: better tunnel-exit/night contrast. Complexity: high (HLG/HDR10 profile
     plumbing, player compatibility). Thermal: medium-high. Recommendation: defer
     until on-device measurement on the Edge 60 Fusion.
3. **Time-lapse mode**
   - Benefit: long-trip compression. Complexity: medium (frame-decimation in the GL
     stage). Thermal: low. Storage: much lower. Recommendation: approve — cheap win.
4. **Bluetooth-connect auto-prompt** (car stereo pairing → "start recording?"
   notification)
   - Benefit: near-automatic start UX within platform limits. Complexity: low-medium.
     Privacy: needs BLUETOOTH_CONNECT permission. Recommendation: approve.
5. **Voice-triggered protect** ("save that")
   - Benefit: hands-free protection. Complexity: high (offline keyword spotting).
     Thermal: continuous audio DSP — measurable. Recommendation: defer.

## 3. Honest platform limitations recorded during research

- **No headless auto-start.** Android 14+ forbids starting a camera foreground
  service from the background (boot, power-connected, WorkManager). The app's
  automatic behaviours are therefore: auto-start on app launch (default ON, ~3 s
  delay), auto-start/stop on power events **while the app is running**, and a
  tap-to-start notification otherwise. No surveyed app genuinely solves this; the
  ones that claim to predate Android 14 restrictions.
- **Screen-off recording is OEM-dependent.** Framework-level support is real
  (camera FGS), but some OEM HALs/power managers stop capture. The app detects this
  (encoder watchdog) and offers the dimmed-black-screen fallback rather than
  pretending it works everywhere.
- **No 24/7 parking mode on a phone.** Heat, battery chemistry, and the FGS
  restriction make it dishonest to ship; documented instead.
