# Troubleshooting

## "App not installed as package appears to be invalid"

Three causes, in likelihood order:

1. **You installed an unsigned APK.** Builds from before the dev-signing fix
   produced `app-release-unsigned.apk`, which Android always refuses. Use a
   current build — both CI artifacts are now signed and verified in CI.
2. **You installed the artifact zip.** GitHub wraps artifacts in a `.zip`:
   extract the `.apk` from inside it first, then install that.
3. **Signature mismatch with an already-installed copy.** Dev-signed builds
   from different machines/CI runs carry different keys — uninstall the old
   app (this deletes its recordings; export anything protected first), then
   install.

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

## First: check which version you are actually running

The running version is shown in the preview's bottom-right corner and in
Diagnostics. Sideloading a build signed with a different key than the
installed one fails **silently from the launcher's point of view** — the
install errors out and the old app keeps running, so a fix can look like it
"changed nothing". Builds up to 1.0.2 were signed with a fresh key on every
CI run; from 1.0.3 the CI key is stable, so updates install normally (the
first 1.0.3 install over an older build still needs one uninstall).

## The map doesn't centre on my location

Versions up to 1.0.2 had a real bug here: the offline map's internal source
URL format was one MapLibre rejects for local files, so **no tiles ever
rendered** — the panel stayed an empty rectangle no matter what the GPS did.
Fixed in 1.0.3; map load errors now also show on the panel itself instead of
failing silently.

Beyond that: the map centres on the first GNSS fix and shows "Waiting for
GPS…" until then. First fix without a SIM can take 30+ seconds and generally
needs sky view — GNSS rarely works deep indoors. The blue centre dot is your
position (the camera follows it). GPS runs whenever the app is open, not only
while recording. If it never centres: check Location is ON and the app has
the location permission.

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
