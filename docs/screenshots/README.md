# Screenshots

No screenshots are committed yet: this project was built in a headless CI-style
environment without an Android emulator/device capable of rendering the UI, and
per the project's no-fabrication rule, mock images are not passed off as
screenshots.

## Reproducible capture instructions

On any device/emulator (API 34+):

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.tunlezah.dashcam.debug android.permission.CAMERA
adb shell pm grant com.tunlezah.dashcam.debug android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.tunlezah.dashcam.debug android.permission.POST_NOTIFICATIONS

capture() { adb exec-out screencap -p > "docs/screenshots/$1.png"; }

adb shell am start -n com.tunlezah.dashcam.debug/com.tunlezah.dashcam.ui.MainActivity
sleep 2 && capture 01-main-initialising
sleep 5 && capture 02-main-recording          # after the startup countdown
# tap Protect, then:
capture 03-protected-event
# navigate to Settings:
capture 04-settings
# Settings → Storage section visible:
capture 05-storage
# Diagnostics screen:
capture 06-diagnostics
# Library:
capture 07-library
# Themes: switch in Settings → Display → Theme and re-capture the main screen:
capture 08-theme-dark
capture 09-theme-oled
capture 10-theme-light
# Thermal warning: on a debug build, warm the device or use the simulated
# thermal source hooks in ThermalEngineTest as a reference; the chip and
# "reduced quality" badge appear at HIGH.
# Map mode: import a .pmtiles file (docs/offline-maps.md), enable the map:
capture 11-map-mode
```

Name files as above so the README/table can link them once captured on
hardware.
