# Hardware Research: Moto G04 & Motorola Edge 60 Fusion

Research date: 2026-08-21.
Confidence levels used throughout: **CONFIRMED** (multiple/official sources agree),
**LIKELY** (single reputable source), **UNCERTAIN** (thin or conflicting sources).

> These findings drive the Device Capability Profile design (docs/device-profiles.md).
> Everything marked UNCERTAIN is probed at runtime rather than assumed.

---

## 1. Motorola Moto G04 (2024) — hard minimum target

Announced 2024-01-18, released 2024-02-07. Ships with Android 14, **no major OS
upgrades planned** (security patches only). — CONFIRMED
([GSMArena](https://m.gsmarena.com/motorola_moto_g04-ampp-12803.php))

### SoC / CPU / GPU

| Item | Value | Confidence |
|---|---|---|
| SoC | **Unisoc T606** (rebranded Unisoc T7250), 12 nm | CONFIRMED ([GSMArena](https://m.gsmarena.com/motorola_moto_g04-ampp-12803.php), [Unisoc T7250 official](https://www.unisoc.com/en/product/SmartPhone/T7250)) |
| CPU | Octa-core: 2× Cortex-A75 @ 1.6 GHz + 6× Cortex-A55 @ 1.6 GHz | CONFIRMED (GSMArena, Notebookcheck) |
| GPU | ARM **Mali-G57 MP1** (single core, ~650–850 MHz) | CONFIRMED (GSMArena, Unisoc official) |

Note: Unisoc's official T7250 page lists 2×A75 @ 1.8 GHz; the original T606 (and the
G04 listing) says 1.6 GHz. The G04 almost certainly runs the 1.6 GHz bin —
UNCERTAIN on exact shipping clock; plan for 1.6 GHz.

### Memory / storage

- RAM: **4 GB or 8 GB** (region dependent; many markets 4 GB only) — CONFIRMED (GSMArena)
- Storage: **64 GB or 128 GB**, GSMArena lists **UFS 2.2** (T606 platform supports both
  eMMC 5.1 and UFS 2.2, so cheap regional variants could differ) — LIKELY UFS 2.2;
  not independently verified per-variant
- microSDXC, **dedicated slot** — CONFIRMED (GSMArena, Vodafone)

### Display

6.56" **IPS LCD**, 720×1612 (HD+, 20:9, ~269 ppi), **90 Hz** — CONFIRMED (GSMArena, GSMChoice)

### Cameras

- Rear: single **16 MP, f/2.2, 1.0 µm, PDAF**, LED flash, HDR — CONFIRMED (GSMArena, Vodafone UK)
- **Max rear video: 1080p@30fps.** No 4K, no 60 fps, no gyro-EIS possible (no gyroscope) —
  CONFIRMED ([GSMArena](https://m.gsmarena.com/motorola_moto_g04-ampp-12803.php),
  [Vodafone device guide](https://deviceguides.vodafone.co.uk/motorola/moto-g04-android-14/specifications))
- Front: **5 MP f/2.2**, video 1080p@30fps — CONFIRMED (GSMArena)

### Unisoc T606 video encoder (dashcam-critical)

- Hardware encode: **H.264/AVC up to 1080p@60fps**. Unisoc's official T7250 page states
  encode "1080P @60fps"; Notebookcheck states the T606 "encodes only in H.264" while
  decoding H.265 — CONFIRMED for H.264 1080p60 encode
  ([Unisoc official](https://www.unisoc.com/en/product/SmartPhone/T7250),
  [Notebookcheck](https://www.notebookcheck.net/T606-vs-625-vs-SD-429_14018_8168_11422.247596.0.html))
- **HEVC/H.265 hardware encode: UNCERTAIN — probably absent.** No authoritative source
  shows an HEVC encoder on T606 devices. The app plans for H.264-only hardware encode and
  treats any HEVC encoder found at runtime as a bonus only if
  `MediaCodecInfo.isHardwareAccelerated()` is true (a software `c2.android.hevc.encoder`
  is always present but useless for sustained 1080p30 on this CPU).
- MediaCodec component names: Spreadtrum/Unisoc historically exposes
  `OMX.sprd.h264.encoder`; on Android 14 (Codec2) likely `c2.sprd.avc.encoder` /
  `c2.unisoc.*` — UNCERTAIN; the app probes `MediaCodecList` at runtime.
  Warning from the field: T606/ums9230 devices expose a `c2.unisoc.av1.decoder` that
  *appears* hardware but is a vendor-packaged dav1d **software** decoder
  ([NewPipe issue #10666](https://github.com/TeamNewPipe/NewPipe/issues/10666)) —
  Unisoc codec flags can mislead; the profiler must check
  `isHardwareAccelerated()` and still be sceptical.
- Budget Unisoc firmwares are known for flaky OMX/c2 behaviour (ijkplayer blacklisted
  `OMX.sprd.h264.decoder`). Expect **one reliable 1080p30 encode session**; dual-session
  or 1080p60 encode is unverified on this device.

### Camera2 hardware level

**LIKELY LIMITED.** No authoritative per-device dump found; GCam-port sites describe
partial Camera2 support typical of this class. LEGACY is unlikely on an
Android-14-launch device, but LIMITED means: no guaranteed 60 fps, constrained
concurrent-stream combos. — LIKELY, verified at runtime.

### GNSS

**GPS + GLONASS + Galileo** per GSMArena; BeiDou not listed for the phone though the
T606 chip supports it — BeiDou presence UNCERTAIN. Single-band L1 only; no dual-band
GNSS at this tier; no sensor-fusion dead reckoning (no gyro). GNSS works with no SIM.

### Sensors — key dashcam finding

- Accelerometer: **YES** — CONFIRMED
- **Gyroscope: ABSENT** — CONFIRMED by two independent sources
  ([GSMArena](https://m.gsmarena.com/motorola_moto_g04-ampp-12803.php),
  [Vodafone](https://deviceguides.vodafone.co.uk/motorola/moto-g04-android-14/specifications)).
  Impact: no gyro-EIS, no gyro-based crash detection refinement — the event detector
  must work from the accelerometer alone.
- **Compass/magnetometer: ABSENT** — CONFIRMED. Heading must be derived from the GPS track.
- Proximity: YES (may be virtual). Ambient light: UNCERTAIN, possibly absent/virtual.

### Battery / thermals / misc

- Battery: **5000 mAh**, **15 W wired** — CONFIRMED (GSMArena); in-box charger wattage
  varies by region.
- Thermals: T606 is a low-power 12 nm part; reviewers consistently report low heat and
  minimal throttling because peak performance is already modest. For continuous dashcam
  recording, the constraint is **performance headroom, not silicon heat** — though a
  windscreen mount in Australian sun changes the equation (see docs/thermal-management.md).
  — LIKELY ([cpu-monkey](https://www.cpu-monkey.com/en/cpu-unisoc_t606),
  [nanoreview](https://nanoreview.net/en/soc/unisoc-t606))
- USB-C 2.0, BT 5.0, Wi-Fi 5 dual-band, 3.5 mm jack — CONFIRMED (GSMArena)

---

## 2. Motorola Edge 60 Fusion (2025)

Announced 2025-04-02, released 2025-04-09 — CONFIRMED
([GSMArena](https://www.gsmarena.com/motorola_edge_60_fusion-13752.php))

### SoC variants — regional split (CONFIRMED)

| Variant | Regions | CPU | Node |
|---|---|---|---|
| **MediaTek Dimensity 7300** | Global (EU/LatAm/AU) | 4× Cortex-A78 @ 2.5 GHz + 4× Cortex-A55 @ 2.0 GHz | TSMC 4 nm |
| **MediaTek Dimensity 7400** | India, Indonesia, US | 4× Cortex-A78 @ 2.6 GHz + 4× A55 @ 2.0 GHz | TSMC 4 nm |

Both use **Mali-G615 MC2** GPU — CONFIRMED
([GSMArena](https://www.gsmarena.com/motorola_edge_60_fusion-13752.php),
[MediaTek 7300](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7300)).
The 7400 is essentially a rebinned 7300. **This regional split is exactly why the app
profiles capabilities at runtime instead of matching on model name.**

### Memory / storage

- 8/128, 8/256, 12/256, 12/512 GB mixes by market — CONFIRMED (GSMArena)
- UFS 2.2 + microSDXC — LIKELY (GSMArena)

### Display

6.67" quad-curved **pOLED**, 1220×2712 (446 ppi), **120 Hz**, 10-bit, HDR10+,
Gorilla Glass 7i — CONFIRMED. (OLED theme genuinely saves power on this device;
the G04's LCD does not benefit.)

### Cameras

- Main: **50 MP Sony LYT-700C**, f/1.9, 1/1.56", multi-directional PDAF, **OIS** — CONFIRMED
- Ultrawide: 13 MP f/2.2, 120°, AF — CONFIRMED
- **Rear video: 4K@30fps; 1080p@30/60 (+high-speed modes), gyro-EIS.** 4K is 30 fps max
  (Imagiq 950 ISP limit) — CONFIRMED (GSMArena)
- Front: 32 MP f/2.2, video 4K@30 / 1080p@30, gyro-EIS — CONFIRMED

### Dimensity 7300/7400 media capabilities

- **Hardware encode: H.264 AND HEVC, up to 4K30** — CONFIRMED
  ([MediaTek official](https://www.mediatek.com/products/smartphones/mediatek-dimensity-7300))
- **AV1: no hardware decode or encode** on this mid-tier part — CONFIRMED absent-from-spec
- Expected Codec2 encoders: `c2.mtk.avc.encoder`, `c2.mtk.hevc.encoder` — UNCERTAIN
  (naming convention); probed at runtime
- ISP Imagiq 950: 4K HDR record, hardware EIS, **dual simultaneous video capture at SoC
  level** — device-level support still requires runtime verification via
  `ConcurrentCamera`/`CameraManager.getConcurrentCameraIds()`

### Camera2 hardware level

LIKELY FULL (typical for Motorola midrange Edge devices; GCam-port compatibility
reports support this). Verified at runtime — could NOT be confirmed from public dumps.

### GNSS

GPS, GLONASS, Galileo confirmed; chipset also supports BeiDou/QZSS/NavIC (activation
UNCERTAIN). L1 vs L1+L5 unpublished; assume L1-only unless runtime `GnssCapabilities`
says otherwise.

### Sensors

Accelerometer, **gyroscope**, proximity, ambient light, e-compass, SAR sensor —
CONFIRMED (Motorola US official). Full sensor fusion available: better event detection
and gyro-EIS.

### Battery / charging / misc

- 5200 mAh (global) / 5500 mAh (India) — CONFIRMED
- **68 W wired TurboPower** — CONFIRMED. Fast charging is a significant heat source;
  the thermal engine treats "charging at high rate while recording" as a risk state.
- Android 15 shipped; 3 major OS upgrades + 4 years security — CONFIRMED
- IP68/IP69 + MIL-STD-810H, Wi-Fi 6E, BT 5.4 — CONFIRMED

---

## 3. Cross-cutting dashcam implications

| Concern | Moto G04 | Edge 60 Fusion |
|---|---|---|
| Max HW encode | H.264 1080p@60 (SoC); device records 1080p30 | H.264/HEVC 4K@30, 1080p60 |
| HEVC encode | assume NO | YES (hardware) |
| AV1 | decode only, vendor-wrapped **software** dav1d | none |
| Gyroscope | **ABSENT** — accel-only impact detection | present |
| Compass | ABSENT — GPS-track heading only | present |
| Camera2 level | LIKELY LIMITED | LIKELY FULL |
| GNSS | GPS/GLONASS/Galileo, L1, no dead reckoning | same + chip-level BeiDou/QZSS |
| Thermal | 12 nm, low power; perf headroom is the constraint | 4 nm, efficient; 4K30 sustained plausible but unproven |
| Storage | UFS 2.2 (likely) + dedicated microSD | UFS 2.2 + microSD |
| Display power | 90 Hz LCD — OLED black theme saves nothing | 120 Hz pOLED — true-black saves real power |

## 4. Explicitly unverified items (must be probed on-device)

1. Moto G04 gyroscope: verified ABSENT (two independent sources).
2. Moto G04 max video: verified 1080p@30fps rear and front (two sources).
3. T606 HEVC hardware encoder existence — UNVERIFIED; assume H.264-only.
4. Exact Codec2 encoder component names on both devices — probe via `MediaCodecList`.
5. Exact Camera2 hardware levels for both devices — probe via
   `CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL`.
6. GNSS L5/dual-band on Edge 60 Fusion — assume L1-only.
7. Moto G04 ambient-light sensor — possibly absent; do not depend on it.
8. Concurrent encode sessions / front+rear simultaneous capture — unknown on T606;
   SoC-level support exists on Dimensity but needs `getConcurrentCameraIds()` at runtime.

These are exactly the values the runtime `DeviceCapabilityProfiler` inspects; nothing
above is hard-coded by model name (see docs/device-profiles.md).
