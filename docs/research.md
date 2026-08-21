# Research Index

All implementation decisions trace back to research performed against current
(2026) authoritative sources — Android Developers documentation, AndroidX
release notes, manufacturer specifications, and the dashcam industry. Every
document marks claims as CONFIRMED / LIKELY / UNCERTAIN, and everything
UNCERTAIN is probed at runtime rather than assumed.

| Document | Scope |
|---|---|
| [research/device-hardware.md](research/device-hardware.md) | Moto G04 & Edge 60 Fusion: SoCs, cameras, encoders, sensors (including the G04's missing gyroscope), GNSS, thermals, charging |
| [research/android-media-apis.md](research/android-media-apis.md) | CameraX vs Camera2/MediaCodec for gapless recording, Android 14–16 foreground-service rules, thermal APIs, Media3, storage APIs, offline GNSS, codecs, battery monitoring, keep-alive |
| [research/dashcam-landscape.md](research/dashcam-landscape.md) | Android dashcam apps, dedicated hardware dashcams, industry conventions, phone-as-dashcam failure modes |
| [research/offline-maps-research.md](research/offline-maps-research.md) | MapLibre vs Mapsforge vs osmdroid, PMTiles, measured Australian extract sizes, ODbL licensing, GPX conventions |

The decisions derived from this research are recorded in:

- [architecture.md](architecture.md) — decision records with rejected alternatives
- [feature-research.md](feature-research.md) — feature matrix and scope decisions
- [device-profiles.md](device-profiles.md) — the Auto quality algorithm
- [thermal-management.md](thermal-management.md) — the mitigation ladder

Key research-driven decisions at a glance:

1. **Camera2 + persistent MediaCodec, not CameraX** — CameraX Recorder cannot
   rotate output files without a stop/start gap; a dashcam cannot have a
   recurring blind spot at every segment boundary.
2. **Fragmented MP4 on disk, classic MP4 on export** — crash durability for the
   segment being written during a crash (exactly the one that matters), with
   universal-compatibility remux for sharing.
3. **H.264 default everywhere** — the Moto G04's Unisoc T606 has no hardware
   HEVC encoder, and H.264 is what insurers/police/old laptops can play. HEVC
   is opt-in where hardware-accelerated.
4. **Accelerometer-only event detection** — the Moto G04 has no gyroscope
   (CONFIRMED by two independent sources); the detector is designed around
   that constraint rather than assuming sensors it may not have.
5. **1080p30 Auto profile** — the hardware-dashcam reliability sweet spot;
   the G04's maximum anyway; and unmeasured windshield thermal behaviour argues
   against 1440p+ defaults on any device (manual override available).
6. **No headless auto-start, no parking mode** — platform restrictions
   (camera FGS background-start ban) and physics (windshield heat) make honest
   documentation better than broken promises.
