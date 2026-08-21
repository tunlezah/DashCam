# DashCam — Architecture & Design

Status: implemented design. Decision records reference the research in
`docs/research/`.

---

## 1. Product invariant

> Start recording reliably, continue recording for long periods, protect important
> footage, manage storage automatically, and avoid overheating the phone.

Every architectural decision below is subordinated to this. Optional features (map,
weather, overlays, front camera) are structured so their failure or cost can never
stop the recording pipeline: they run in separate components with independent error
handling, and the thermal engine sheds them first.

## 2. Technology stack (Decision record)

| Choice | Decision | Why |
|---|---|---|
| Language / UI | Kotlin + Jetpack Compose (Material 3) | Current Android standard; declarative UI keeps the status-heavy dashcam screen simple. |
| Camera | **Camera2 directly** (not CameraX) | CameraX `Recorder` cannot do gapless segment rollover — each file finalizes and restarts the recording, losing frames at every boundary (see `docs/research/android-media-apis.md`). A dashcam cannot have a recurring blind spot. Camera2 gives one continuous capture session for hours. |
| Encoding | **MediaCodec** persistent encoder session (surface input) | One encoder session for the whole drive; segments are cut in the muxer layer at keyframes — zero frames lost. |
| Container | **Fragmented MP4** via Media3 `FragmentedMp4Muxer` | Classic MP4 writes `moov` at stop → a crash/power-cut corrupts exactly the segment that matters (the crash). fMP4 is durable per-fragment (~1 s granularity). CameraX 1.6 adopted the same muxer internally for the same reason. Export/share remuxes to classic MP4 for universal compatibility. |
| Frame routing | **OpenGL ES interposer** (single camera output → fan-out to encoder + preview) | (a) LIMITED-level devices (Moto G04 class) have constrained stream-combination guarantees; one camera output is the safest combo. (b) The GL stage is where overlays are burned into the video. (c) Orientation/crop handled as texture transforms. CameraX's own StreamSharing does exactly this internally on such devices. |
| Playback | Media3 ExoPlayer | Plays fMP4 natively; playlist API for cross-segment review. |
| Persistence | Room (segment index, event log), DataStore Preferences (settings) | Segment metadata must survive process death and drive recovery; Room is the boring, robust answer. |
| DI | Hand-wired `AppGraph` | Small object graph, explicit construction order, no Hilt/KSP-DI build overhead on a project this size. |
| Maps | MapLibre Native (OpenGL artifact) + PMTiles, optional | See `docs/offline-maps.md`. OFF by default; never required. |
| Location | `LocationManager` GPS provider directly | No Play-services dependency; pure GNSS works with no SIM/network; privacy-clean. |
| Weather | Open-Meteo (no API key), optional | No secrets in repo; cached; never blocks or gates recording. |

Rejected alternatives, and why:

- **CameraX `Recorder` + restart-on-Finalize**: sub-second gap at every segment
  boundary (~0.1–1 s each 1–10 min). Rejected: violates "no gaps"; an impact at a
  boundary is precisely the footage that matters.
- **`MediaRecorder.setNextOutputFile()`**: seamless but size-triggered (not
  duration), MPEG-4-only, historically OEM-flaky, and no overlay burn-in path.
- **AV1**: no hardware encoder on either target class (research CONFIRMED).
- **Hilt/Koin**: unnecessary for one module with ~15 singletons.
- **On-device ML crash detection**: CPU/thermal budget on the Moto G04
  (2×A75 @1.6 GHz doing 1080p30 encode) does not leave room for continuous camera
  ML with acceptable benefit; the accelerometer pipeline covers the requirement.
  Documented in `docs/event-detection.md`.

## 3. Module & package map (single Gradle module, layered packages)

```
com.tunlezah.dashcam
├── AppGraph.kt                  # hand-wired DI
├── core/                        # pure-Kotlin utilities, clock, logging ring buffer
├── domain/
│   ├── capability/              # DeviceCapabilityProfiler, RecordingProfile selection
│   ├── thermal/                 # ThermalEngine, ThermalPolicy, simulated source
│   ├── storage/                 # StorageManager (loop eviction, reserves, stats)
│   ├── events/                  # EventDetector (accelerometer), EventProtector
│   ├── location/                # GpsManager, speed filter, GpxWriter
│   ├── power/                   # PowerMonitor (battery/charging), power policy
│   └── settings/                # Settings model + validation, DataStore repo
├── data/
│   └── db/                      # Room: SegmentEntity, EventEntity, DAOs
├── recording/
│   ├── engine/                  # Camera2 controller, GL pipeline, encoders, muxer rotation
│   ├── overlay/                 # OverlayRenderer (Canvas→texture), overlay config
│   ├── RecordingService.kt      # FGS (camera|location|microphone)
│   └── RecordingOrchestrator.kt # state machine tying everything together
├── map/                         # optional MapLibre panel (isolated; failure-safe)
├── weather/                     # optional Open-Meteo client + cache
└── ui/                          # Compose: main screen, settings, diagnostics, library, themes
```

