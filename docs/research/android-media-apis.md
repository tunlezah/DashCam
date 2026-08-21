# Android Media / Platform API Research

Research date: 2026-08-21. Target: minSdk 34 (Android 14) → API 36 (Android 16).
Confidence levels: **CONFIRMED** / **LIKELY** / **UNCERTAIN**.

This research drives the recording-pipeline decision in `docs/architecture.md`.

---

## The key decision: CameraX Recorder vs Camera2 + MediaCodec for gapless segmented loop recording

**Verdict: CameraX Recorder cannot do truly gapless file rollover. For zero-frame-loss
segmentation, use Camera2 with a persistent MediaCodec encoder session and rotating
muxer outputs.** Confidence: CONFIRMED for the API-shape facts, LIKELY for gap magnitude.

Evidence:

- CameraX `Recorder` supports **exactly one `Recording` at a time**; there is no
  `setNextOutputFile()` analog and nothing queued: stopping finalizes the encoder output
  and muxer, and only then can the next `PendingRecording.start()` spin up. Frames
  arriving between finalize and next start are discarded. CONFIRMED —
  [CameraX video capture guide](https://developer.android.com/media/camera/camerax/video-capture)
- When `setDurationLimitMillis()`/`setFileSizeLimit()` trips, the recording finalizes
  with `ERROR_DURATION_LIMIT_REACHED`/`ERROR_FILE_SIZE_LIMIT_REACHED` and **stops** —
  no automatic roll into the next file. CONFIRMED
- CameraX current stable is **1.6.1** (2026-05-06); 1.7.0-alpha03 (2026-08-12).
  Release-note scan of 1.5.x/1.6.x/1.7-alpha: Feature Group API, high-speed video,
  mirror modes, audio channel control, format discovery, and — notably — **1.6.0
  switched VideoCapture to the Media3 muxer internally citing "enhanced crash resilience
  and protection against file corruption"**. **Nothing addresses seamless segment
  rollover.** CONFIRMED — [CameraX releases](https://developer.android.com/jetpack/androidx/releases/camera),
  [CameraX 1.5 blog](https://android-developers.googleblog.com/2025/11/introducing-camerax-15-powerful-video.html)
- `asPersistentRecording()` keeps one recording alive across rebinding — irrelevant to
  file rotation. CONFIRMED
- **Gap magnitude:** not officially documented; community reports cluster around
  **~100 ms to ~1 s** per stop→start depending on device and audio. UNCERTAIN on exact
  number, LIKELY on order of magnitude.

A dashcam's one job is capturing the moment of impact; a recurring blind spot every
segment boundary is a product defect the brief explicitly forbids ("avoid visible gaps
between segments"). The gapless Camera2 design is therefore used — see
`docs/architecture.md` for the full decision record including the trade-offs accepted.

## 1. CameraX facts recorded for reference

- `QualitySelector` guarantees hold for VideoCapture alone or VideoCapture+Preview only.
- `camera-effects` **`OverlayEffect`** (stable since 1.4.0): GPU Canvas compositing into
  recorded video — CameraX's supported burn-in path. CONFIRMED
- StreamSharing (1.3+) rescues unsupported stream combos via an internal OpenGL copy on
  budget devices — i.e. CameraX itself falls back to exactly the GL-interposer
  architecture this app implements directly. CONFIRMED —
  [architecture doc](https://developer.android.com/media/camera/camerax/architecture)
- Pre-1.6 MP4 output wrote moov at stop → power-cut = corrupt file; 1.6 adopted the
  Media3 muxer for this reason. Validates this app's fragmented-MP4 decision. CONFIRMED

## 2. Camera2 + MediaCodec best-practice pipeline (the design used)

CONFIRMED patterns (grafika/CameraToMpegTest lineage, MediaCodec docs):

1. **One long-lived encoder session.** `MediaCodec.createInputSurface()`, configure once
   (codec, bitrate, I-frame interval), never stop at segment boundaries.
2. **Rotate the muxer, not the codec.** On `INFO_OUTPUT_FORMAT_CHANGED`, cache the
   output `MediaFormat` (contains csd-0/csd-1 — SPS/PPS). Each new segment: new muxer,
   `addTrack(cachedFormat)`, `start()`, begin writing **at the next sync frame**.
3. **Key-frame alignment.** Request an on-demand keyframe at each boundary via
   `PARAMETER_KEY_REQUEST_SYNC_FRAME`; cut when `BUFFER_FLAG_KEY_FRAME` arrives.
   Every encoded frame lands in exactly one file → **zero gap**. Rebase PTS per segment.
4. **Muxer constraints:** track format must carry csd (always use the one from
   `INFO_OUTPUT_FORMAT_CHANGED`); first video sample of a file must be a sync frame;
   moov written at `stop()` for classic MP4 → fsync+rename per segment, or use
   fragmented MP4 (Media3 `FragmentedMp4Muxer`) for crash-durable in-progress files.
5. **Audio:** separate `AudioRecord`+AAC encoder, cut at the same boundary (audio has
   no keyframe constraint).
6. **`MediaRecorder.setNextOutputFile()` (API 26+)** exists and is designed for seamless
   rotation but: size-triggered (not duration), MPEG-4 only, timing-sensitive, OEM
   implementations vary, and no overlay burn-in path. CONFIRMED existence, LIKELY on
   reliability variance. Not chosen.

## 3. Android 14+ foreground service requirements

CONFIRMED — [FGS types](https://developer.android.com/develop/background-work/services/fg-service-types),
[Android 14 FGS changes](https://developer.android.com/about/versions/14/changes/fgs-types-required),
[FGS changes by release](https://developer.android.com/develop/background-work/services/fgs/changes):

- API 34+: every FGS declares `android:foregroundServiceType` + matching permission
  (`FOREGROUND_SERVICE_CAMERA` / `_MICROPHONE` / `_LOCATION` plus base
  `FOREGROUND_SERVICE`). One service can declare `camera|location|microphone` combined.
- **Background-start restriction:** camera/mic FGS **cannot be created while the app is
  in the background**, and (targeting 15+) cannot be launched from `BOOT_COMPLETED`.
  **Fully-automatic headless auto-start (boot/power-connected) is impossible for a
  camera FGS on modern Android** — recording must start from a visible activity or a
  user interaction. The app's power-connected "auto-start" therefore only operates while
  the app is open or via a tap-to-start notification. CONFIRMED.
- **Screen off:** the while-in-use gate is evaluated at camera open/FGS start. With a
  camera-type FGS started in the foreground, capture continues when the screen turns
  off. CONFIRMED at framework level; OEM caveat: some OEM HALs/power managers throttle
  or stop capture on screen-off (LIKELY, community-documented) — the app watchdogs
  encoder output and surfaces an explanation if the OEM kills capture.
- **Android 15:** 6h/24h timeout applies to `dataSync`/`mediaProcessing` only —
  **camera/mic/location FGS have no timeout**. CONFIRMED.
- **Android 16:** no new camera FGS limits; jobs started from an FGS now count against
  job quotas (affects cleanup workers, not recording). CONFIRMED.

## 4. Thermal APIs

- `PowerManager.getCurrentThermalStatus()` / `addThermalStatusListener()` — API 29.
  Levels: NONE(0), LIGHT(1), MODERATE(2, UX impacted), SEVERE(3, significant
  throttling), CRITICAL(4), EMERGENCY(5 — **the camera itself may be disabled by the
  platform at this level**), SHUTDOWN(6). CONFIRMED —
  [ADPF thermal guide](https://developer.android.com/games/optimize/adpf/thermal)
- `getThermalHeadroom(forecastSeconds)` — API 30. 0.0 → 1.0 (1.0 = SEVERE threshold).
  **Rate-limited: returns NaN if polled too fast; official guidance ≤ once per 10 s,
  single thread.** CONFIRMED.
- **Headroom listener + `getThermalHeadroomThresholds()`: API 36** (NDK
  `AThermal_registerThermalHeadroomListener` confirmed API 36; Java listener same level
  LIKELY). Poll on 14/15; listen on 16+. CONFIRMED/LIKELY.
- **Budget-device reality (CONFIRMED, documented in ADPF guide):** headroom may return
  **NaN (unsupported)** or frozen values (heuristics: first call 0/NaN ⇒ unsupported;
  headroom ≥0.85 while status NONE ⇒ stale). Fallback chain: headroom → thermal status
  listener → battery temperature (`ACTION_BATTERY_CHANGED` `EXTRA_TEMPERATURE`).
- ADPF `PerformanceHintManager` (API 31): designed for game frame loops; marginal value
  for an encoder-surface pipeline. Skipped. LIKELY (relevance judgment).

## 5. Media3

- Current stable **1.11.0** (2026-08-05). CONFIRMED —
  [Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3)
- **ExoPlayer**: in-app playback of recordings; playlist API gives near-gapless
  cross-segment playback of keyframe-aligned files.
- **`media3-muxer`**: `Mp4Muxer` / `FragmentedMp4Muxer` usable standalone with a custom
  MediaCodec pipeline — fragmented MP4 avoids the moov-at-end corruption problem
  (what CameraX 1.6 adopted internally). CONFIRMED availability.
- **Transformer** overlays (`TextOverlay`, `BitmapOverlay`) available for
  post-processing export; not needed since overlays are burned live in GL.

## 6. Storage

CONFIRMED framework facts:

- Primary store: `context.getExternalFilesDir(DIRECTORY_MOVIES)` — no permission, not
  scanned into Gallery, deleted on uninstall (documented to users).
- SD card: `getExternalFilesDirs(null)` index ≥1 = removable volumes, writable without
  permission; handle mount/unmount mid-write.
- Export: MediaStore + `IS_PENDING=1` atomic-publish pattern for user-initiated saves.
- Space: `StorageManager.getAllocatableBytes(uuid)` is the honest "can I keep
  recording" number (API 26); delete-oldest preemptively, before the muxer needs space.
- Crash safety: write `name.mp4.tmp` → fsync → close → atomic rename (same dir) →
  fsync parent dir. Residual mid-segment risk handled by fragmented MP4 + boot-time
  recovery pass that salvages readable files and discards junk.

## 7. Location without connectivity

- **GNSS needs no SIM/network.** `FusedLocationProviderClient` works fully offline but
  requires Play services; **`LocationManager.GPS_PROVIDER`** is pure GNSS with no
  dependency. Cold fix without network assistance (no downloaded ephemeris) can take
  30 s+. This app uses `LocationManager` directly: no Play-services dependency,
  privacy-clean, works on de-Googled devices. CONFIRMED.
- `registerGnssStatusCallback` (`GnssStatus`) for satellites-in-view/used — drives the
  GPS status indicator.
- `Location.getSpeed()` is **Doppler-derived**, typically ~0.1–0.5 m/s accurate at
  driving speed — far better than position differencing; check `hasSpeed()` and
  `getSpeedAccuracyMetersPerSecond()`. CONFIRMED API, LIKELY accuracy figures.
- FGS must include `location` type + `FOREGROUND_SERVICE_LOCATION` to keep updates
  while screen-off. CONFIRMED.

## 8. AV1 / HEVC

- **AV1 hardware encode: effectively nonexistent** in these device classes (only
  Tensor G3+ has a mobile AV1 encoder block). Not built around; opportunistic only if
  `MediaCodecList` reports a hardware `video/av01` encoder. CONFIRMED.
- HEVC hardware encoders are ubiquitous on modern mid-range (Dimensity 7300: yes) but
  **absent on Unisoc T606** (assume H.264-only there). ~35–50% bitrate savings vs AVC.
- HEVC royalty obligations attach to codec implementers (SoC/OEM); an app invoking the
  platform encoder does not itself owe royalties. LIKELY (standard interpretation,
  not legal advice).
- Compatibility: HEVC playback friction off-device (older Windows needs a paid
  extension). Default: **H.264 loop for universal evidence playability; HEVC opt-in**
  where hardware-accelerated. LIKELY (judgment).

## 9. Battery / charging monitoring

- `ACTION_BATTERY_CHANGED`: sticky — read synchronously with `registerReceiver(null,…)`;
  provides level, status, plugged type, voltage, **temperature**. CONFIRMED.
- `ACTION_POWER_CONNECTED/DISCONNECTED` are **NOT on the implicit-broadcast exceptions
  list** — manifest receivers do not work for them (API 26+). Context-registered
  receivers while the app/service runs are the mechanism. WorkManager
  `setRequiresCharging(true)` can fire in background but cannot start a camera FGS —
  it can only post a tap-to-start notification. CONFIRMED —
  [broadcast exceptions](https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions)
- `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`, `isCharging()`,
  `computeChargeTimeRemaining()`, `BATTERY_PROPERTY_CURRENT_NOW` (negative-while-
  "charging" is common on weak 12 V USB ports — worth surfacing in diagnostics).

## 10. Keeping recording alive

- **`PARTIAL_WAKE_LOCK`** held for the life of the recording service — an FGS alone
  does not guarantee CPU wakefulness with screen off. CONFIRMED.
- OEM screen-off behaviour varies (some HALs pause capture) — mitigations: optional
  "screen on, dimmed black" mode, encoder-output watchdog (no frames for N seconds →
  alert/recover), user guidance for OEM battery whitelisting
  ([dontkillmyapp.com](https://dontkillmyapp.com)). LIKELY (community-documented).
- Battery-optimization exemption: prefer `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`
  (no permission) + guidance; the direct-request permission has Play-policy risk.
  CONFIRMED policy existence.

---

## Bottom-line architecture recommendation (adopted)

- **Recording core:** Camera2 + one persistent MediaCodec encoder + rotating
  Media3 `FragmentedMp4Muxer` outputs, keyframe-aligned cuts → true zero-gap loop,
  crash-durable in-progress segments. GL interposer (single camera output →
  fan-out to encoder + preview surfaces) both sidesteps LIMITED-device stream-combo
  constraints and provides the overlay burn-in stage.
- **Service:** single FGS `camera|location|microphone`, started only from foreground
  UI, PARTIAL_WAKE_LOCK held, thermal-driven quality ladder; no FGS timeout on 14/15/16.
- **Codec:** H.264 default; HEVC opt-in where hardware-accelerated; AV1 ignored.
- **Storage:** app-specific external dirs (+SD), fragmented MP4, allocatable-bytes-
  driven preemptive loop eviction; classic-MP4 remux on user export.
- **Playback:** ExoPlayer.
