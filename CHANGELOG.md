# Changelog

## 1.0.0 (unreleased)

Initial release.

- Gapless loop recording (Camera2 + persistent MediaCodec, keyframe-aligned
  fragmented-MP4 segments; 1/3/5/10 min, default 3 min)
- Automatic device capability profiling and Auto quality selection
  (1080p30 H.264, tier-scaled bitrate); manual resolution/fps/codec/bitrate
- Thermal engine: headroom forecasting + status + battery-temp fallback,
  hysteresis, graduated mitigation ladder, simulation harness
- Accelerometer event detection (impact / pothole / speed bump / hard braking /
  cradle movement) with 30 s pre / 60 s post protection and manual protect
- Storage loop management: 5 GB default cap, device safety reserve, protected
  footage budget with warnings, startup recovery + quarantine
- GPS (GNSS-only, no SIM needed): speed/coordinate overlays, GPX tracks,
  optional video-metadata location
- Overlay burn-in (speed, date/time, GPS, weather, custom label) or
  display-only mode
- Optional offline map (MapLibre + user-imported PMTiles), off by default
- Optional weather (Open-Meteo), cached, never required
- Optional microphone (OFF by default; permission requested only on enable)
- Power behaviours: plug/unplug actions, battery floor, charging status
- Screen-off recording with watchdog fallback for restrictive OEMs
- Compose UI: portrait dashcam main screen, settings, recordings library with
  share/export (classic-MP4 remux), ExoPlayer playback, diagnostics with
  report export, Light/Dark/System/OLED themes
- 79 JVM unit tests + on-device smoke tests; GitHub Actions build producing
  sideloadable APKs