The layering rule: `ui` and `recording` may depend on `domain`; `domain` depends only
on `core` and `data`. Optional features (`map`, `weather`) are leaves nothing else
depends on.

## 4. Recording pipeline

```
Camera2 CameraDevice
   └── CaptureSession ── one output: SurfaceTexture (GL external texture)
                              │
                        GlRenderPipeline (EGL context, dedicated thread)
                              │   • orientation / crop transform
                              │   • overlay quad (Canvas-rendered texture, updated ≤1 Hz)
                              ├──► MediaCodec video encoder input surface (drives recording)
                              └──► Preview surface (when UI visible; detached when screen off)
MediaCodec (H.264/HEVC, surface input, persistent session)
   └── EncoderDrain thread ── SegmentSink
                                  │  keyframe-aligned rotation
                                  ├── FragmentedMp4Muxer → segment N   (.mp4, fMP4)
                                  └── FragmentedMp4Muxer → segment N+1
AudioRecord → AAC MediaCodec (only when mic enabled) → same SegmentSink
```

Key behaviours:

- **Gapless segmentation.** At each boundary the sink requests a sync frame
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`); when the next `BUFFER_FLAG_KEY_FRAME` arrives
  the old muxer is finalized (on a worker) and the new one starts with that keyframe.
  Every encoded frame lands in exactly one file. PTS are rebased per segment.
- **Crash durability.** fMP4 fragments (~1 s) are written through; a power cut
  loses at most the last fragment. A startup recovery pass (§8) validates and
  indexes any orphaned segment.
- **Preview is expendable.** The preview surface is attached/detached from the GL
  fan-out at runtime (screen off, thermal shedding) without touching the camera
  session or encoder.
- **Overlay burn-in** happens in GL: a small Canvas-rendered bitmap (speed, GPS,
  date/time, weather) uploaded as a texture at most once per second, drawn as a
  translucent quad. Cost: one small texture upload + one quad per frame. If the user
  disables burn-in, the quad is skipped entirely; overlays can still be displayed
  UI-only and/or logged to the GPS track.
- **Watchdog.** The orchestrator monitors encoder output; if no encoded frames
  arrive for 5 s while recording, it executes controlled recovery (§9).

## 5. Device capability profiling

`DeviceCapabilityProfiler` runs at startup (cached, re-validated when hardware-related
state changes) and inspects — never assumes:

- API level, RAM (`ActivityManager.MemoryInfo`), core count/max freq
- Camera: `CameraCharacteristics` hardware level, supported sizes/fps for the
  encoder surface class, stabilisation modes, concurrent camera IDs
- Codecs: `MediaCodecList` — hardware AVC/HEVC/AV1 encoders
  (`isHardwareAccelerated`), max supported instances, capability ranges
- Thermal API support: headroom NaN probe, status availability
- Storage: allocatable bytes, removable volumes
- Sensors: accelerometer, gyroscope (absent on Moto G04), magnetometer

From these it computes a **device tier** (CONSTRAINED / BALANCED / CAPABLE) and a
recommended `RecordingProfile` (resolution, fps, codec, bitrate). The algorithm and
scoring are documented in `docs/device-profiles.md`. The user can override any part
in Settings; overrides are validated against actual capabilities. All profiling
results and the final decision are logged for diagnostics.

## 6. Thermal engine

`ThermalEngine` merges three sources (best available wins):

1. `PowerManager.getThermalHeadroom(30)` polled every 15 s (NaN/staleness detected →
   source disabled) — plus the API 36 headroom listener when available
2. `addThermalStatusListener` (status levels)
3. Battery temperature from `ACTION_BATTERY_CHANGED` as last-resort fallback

It emits a `ThermalState` (NOMINAL / WARM / ELEVATED / HIGH / CRITICAL) with
hysteresis (no oscillation), consumed by the `ThermalPolicy` mitigation ladder:

| State | Actions (cumulative) |
|---|---|
| NOMINAL | recommended profile |
| WARM | preview fps cap, disable weather refresh, map fps floor |
| ELEVATED | detach preview (screen dim prompt), pause map, reduce GPS rate |
| HIGH | reduce bitrate → then resolution/fps step-down (encoder reconfigure at next segment boundary) |
| CRITICAL | minimum viable profile; if EMERGENCY status: clean stop with protected finalization + clear user notification |

A `SimulatedThermalSource` (debug builds / tests) can inject any state sequence —
the test harness required by the brief. Every transition and mitigation is logged.
Full design: `docs/thermal-management.md`.

## 7. Storage manager

- Segments live in `getExternalFilesDir(Movies)/loop/`; protected footage in
  `/protected/` (mirrors hardware-dashcam RO-folder convention).
- Loop cap (default 5 GB) enforced **preemptively**: before each new segment starts,
  the manager ensures ≥ 2 segments of space by deleting oldest unprotected segments.
- Device safety reserve: recording pauses (with clear status) if free device space
  would drop below max(1 GB, 5% of total); the loop cap is also auto-clamped.
- Protected storage has its own budget (default 2 GB): when exceeded, the user is
  warned; new protections still succeed (oldest loop gives way first); nothing
  protected is ever auto-deleted.
- Room index is the source of truth for the UI; the filesystem is the source of
  truth at recovery (index is rebuilt from disk when they disagree).
  Full design: `docs/storage.md`.

## 8. Startup recovery

On service start (and app start):

1. Scan `loop/` and `protected/` for files not in the index or marked in-progress.
2. Probe each with `MediaExtractor`: readable fMP4 (valid up to last complete
   fragment) → finalize its index row, keep (marked "recovered").
3. Unreadable/zero-length files → move aside to `quarantine/` (bounded, oldest
   dropped) rather than delete immediately; surfaced in diagnostics.
4. An event that was mid-protection at crash time is re-protected from the index
   journal (event rows are written before protection starts).

## 9. Failure handling & controlled recovery

State machine in `RecordingOrchestrator` with bounded exponential backoff
(1 s → 2 s → 4 s → 8 s, max 5 attempts per failure class, counters reset after
10 min healthy):

- **Camera error/disconnect** → close device, reopen, rebuild session. Camera in
  use by another app → wait for availability callback.
- **Encoder error** → release codec, recreate at same profile once, then step the
  profile down; segment in progress is finalized (fMP4 keeps it valid).
- **Storage error** (write fail/unmounted SD) → switch to internal volume, resume.
- **Repeated unrecoverable failure** → stop cleanly, preserve everything written,
  post a persistent notification explaining exactly why recording stopped.
- Recording never blocks the UI thread: camera, GL, encoder-drain, muxer-finalize
  and storage-scan each have their own thread/dispatcher.

## 10. Foreground service & screen-off

`RecordingService`: one FGS with `camera|location|microphone` types (mic type only
started when audio enabled). Started only from the foreground activity (Android 14+
forbids background camera-FGS starts — honest consequence: no headless auto-start;
the power-connected trigger can only auto-start while the app is open, otherwise it
posts a tap-to-start notification). Holds `PARTIAL_WAKE_LOCK` while recording.
Screen-off recording detaches the preview surface; an encoder watchdog detects OEM
HALs that stop capture on screen-off and falls back to the "screen on, dimmed black
overlay" mode with an explanation (see research §10 — OEM behaviour varies and is
not claimed to work universally).

## 11. Orientation strategy (portrait mount)

Physics first: with the phone portrait, the sensor's wide axis is vertical — no
software recovers horizontal FOV. Design (full analysis in
`docs/research/orientation-analysis.md` section of architecture docs):

- Mount orientation detected from the accelerometer gravity vector (no gyro needed).
- **Landscape mount (recommended, stated in onboarding):** native 16:9 landscape
  recording — full horizontal FOV, standard dashcam output.
- **Portrait mount:** default output is a **16:9 landscape crop** of the portrait
  frame (the evidence-relevant horizontal band; conventional playback; smaller
  files), with a vertical aim adjustment. A "full portrait frame" option records
  9:16 for maximum vertical coverage. Both produce upright, rotation-correct files
  (orientation applied in GL, no reliance on rotation metadata).

## 12. Threading model

| Thread | Owner |
|---|---|
| Main | UI only |
| `CameraThread` (HandlerThread) | Camera2 callbacks |
| `GlThread` | EGL context, frame rendering |
| `EncoderDrain` (video), `AudioThread` | codec output/input |
| `Muxer` dispatcher | segment finalize, remux-on-export |
| `IO` dispatcher | Room, storage scans, GPX writes |
| `Default` dispatcher | event detection math, profiling |

## 13. Privacy boundary

No network calls exist outside `weather/` (optional, user-enabled, Open-Meteo) and
`map/` region downloads (user-initiated). No analytics, no crash reporting service,
no cloud. GPS data never leaves the device. See `docs/privacy.md`.
