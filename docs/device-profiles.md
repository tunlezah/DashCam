# Device Capability Profiling & the Auto Quality Algorithm

## Why runtime profiling, never model names

The Edge 60 Fusion alone ships with **two different SoCs** depending on region
(Dimensity 7300 vs 7400 — see `research/device-hardware.md`), and budget
devices ship RAM/storage variants under one name. Matching on `Build.MODEL`
would be wrong for real users, so `DeviceCapabilityProfiler` inspects the
actual hardware at startup:

| Probe | API | Used for |
|---|---|---|
| RAM + low-RAM flag | `ActivityManager.getMemoryInfo`, `isLowRamDevice` | tier score |
| CPU cores + max freq | `/sys/devices/system/cpu/*/cpufreq` | tier score |
| Camera hardware level, recorder sizes, fixed FPS ranges, stabilisation, sensor orientation | `CameraCharacteristics`, `StreamConfigurationMap.getOutputSizes(MediaRecorder)` | profile validation, geometry |
| Video encoders: mime, `isHardwareAccelerated`, max size, instances, bitrate range | `MediaCodecList` | codec/profile validation |
| Gyroscope / magnetometer / accelerometer | `SensorManager` | event-detector configuration |
| Thermal headroom support | `getThermalHeadroom` NaN probe | thermal source selection |
| Concurrent cameras | `CameraManager.concurrentCameraIds` | future dual-camera gating |
| Removable storage | `getExternalFilesDirs` + `isExternalStorageRemovable` | storage options |

**Vendor-flag scepticism:** Unisoc devices expose a "hardware" AV1 decoder that
is a repackaged software dav1d (research CONFIRMED). The profiler therefore
requires `isHardwareAccelerated()` AND treats encoder capability ranges as the
authority, and the recording engine still verifies by configuring the codec.

## Tier scoring (`CapabilityScorer`)

Transparent additive score (unit-tested against fixtures of both reference
devices):

| Signal | Points |
|---|---|
| RAM ≥ 7 GB / ≥ 5 GB / ≥ 3.5 GB | +3 / +2 / +1 |
| `isLowRamDevice` | −2 |
| Camera FULL or LEVEL_3 / LIMITED / worse | +2 / 0 / −1 |
| Hardware HEVC encoder | +2 |
| Hardware AVC encoder ≥ 4K | +1 |
| Max CPU ≥ 2.3 GHz | +1 |
| Camera fixed 60 fps | +1 |
| Gyroscope | +1 |

Tiers: score ≥ 7 → **CAPABLE**, ≥ 3 → **BALANCED**, else **CONSTRAINED**.

Reference results: Moto G04 fixture ≈ 1 → CONSTRAINED. Edge 60 Fusion fixture
≈ 11 → CAPABLE.

Tier drives *defaults for optional features* (map off on CONSTRAINED, weather
cadence, protected budget suggestions), **not** hard feature locks — users can
override, and the recording profile is validated against concrete camera and
encoder capabilities regardless of tier.

## The Auto profile algorithm (`ProfileSelector`)

> Auto means: *the best sustainable dashcam profile for this device* — not the
> maximum the spec sheet advertises.

1. **Resolution: 1080p** on every tier, stepped down (→720p) only if the
   camera or hardware encoder cannot verifiably do 1080p. Rationale:
   - 1080p30 at 10–16 Mbps matches dedicated hardware dashcams (research §
     dashcam-landscape), and is the evidence-quality sweet spot.
   - It is the Moto G04's maximum anyway.
   - On capable devices, 1440p/4K roughly doubles/quadruples encode, ISP and
     storage load in a windshield heat-trap — and sustained thermal behaviour
     on the actual devices is **unmeasured** (docs/benchmarking.md). Auto does
     not gamble; manual mode unlocks anything the hardware verifiably supports
     (4K30 on the Edge 60 Fusion).
2. **FPS: 30** — dashcam standard; 60 available manually where the camera
   reports a fixed 60 fps AE range.
3. **Codec: H.264** — universal evidence playability; no hardware HEVC on the
   G04. HEVC is a manual option permitted only with a hardware encoder
   (~35 % smaller files on the Edge).
4. **Bitrate, tier-scaled** (hardware-dashcam norms):

   | Resolution | CONSTRAINED | BALANCED | CAPABLE |
   |---|---|---|---|
   | 720p | 5 Mbps | 6 Mbps | 6 Mbps |
   | 1080p | 10 Mbps | 12 Mbps | 14 Mbps |
   | 1440p (manual) | — | 24 Mbps | 24 Mbps |
   | 4K (manual) | — | — | 45 Mbps |

   HEVC uses ~65 % of the H.264 rate. 60 fps multiplies by 1.5.

5. **Every decision is logged** with its rationale string, visible in
   Diagnostics ("Profile rationale").

Manual settings are honoured but *clamped to reality*: a 4K request on the G04
records at 1080p with the clamp noted in the rationale; HEVC without a hardware
encoder falls back to H.264 with a note.

## Thermal step-down ladder

`ProfileSelector.stepDown` produces one degradation step at a time:
bitrate −30 % (repeatable to a floor) → fps 60→30 → 720p → fps 30→24 → null
(floor). Bitrate-only steps apply **live** via
`MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)` (zero interruption);
geometry changes wait for a segment boundary and rebuild the pipeline there.

## Storage/hour at the defaults

| Profile | ≈ GB/hour | 5 GB loop holds |
|---|---|---|
| 1080p30 H.264 @ 10 Mbps (G04 Auto) | 4.5 | ~66 min |
| 1080p30 H.264 @ 14 Mbps (Edge Auto) | 6.3 | ~48 min |
| 1080p30 HEVC @ 9 Mbps (Edge manual) | 4.1 | ~74 min |
| 720p30 H.264 @ 5 Mbps (floor) | 2.25 | ~2.2 h |

These figures are arithmetic from bitrate, not measurements; container overhead
adds ~2–4 %. The 5 GB default gives roughly an hour of loop on Auto — the
brief's target commute coverage — while staying kind to non-replaceable flash.
Users with space can raise it to 30 GB+ (validated against the device reserve).
