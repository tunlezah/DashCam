# Dashcam Landscape Research

Research date: 2026-08-21.
Confidence levels: **CONFIRMED** (multiple/official sources), **LIKELY** (single
reputable source or reasoned synthesis), **UNCERTAIN** (thin/conflicting sources).

This document surveys existing Android dashcam apps and dedicated hardware dashcams to
establish the conventions this app should follow and the failure modes it must avoid.
The distilled feature table with recommendations lives in `docs/feature-research.md`.

---

## 1. Android dashcam apps

### Droid Dashcam – Video Recorder (com.helge.droiddashcam)
- Continuous loop recording; user-configurable resolution, bitrate, FPS, codec;
  auto-deletes oldest files when storage is low. ~2.5M downloads, ~4.3/5. CONFIRMED
- Background recording with notification-panel start/stop controls. CONFIRMED
- Standout feature: **burned-in subtitles** in the video file — timestamp, address, GPS
  coordinates, GPS speed, registration plate. CONFIRMED
- Auto-start on app launch; dual-camera on supported phones. CONFIRMED
- Past bugs around recording misbehaving when backgrounded (since fixed). LIKELY
- Sources: [Google Play](https://play.google.com/store/apps/details?id=com.helge.droiddashcam&hl=en_US),
  [AppBrain](https://www.appbrain.com/app/droid-dashcam-video-recorder/com.helge.droiddashcam)

### AutoBoy Dash Cam – BlackBox (com.happyconz.blackbox)
- Continuous background recording; auto-delete; external SD storage. CONFIRMED
- "Smart crash sensor" (accelerometer) auto-saves incident footage. CONFIRMED
- Richest trigger set surveyed: Car Dock, power connection, Bluetooth, GPS conditions. CONFIRMED
- 3-mode playback (Video / Video+Map / Map) from recorded GPS tracks. CONFIRMED
- Sources: [Softonic](https://autoboy-dash-cam-blackbox.en.softonic.com/android),
  [Aptoide](https://blackbox.en.aptoide.com/app)

### DailyRoads Voyager (com.dailyroads.v)
- One of the oldest Android dashcam apps. Loop recording with auto-delete; manual or
  automatic video protection. CONFIRMED
- Background recording with optional floating controls. CONFIRMED
- Timestamped/geotagged with speed, elevation, coordinates. CONFIRMED
- **Reliability complaints**: scoped-storage friction, unreliable background operation,
  auto-start problems; v8.1 was released specifically to fix background recording broken
  by Android 14 — a telling example of OS churn breaking dashcam apps. CONFIRMED
- Sources: [dailyroads.app/features](https://dailyroads.app/features),
  [Google Play](https://play.google.com/store/apps/details?id=com.dailyroads.v)

### AutoGuard Dash Cam – Blackbox (com.hovans.autoguard)
- Video + speed + GPS + nearest address; path on Google Maps; YouTube upload. CONFIRMED
- Background recording is a paid feature. Auto-start on Bluetooth. CONFIRMED
- Complaints: lost entitlements, inaccurate GPS speed, background failures,
  **corrupted or missing video files**. CONFIRMED
- Sources: [Google Play](https://play.google.com/store/apps/details?id=com.hovans.autoguard&hl=en),
  [MakeUseOf](https://www.makeuseof.com/tag/dash-cam-android-comparison/)

### Nexar
- Now primarily a hardware ecosystem tethered to a phone app; automatic incident
  detection, cloud event backup, subscription-gated features. CONFIRMED
- Complaints: recording/connectivity failures, battery drain, storage consumption. CONFIRMED
- Sources: [getnexar.com](https://www.getnexar.com/),
  [TechRadar](https://www.techradar.com/reviews/nexar-beam)

### CamOnRoad
- Car DVR + AR overlay; 2 GB free cloud; accident detection with auto emergency calling.
  Development appears stagnant. LIKELY
- Source: [APKCombo](https://apkcombo.com/camonroad-car-dvr-ar-driver-assistance/com.camonroad.app/)

### Speedometer/dashcam hybrids
- Crowded category of lower-quality ad-supported apps combining GPS speedometer + HUD +
  loop recording + G-sensor lock; thin on storage-management rigour. CONFIRMED/LIKELY

### Platform constraints affecting all apps
- Since Android 9, background camera access requires a **foreground service**;
  Android 14+ requires `foregroundServiceType="camera"` + `FOREGROUND_SERVICE_CAMERA`
  or a SecurityException is thrown. Persistent notification + green camera-dot
  indicator are mandatory. CONFIRMED
- The most common field failure is **OEM battery optimisation killing the app
  mid-drive**; the fix (battery-optimisation exemption) must be granted manually. CONFIRMED
- Sources: [javathinking.com](https://www.javathinking.com/blog/android-app-video-recording-when-screen-off/)

---

## 2. Dedicated hardware dashcams — standard feature set

### Loop recording
- Segment lengths **1 / 3 / 5 minutes** (some add 2/10), 3 min the near-universal
  default; oldest files overwritten when full. CONFIRMED
- Sources: [BlackboxMyCar A229 guide](https://www.blackboxmycar.com/pages/best-settings-for-viofo-a229-series-dash-cam),
  [Nextbase](https://nextbase.com/hub/what-is-loop-dash-cam-recording-/)

### G-sensor / event detection
- 3-axis accelerometer with **Low / Medium / High sensitivity** (medium default; low for
  highway to avoid pothole false-positives). Trigger locks the current segment.
  Event clips typically capture **10–30 s pre-trigger + post-trigger** from the
  always-running buffer. CONFIRMED
- Sources: [DDPAI](https://www.ddpai.com/blog/what-is-g-sensor-in-dash-cam/),
  [dashcamhome.com](https://dashcamhome.com/dash-cam-g-sensor-setting/)

### Parking mode (requires hardwire kit)
1. Buffered auto event detection — 15 s before + 30 s after event (Viofo numbers). CONFIRMED
2. Time-lapse — 1–10 fps, no audio, lowest power. CONFIRMED
3. Low-bitrate continuous. CONFIRMED
- Source: [Viofo parking mode guide](https://www.viofo.com/blogs/viofo-car-dash-camera-guide-faq-and-news/everything-you-need-to-know-about-parking-mode)

### Low-voltage cutoff & temperature protection
- Hardwire kits cut power at configurable voltage (BlackVue 12.0–12.5 V). CONFIRMED
- BlackVue DR900X: −20 °C to 70 °C operating range, **self-shutdown at ~75 °C**;
  supercapacitors instead of Li-ion batteries are now the norm specifically for heat
  tolerance. CONFIRMED
- Sources: [BlackVue LVC](https://forum.blackvue.com/hc/en-us/articles/49160733840921-Battery-Protection-Features-Low-Voltage-Cutoff),
  [DR900X](https://blackvue.com/product/dr900x-2ch/)

### Video format & bitrates
- **MP4, H.264 default, H.265 optional** on higher-end models (H.264 @40 Mbps ≈ H.265
  @20 Mbps). CONFIRMED
- Typical bitrates: **1080p ≈ 12–16 Mbps** (below ~10 Mbps shows artifacts),
  **1440p ≈ 24–31 Mbps**, **4K ≈ 44–60 Mbps**. CONFIRMED
- Sources: [BlackboxMyCar bitrate explainer](https://www.blackboxmycar.com/pages/dash-cam-bitrate-explained-how-does-it-affect-my-footage),
  [H.265 article](https://www.blackboxmycar.com/blogs/news/h265-the-good-the-catch-and-the-how)

### File naming & folder conventions
- Viofo: `YYYYMMDD_HHMMSS_F.MP4` / `_R.MP4`. Standard folders: **Movie** (loop),
  **Movie/RO** or **EVENT** (locked event files), **Photo**, **Parking**. "Locking" =
  the loop-overwrite pass skips the file. CONFIRMED
- Sources: [DashCamTalk folders thread](https://dashcamtalk.com/forum/threads/folders-and-filenames-on-sd-card.18485/),
  [Viofo lock videos](https://support.viofo.com/support/solutions/articles/19000129714-how-to-lock-videos-)

### Other standard hardware features
- GPS logger + speed/time stamp burned into video (togglable). CONFIRMED
- WDR/HDR for tunnel-exit/headlight dynamic range; Viofo offers an "HDR timer"
  (night-only HDR). CONFIRMED
- CPL filters (physical accessory) to kill dashboard reflection. CONFIRMED
- Emergency lock button on virtually every unit. CONFIRMED
- Voice control (Garmin, Viofo A229 Pro, Nextbase Alexa). CONFIRMED
- Nextbase 622GW: Emergency SOS, offline what3words. 70mai: ADAS voice alerts. CONFIRMED

---

## 3. Conventions that matter for this app

1. **Landscape 16:9 is the universal dashcam format** regardless of mount orientation.
   Portrait footage loses the horizontal field of view where all the evidence is.
   CONFIRMED (convention).
2. **1080p30 is the reliability sweet spot** for a phone: sustainable thermals,
   12–16 Mbps matches hardware-dashcam norms, every encoder handles it. Samsung's own
   camera app caps continuous high-res recording at 10 min for thermal reasons.
   LIKELY (synthesis; Samsung cap CONFIRMED —
   [Samsung support](https://www.samsung.com/us/support/troubleshoot/TSG10004918/)).
3. **3-minute segments** are standard because they keep single-file corruption loss
   small, keep files transferable, and give reasonable loop granularity. CONFIRMED
4. **Event files in a separate locked area** exempt from loop deletion — but the locked
   area must be budgeted/warned or it starves the loop (known real-world failure mode
   on hardware cams). CONFIRMED/LIKELY
5. **Buffered event capture**: event clips must include pre-trigger footage (10–30 s
   standard) — requires an always-running rolling buffer, not start-on-trigger. CONFIRMED
6. **High-endurance storage assumption**: loop recording is a worst-case sustained-write
   workload; standard SD cards die in 12–18 months. A phone app writes the same pattern
   to non-replaceable flash — cap loop size, prefer modest bitrates. CONFIRMED (card
   endurance), LIKELY (practical severity on modern UFS).

---

## 4. Phone-as-dashcam known problems

| Problem | Detail | Confidence |
|---|---|---|
| Heat triple-threat | Windscreen sun (dash surfaces reach 65–80 °C parked) + charging + continuous encode. Result: throttling → crashes → stopped recordings. | CONFIRMED |
| Battery swelling | Li-ion rated to ~45 °C; repeated excursions degrade electrolyte → swelling. Hardware cams moved to supercapacitors for this reason. | CONFIRMED |
| OS/OEM kills | Samsung 10-min continuous cap; OEM battery optimisers kill foreground services; Android version churn breaks background recording (DailyRoads/Android 14). | CONFIRMED |
| Storage wear | Loop recording is sustained-write worst case; wears soldered UFS/eMMC. Mitigate: cap loop size, modest bitrate, SD card where available. | CONFIRMED (workload), LIKELY (severity) |
| Mount failure | Phones are heavy; adhesives/suction weaken in heat. | CONFIRMED |
| GPS cold start without SIM | Slower fixes with no network assistance (no AGPS data). | CONFIRMED |
| Charging chemistry | Constant 100% charge + heat is worst-case Li-ion regime. | LIKELY |

Sources: [HowToGeek](https://www.howtogeek.com/i-turned-my-old-android-phone-into-a-dashcam-and-it-was-a-mistake/),
[MakeUseOf](https://www.makeuseof.com/things-i-wish-i-knew-before-using-my-old-phone-as-a-dashcam/),
[RedTiger](https://redtigercam.com/blogs/dash-cam/dash-cam-overheating),
[ATP endurance cards](https://www.atpinc.com/blog/choosing-memory-cards-for-dashcam-usage)

---

## 5. Key takeaways applied in this project

1. **Match hardware-dashcam conventions where cheap**: 1/3/5/10-min landscape 16:9
   MP4/H.264 segments at ~10–14 Mbps 1080p30, `YYYYMMDD_HHMMSS_F` naming, locked-folder
   semantics, 3-level G-sensor sensitivity with buffered pre/post event clips.
2. **The hard engineering problems are platform survival**: correctly-typed camera
   foreground service, battery-optimisation exemption guidance, and proactive thermal
   degradation (drop bitrate/resolution *before* Android kills the pipeline) — which no
   surveyed app does well. This is the app's main differentiator.
3. **Phone structural weaknesses designed around**: heat in windscreen mounts, flash
   wear (5 GB default loop cap), no viable 24/7 parking mode (documented as
   out of scope rather than promised and broken).
