# Offline Maps

A secondary feature by design: the map panel sits below the live preview, is
**OFF by default** (especially on the CONSTRAINED tier), renders fully offline,
and is architecturally unable to affect recording — every map failure collapses
to a placeholder message.

## Stack (decision from `research/offline-maps-research.md`)

- **MapLibre Native Android 13.x, OpenGL artifact** (`android-sdk-opengl`).
  Chosen for GPU rendering with on-demand redraw, native `pmtiles://` support,
  BSD-2 licensing, and the healthiest maintenance of the candidates. The
  OpenGL (not default Vulkan) artifact is deliberate: Unisoc Vulkan drivers
  are historically weak, and reports of Vulkan jitter exist on budget devices.
- **PMTiles** vector extracts (Protomaps schema): single-file offline data,
  no server, no account.
- Rejected: osmdroid (archived 2024, raster-only); Mapsforge kept as a
  documented fallback (LGPL, CPU rendering cost); OpenMapTiles schema (extra
  CC-BY attribution burden).

## Getting map data (Australia)

The app ships with **no bundled map data** (it would bloat the APK by
hundreds of MB). Import a `.pmtiles` extract via **Settings → Map → Import
offline map**. Measured sizes from the Protomaps daily build:

| Extract | Detail | Size |
|---|---|---|
| All Australia | z0–15 (full) | ~2.2 GB |
| All Australia | z0–14 | ~1.1 GB |
| All Australia | z0–12 (budget; overzooms fine for a small panel) | ~242 MB |
| One state (e.g. Victoria) | z0–15 | ~150–550 MB |
| Metro area (e.g. Melbourne) | z0–15 | ~112 MB |

To produce an extract with the [pmtiles CLI](https://docs.protomaps.com/pmtiles/)
(free, no account; runs against the daily build over HTTP range requests):

```bash
# Victoria example
pmtiles extract https://build.protomaps.com/$(date +%Y%m%d).pmtiles vic.pmtiles \
  --bbox=140.9,-39.2,150.0,-33.9
# All Australia at reduced zoom (small file)
pmtiles extract https://build.protomaps.com/$(date +%Y%m%d).pmtiles au-z12.pmtiles \
  --bbox=112.5,-44.2,154.0,-9.0 --maxzoom=12
```

Copy the file to the phone (USB/Drive/etc.) and import it in Settings. The
import streams the file into the app's `maps/` directory (progress shown).

## Rendering budget (the research playbook, all implemented)

- North-up always — rotating the bearing dirties every tile every frame.
- `setMaximumFps(15)` default (5–30 configurable); MapLibre's `WHEN_DIRTY`
  refresh mode means it renders only when the camera moves.
- Camera jump-set per 1 Hz GPS fix — no continuous easing animations.
- Minimal label-free style (earth/water/roads only; no glyph assets needed →
  smaller APK, fewer draw calls) with dark/light variants following the theme.
- Gestures disabled (it's a glanceable panel, not a nav app).
- **Paused at the ELEVATED thermal state** by the mitigation ladder, with a
  visible "Map paused (device warm)" placeholder.

## Licensing & attribution

- Map data: © OpenStreetMap contributors (ODbL). Attribution is rendered on
  the panel and in the About text, per the OSMF attribution guidelines.
- Protomaps basemap schema/style: CC0. MapLibre: BSD-2.
- Downloading/bundling OSM extracts for offline use is permitted by ODbL.

## Known limitations

- MapLibre Vulkan-vs-OpenGL behaviour on the Moto G04's Mali-G57 MP1 is
  unverified on hardware; the OpenGL artifact is the conservative choice and
  the panel disables itself gracefully if GL init fails.
- No in-app region *downloader* (the Protomaps build is a 120 GB planet file;
  serving per-region extracts would require infrastructure this privacy-first
  app deliberately doesn't have). Import-a-file keeps the app honest and
  offline-first; the CLI recipe above is the supported path.
