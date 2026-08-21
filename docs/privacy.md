# Privacy

## Guarantees

- **No cloud. No accounts. No analytics. No telemetry.** There is no crash
  reporter, no advertising SDK, and no first-party server.
- **Video never leaves the device** unless the user explicitly shares a clip
  through the system share sheet.
- **GPS data never leaves the device.** Tracks (GPX) and embedded metadata are
  optional, local, and off by default except the on-screen speed display.
- **Microphone is OFF by default.** The RECORD_AUDIO permission is not
  requested at install or first run — only when the user enables audio in
  Settings, alongside a note that recording in-vehicle conversations may
  require consent from everyone present (laws vary by Australian state).
- The only network calls in the codebase are:
  1. **Weather** (optional, user-enabled): a coordinates-only HTTPS request to
     Open-Meteo (no API key, no identifiers). Coordinates sent at city-level
     precision are inherent to a weather query; enabling weather is the
     consent for that. Cached ≥ 30 min, silently skipped offline.
  2. **Nothing else.** Map data is user-imported; no tile server is contacted.

## Permissions requested

| Permission | When | Why |
|---|---|---|
| CAMERA | first run | the dashcam |
| POST_NOTIFICATIONS | first run | the mandatory foreground-service status notification |
| ACCESS_FINE_LOCATION (+ COARSE) | first run, declinable | GPS speed/overlays/track — recording works without it |
| RECORD_AUDIO | only when the user enables audio | optional audio track |
| FOREGROUND_SERVICE + CAMERA/LOCATION/MICROPHONE types | manifest | Android 14+ typed-FGS requirement |
| WAKE_LOCK | manifest | keep the CPU alive during screen-off recording |
| INTERNET / ACCESS_NETWORK_STATE | manifest | weather only; recording has no network path |

Notably absent: no storage permissions (app-specific directories), no
background-location, no Bluetooth, no contacts/phone/SMS anything.

## Data at rest

- Recordings, GPX tracks, settings, and the segment index live in app-specific
  storage, removed on uninstall.
- Recordings are not indexed into the system gallery; sharing uses a scoped
  `FileProvider` over the export directory only.
- The diagnostics report contains device model, capability data and the
  decision log; it includes GPS fix *quality* but not coordinates. It is only
  ever exported by explicit user action through the share sheet.

## Exported surface (security review summary)

- `MainActivity` is the only exported component (launcher).
- The service, FileProvider and receivers are `exported="false"` /
  context-registered.
- No custom permissions, no content providers beyond the scoped FileProvider,
  no cleartext traffic (weather is HTTPS; nothing else talks to a network).
