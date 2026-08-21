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
./gradlew :app:assembleRelease        # release APK (unsigned without secrets)
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
debug APK → release APK → artifact upload (`dashcam-debug-apk`,
`dashcam-release-apk`, plus lint/test reports). Download the debug APK from
the workflow run's Artifacts section — it is signed with the standard Android
debug key and sideloads directly.

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

Without secrets, `assembleRelease` produces `app-release-unsigned.apk` —
useful for CI verification; sideload the **debug** APK for testing instead.
