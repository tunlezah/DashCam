# Testing

## Automated tests

### JVM unit tests (79, run in CI on every push)

| Area | File | What it proves |
|---|---|---|
| Settings validation | `SettingsValidatorTest` | every value clamps to safe ranges; corrupted prefs cannot create absurd configs |
| Capability scoring | `CapabilityScorerTest` | G04 fixture → CONSTRAINED, Edge fixture → CAPABLE, low-RAM penalty |
| Profile selection | `ProfileSelectorTest` | Auto = 1080p30 H.264 tier-scaled; manual clamping (4K→1080p on G04, HEVC→H.264 without HW); step-down ladder terminates at a floor; blind fallback to 720p |
| Thermal classification | `ThermalClassifierTest` | headroom/status/battery ladders; most-severe-wins merging |
| Thermal engine | `ThermalEngineTest` | immediate escalation; 60 s de-escalation hold; no flapping; settles at highest recent lower state; full simulated ladder |
| Thermal policy | `ThermalPolicyTest` | mitigations monotonic; optional features shed before video quality; stop only at platform EMERGENCY |
| Event detection | `EventDetectorTest` | synthetic traces: smooth driving silent; impact protected; pothole classified & unprotected; sensitivity levels; cooldown; sustained hard braking (regression test for the gravity-absorption bug); cradle knock |
| Storage loop | `StorageManagerTest` | minimal oldest-first eviction; protected never evicted; device reserve honoured & restored; status/remaining-time; cap clamping; window protection incl. in-progress segments |
| Startup recovery | `StartupRecoveryTest` | interrupted recordings recovered/quarantined; ghost rows dropped; orphans re-indexed (DB-loss survival); quarantine bounded |
| Orientation math | `FrameGeometryTest` | gravity→mount rotation; Camera2 relative rotation; portrait 16:9 crop geometry; even output dimensions |
| GPX | `GpxWriterTest` | valid GPX 1.1 with speed extension; empty-track cleanup; crash-repair idempotent |
| Event protection | `EventProtectorTest` | pre-window immediate (incl. boundary-spanning), post-window on virtual time, crash re-drive of PENDING events |

Run: `./gradlew :app:testDebugUnitTest`

### Instrumentation tests (device/emulator required)

`RecordingPipelineSmokeTest` verifies on real hardware that the capability
probe finds a camera + hardware AVC encoder, and that the selected Auto
profile is actually supported by that camera and encoder.

Run: `./gradlew :app:connectedDebugAndroidTest`

### What is deliberately NOT claimed

- No emulator can reproduce real thermal behaviour, OEM screen-off camera
  policy, GNSS quality, or encoder brownouts. Those are covered by the manual
  matrix below and the diagnostics tooling, and are marked *unverified on
  hardware* until executed.

## Manual test matrix (per release, on hardware)

Legend: ☐ = to run on a physical device. Record results + a diagnostics export
per cell.

### Devices
- **A: Moto G04 / Android 14** (mandatory baseline)
- **B: Edge 60 Fusion / Android 15+**
- **C: any Android 16 / API 36 device** (headroom-listener path)

### Core recording
| Test | A | B | C |
|---|---|---|---|
| First-run: permissions flow, auto-start countdown, recording begins | ☐ | ☐ | ☐ |
| 60+ min continuous loop; verify segment chain has no gaps (overlay clock across boundary) | ☐ | ☐ | ☐ |
| Segment length options 1/3/5/10 min produce expected files | ☐ | ☐ | ☐ |
| Loop eviction at cap; oldest unprotected deleted; protected retained | ☐ | ☐ | ☐ |
| Fill device storage → clean stop with clear status; reserve intact | ☐ | ☐ | ☐ |
| Kill the app mid-recording (adb shell am kill) → restart → segment recovered & playable | ☐ | ☐ | ☐ |
| Pull power mid-recording (force reboot) → current segment valid to last fragment | ☐ | ☐ | ☐ |

### Mounting & output
| Test | A | B | C |
|---|---|---|---|
| Portrait mount → upright 16:9 landscape band video, playable everywhere | ☐ | ☐ | ☐ |
| Portrait mount, full-frame option → upright 9:16 video | ☐ | ☐ | ☐ |
| Landscape mount → native 16:9, correct orientation both edges | ☐ | ☐ | ☐ |
| Overlay burn-in on/off; stamped speed/time legible; display-only mode leaves clean video | ☐ | ☐ | ☐ |

### Events
| Test | A | B | C |
|---|---|---|---|
| Manual protect → pre/post window protected; survives loop pressure | ☐ | ☐ | ☐ |
| Simulated impact (firm cradle strike at MEDIUM) protects; speed bumps at LOW do not | ☐ | ☐ | ☐ |
| Impact within 30 s of a segment boundary protects both segments | ☐ | ☐ | ☐ |

### GPS / offline
| Test | A | B | C |
|---|---|---|---|
| No SIM, airplane mode + location on: GPS fix, speed overlay, GPX written | ☐ | ☐ | ☐ |
| GPS denied/off: recording unaffected; honest "--" speed | ☐ | ☐ | ☐ |
| Weather enabled offline: overlay omitted, no retry storms (battery stats) | ☐ | ☐ | ☐ |
| Map: import state PMTiles, panel renders offline, pauses at ELEVATED (simulate) | n/a default-off | ☐ | ☐ |

### Screen / power / thermal
| Test | A | B | C |
|---|---|---|---|
| Screen off 10 min → recording continuous (check file timeline); if OEM blocks, watchdog surfaces explanation | ☐ | ☐ | ☐ |
| Unplug → configured action (default: stop after 60 s; re-plug cancels) | ☐ | ☐ | ☐ |
| Battery floor: discharge below threshold → clean stop with status | ☐ | ☐ | ☐ |
| Sun-soak / heat-gun proximity (CAUTION): ladder engages in order (weather→map→preview→bitrate→resolution), diagnostics log matches | ☐ | ☐ | ☐ |
| Mic OFF by default: no permission prompt until enabled; enabling records AAC audio | ☐ | ☐ | ☐ |
| Front camera selection records cabin view | ☐ | ☐ | ☐ |
| Theme switching incl. OLED true black | ☐ | ☐ | ☐ |
| Camera stolen by another app mid-recording → controlled recovery, no tight loop | ☐ | ☐ | ☐ |
