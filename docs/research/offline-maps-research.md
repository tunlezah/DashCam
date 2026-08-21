# Offline Mapping Stack Research

Research date: 2026-08-21.
Confidence levels: **CONFIRMED** / **LIKELY** / **UNCERTAIN** as defined in
`device-hardware.md`. The implementation decision derived from this research lives in
`docs/offline-maps.md`.

**Context:** Kotlin / Jetpack Compose, min API 34, portrait dashcam UI with a small
secondary live map, fully offline (no SIM), hardware down to Unisoc T606
(Mali-G57 MP1, 4 GB RAM), Australia coverage.

---

## 1. MapLibre Native Android

- **Current version:** `android-v13.5.0` (2026-08-13) — active, roughly monthly
  releases. CONFIRMED — [GitHub releases](https://github.com/maplibre/maplibre-native/releases)
- **PMTiles: supported natively** since 11.7.0 — point a `VectorSource` at
  `pmtiles:///absolute/path/file.pmtiles`; 13.3.0 added an ambient cache for PMTiles.
  CONFIRMED — [official example](https://maplibre.org/maplibre-native/android/examples/data/PMTiles/)
- **MBTiles: supported natively** via `mbtiles://` absolute paths (no `asset://` —
  copy out of APK first; open issue
  [#3559](https://github.com/maplibre/maplibre-native/issues/3559)). CONFIRMED
- **Rendering backend:** breaking change in 13.0.0 — **Vulkan is the default** for
  `org.maplibre.gl:android-sdk`; OpenGL ES remains as `org.maplibre.gl:android-sdk-opengl`;
  13.5.0 added a `multiBackend` flavor. Reported Vulkan lag on some devices
  ([#4130](https://github.com/maplibre/maplibre-native/issues/4130)); Unisoc Vulkan
  drivers are weak — **test both backends; keep OpenGL as the escape hatch**.
  CONFIRMED (backend change) / LIKELY (OpenGL safer on Unisoc)
- **GPU/battery:** default refresh mode `WHEN_DIRTY` (render only on demand);
  `MapView.setMaximumFps()` caps frame rate; `LocationComponent.setMaxAnimationFps()`
  throttles the puck. A capped-FPS, small-viewport map is a modest GPU load. CONFIRMED —
  [RenderingRefreshMode docs](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.maps.renderer/-map-renderer/-rendering-refresh-mode/index.html)
- **Compose:** first-party **MapLibre Compose** (`org.maplibre.compose:maplibre-compose`),
  v0.13–0.14, pre-1.0 (API churn possible); `AndroidView` + classic `MapView` is the
  conservative alternative. CONFIRMED — [maplibre.org/maplibre-compose](https://maplibre.org/maplibre-compose/)
- **Footprint:** ~5–10 MB per ABI (native `.so`). UNCERTAIN against 13.x artifacts.
- **License:** BSD 2-Clause. OSM data attribution still applies (§5). CONFIRMED

## 2. Mapsforge / VTM

- Actively maintained (VTM 0.28.0, 2026-04-30). Maven Central publishing paused at
  OSSRH EOL — currently distributed via **JitPack**. CONFIRMED
- Mapsforge classic renders **on CPU** (Canvas) — zero GPU dependence, ideal when GPU
  drivers are suspect, but continuous panning burns CPU (thermal cost on the T606).
  VTM is the OpenGL sibling. CONFIRMED
- Australia files (OpenAndroMaps, 2026-07): NSW-VIC 553 MB, QLD 374 MB, TAS 188 MB,
  West-North 185 MB, South 149 MB, West-South 147 MB, NT 138 MB → **~1.73 GB total**.
  CONFIRMED — [openandromaps.org](https://www.openandromaps.org/en/downloads/australia-newzealand-oceania)
- Compose interop: View-based only (`AndroidView`). CONFIRMED
- License: **LGPL v3** — legally awkward for closed-source Android apps. CONFIRMED

## 3. Offline map data for Australia (measured, not guessed)

Measured with `pmtiles extract --dry-run` against the Protomaps daily build of
2026-08-20 (planet 120 GB, z0–15). CONFIRMED:

| Extract (bbox) | Zoom | Size |
|---|---|---|
| All Australia | z0–15 (full detail) | **2.2 GB** |
| All Australia | z0–14 | **1.1 GB** |
| All Australia | z0–12 | **242 MB** |
| Victoria | z0–15 | **521 MB** |
| Melbourne metro | z0–15 | **112 MB** |

Each zoom level roughly doubles size; MapLibre overzooms vector tiles gracefully, so a
z0–13/14 extract still renders street-level views acceptably for a small secondary map.

Other sources: Geofabrik `australia-latest.osm.pbf` = 914 MB raw (feed to
Planetiler/tilemaker to self-build MBTiles). CONFIRMED —
[download.geofabrik.de](https://download.geofabrik.de/australia-oceania.html)

## 4. osmdroid

**Repository archived (read-only) 2024-11-20 — development ended.** Raster-only
offline would need tens of GB for Australia. **Not suitable.** CONFIRMED —
[osmdroid repo](https://github.com/osmdroid/osmdroid)

## 5. Licensing (ODbL / attribution)

- OSM data is ODbL; rendered maps are "Produced Works" — attribution
  "© OpenStreetMap contributors" adjacent to the map or on an about screen is
  required and sufficient. Bundling/downloading extracts for offline use is fully
  permitted. CONFIRMED — [OSMF Attribution Guidelines](https://osmfoundation.org/wiki/Licence/Attribution_Guidelines)
- **Protomaps basemaps:** style CC0, code BSD-3; only "© OpenStreetMap" is mandatory.
  CONFIRMED — [LICENSE_DATA.md](https://github.com/protomaps/basemaps/blob/main/LICENSE_DATA.md)
- **OpenMapTiles:** schema CC-BY 4.0 → requires an extra visible "© OpenMapTiles"
  attribution. Protomaps is the lower-friction choice. LIKELY
- MapLibre BSD-2 imposes no UI attribution; Mapsforge/VTM LGPLv3 imposes
  source/relink obligations instead.

## 6. Performance guidance for a live moving map

No rigorous published MapLibre-vs-Mapsforge benchmark exists (UNCERTAIN — only
anecdotes). Documented strategies, all supported by MapLibre APIs:

1. **Cap frame rate**: `setMaximumFps(15–20)`; GPS updates arrive at 1 Hz anyway.
2. **Keep `WHEN_DIRTY`** and drive redraws from GPS updates only.
3. **No animation**: jump-set the camera per fix; throttle puck animation.
4. **Static north-up beats rotating**: bearing changes dirty every tile every frame;
   north-up redraws only the symbol layer. LIKELY (mechanism confirmed by design docs).
5. **Small viewport, fixed zoom**: fewer tiles resident, no LOD churn.
6. **Strip the style**: roads + water + place labels only; layer count drives draw calls.
7. **Pause aggressively** when the map is hidden.
8. On the T606: ship OpenGL (or multiBackend) and A/B against Vulkan on-device.

## 7. GPX & dashcam GPS sidecar conventions

- **GPX 1.1** (`http://www.topografix.com/GPX/1/1`):
  `<gpx><trk><trkseg><trkpt lat lon><ele><time>`. `<speed>`/`<course>` were **removed
  in 1.1** — carry speed via an `<extensions>` block in a private namespace.
  CONFIRMED — [topografix.com/GPX/1/1](https://www.topografix.com/GPX/1/1/)
- **Dashcam convention:** the de-facto sidecar is an **NMEA-0183 log named identically
  to the video file** ($GPRMC + $GPGGA at 1 Hz) — used by BlackVue/VIOFO/Novatek;
  players auto-pair by filename. GPX is the standard *export/interchange* format.
  CONFIRMED — [Dashcam Viewer](https://dashcamviewer.com/features/),
  [DashCamTalk NMEA thread](https://dashcamtalk.com/forum/threads/format-of-gps-data.7333/)

---

## Recommendation

### Primary: MapLibre Native Android + Protomaps PMTiles extract

Stack: MapLibre Native 13.x via `AndroidView` (classic `MapView`, frozen API) or the
first-party Compose wrapper; a per-state or all-Australia **PMTiles** file on internal
storage via the native `pmtiles://` protocol; Protomaps basemap style stripped to
roads + water + place labels.

Why: GPU rendering with on-demand redraw + FPS cap is the cheapest way to run a
continuously-updating map; single-file offline data, zero server infrastructure;
BSD-2 + CC0 licensing with only OSM attribution; healthiest maintenance trajectory;
all-Australia at 1.1–2.2 GB or ~250 MB at z12.

Risks & mitigations: Vulkan-by-default on Mali-G57 MP1/Unisoc is the main unknown —
validate on-device; fall back to the OpenGL artifact. Pre-1.0 Compose wrapper —
pin or use `AndroidView`.

### Fallback: Mapsforge classic (CPU) + OpenAndroMaps `.map` files

Immune to GPU driver bugs; costs LGPLv3 obligations, JitPack distribution, and higher
CPU/thermal load while moving.

### Ship strategy

Bundle nothing. On first use (on Wi-Fi), the user downloads their state
(~150–550 MB), with an all-Australia option. The map is OFF by default on the
low-end capability tier and never allowed to compromise recording.

### Avoid

osmdroid (archived, raster-only). OpenMapTiles-schema tiles (extra attribution
requirement) unless self-building with Planetiler.
