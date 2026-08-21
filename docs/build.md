# Building DashCam

## Toolchain

| Component | Version |
|---|---|
| JDK | 21 (Temurin in CI) |
| Gradle | 9.5.0 (wrapper — `./gradlew` downloads it) |
| Android Gradle Plugin | 9.3.1 (built-in Kotlin) |
| Kotlin compiler plugins | 2.3.21 (Compose), KSP 2.3.11 (Room) |
| compileSdk | 37 (minor 37.1) · targetSdk 36 · **minSdk 34** |

Android SDK packages needed: `platforms;android-37.1`, `build-tools;36.0.0`,
`platform-tools`. Point `local.properties` at your SDK
(`sdk.dir=/path/to/android-sdk`) or set `ANDROID_HOME`.

## Commands

```bash
./gradlew :app:assembleDebug          # debug APK (sideloadable)
./gradlew :app:testDebugUnitTest      # 79 JVM unit tests
./gradlew :app:lintDebug              # Android Lint
./gradlew :app:assembleRelease        # release APK (dev-signed without secrets — sideloadable)
./gradlew :app:connectedDebugAndroidTest   # on-device smoke tests (device required)
```

Debug output: `app/build/outputs/apk/debug/app-debug.apk`. Install with
`adb install app-debug.apk` or copy to the phone and open it (enable
"install unknown apps").

## Reproducibility

All dependency versions are pinned in `gradle/libs.versions.toml`; the Gradle
wrapper pins the Gradle version; CI and local builds run identical commands.
No snapshot dependencies are used.

## CI (GitHub Actions)

`.github/workflows/build.yml` runs on every push/PR: lint → unit tests →
debug APK → release APK → signature verification (`apksigner verify`, so an
uninstallable APK fails the build) → artifact upload (`dashcam-debug-apk`,
`dashcam-release-apk`, plus lint/test reports).

**Installing a CI artifact:** GitHub packages every artifact as a `.zip` —
download it, **extract the `.apk` from inside**, copy that to the phone and
open it. Installing the zip itself (or an unsigned APK) fails with
"App not installed as package appears to be invalid".

Both artifacts sideload: the debug APK (package `com.tunlezah.dashcam.debug`)
and the release APK (package `com.tunlezah.dashcam`, minified — this is the
one to test for real-world performance). Without release secrets the release
APK is **development-signed with the runner's debug key**, which changes
between CI runs — to update across builds, uninstall the old copy first
(signature mismatch otherwise blocks the update).

`.github/workflows/release.yml` runs on `v*` tags and attaches the release APK
to a GitHub Release.

## Release signing (optional, via secrets only)

No signing keys exist in this repository and none must ever be committed
(`*.jks`, `*.keystore`, `keystore.properties` are gitignored). To produce a
signed release from CI, add repository **Actions secrets**:

| Secret | Content |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | `base64 -w0 your-release.jks` |
| `SIGNING_KEYSTORE_PASSWORD` | keystore password |
| `SIGNING_KEY_ALIAS` | key alias |
| `SIGNING_KEY_PASSWORD` | key password |

The workflow decodes the keystore to the runner's temp dir and exports
`SIGNING_KEYSTORE_FILE`; `app/build.gradle.kts` picks the config up from the
environment. Locally, the same environment variables work:

```bash
export SIGNING_KEYSTORE_FILE=/path/to/release.jks
export SIGNING_KEYSTORE_PASSWORD=… SIGNING_KEY_ALIAS=… SIGNING_KEY_PASSWORD=…
./gradlew :app:assembleRelease
```

Generate a keystore with:
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias dashcam`

Without secrets, `assembleRelease` falls back to **development-signing with
the debug key** so the release APK always installs. Supplying your own
keystore gives builds a stable identity (updates install over each other) and
is required for any real distribution.
