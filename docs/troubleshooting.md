# Troubleshooting

## Recording stops when the screen turns off

Framework support for screen-off camera recording is real (typed foreground
service + wake lock, both implemented), but **some OEM power managers or
camera HALs stop capture anyway**. The app's watchdog notices within ~5
seconds and attempts recovery; if the OEM keeps blocking:

1. Keep "Keep screen on while recording" enabled (default) — the preview
   detaches under thermal load anyway.
2. Exempt DashCam from battery optimisation: Settings → Apps → DashCam →
   Battery → Unrestricted. See [dontkillmyapp.com](https://dontkillmyapp.com)
   for vendor-specific steps.
3. Motorola devices are generally close to stock AOSP behaviour and are the
   least affected class.

## The app doesn't start recording by itself when I plug it in

Android 14+ **forbids** starting a camera service from the background — no
app can honestly do this. What works:
- Recording auto-starts when you **open the app** (default, ~3 s countdown).
- Plugging in power **while the app is open** auto-starts (default setting).

## The phone gets hot

Expected physics: windshield sun + charging + video encoding. The app manages
itself (see the thermal chip on the main screen: Warm → Elevated → High), but
you can help:
- Mount out of direct sunlight where legal/practical; use the dash rather than
  glass when possible.
- Avoid fast chargers in summer — a slow (5–10 W) charger generates far less
  heat and still nets positive charge at dashcam load.
- Enable screen-off recording, or let the thermal manager detach the preview.
- If the status reaches "Stopped: device too hot", the platform declared an
  emergency; footage up to that point is finalized and safe.

## Speed shows "--" or "~"

- "--": no GPS fix yet. First fix without a SIM can take 30+ seconds outdoors
  (there is no network assistance by design — nothing is downloaded).
- "~": fix accuracy is poor (> 25 m); the speed is displayed but marked
  approximate rather than pretending precision.
- No SIM is required, but Location must be ON and the app needs the
  location permission. Recording continues regardless.

## Files won't play on an old Windows PC

Loop segments are fragmented MP4 (crash-durable). Use the in-app **Share**
button, which exports a maximally-compatible classic MP4. Modern players
(VLC, Windows 11 Media Player, anything ExoPlayer/ffmpeg-based) play the raw
files directly.

## Windscreen reflections wash out the image

A physical problem no software fixes: dashboard reflections on the glass.
Hardware dashcams use CPL (circular polariser) filters; clip-on phone CPL
filters exist and help dramatically. A dark, non-reflective dash mat also works.

## A recording is missing after a crash/reboot

Check the Library — segments recovered at startup are tagged "recovered".
Unreadable files are moved to quarantine (visible via diagnostics counts)
rather than silently deleted. If the database was lost entirely, footage on
disk is re-indexed automatically at next launch.

## Storage fills up

The loop never exceeds its cap and never touches the device safety reserve
(≥ 1 GB). If storage fills anyway, the culprit is usually **protected footage**
(never auto-deleted) — the main screen warns when it exceeds its budget.
Review Library → Protected and unprotect/delete/share old events.

## Event detection fires on speed bumps / misses events

Adjust sensitivity (Settings → Incident detection): LOW for highway commutes,
HIGH for city. The detector is an assistance mechanism — for anything
important, use the manual Protect button; it grabs the previous 30 s too.
