package com.tunlezah.dashcam.domain.settings

/** How recording quality is chosen. */
enum class QualityMode { AUTO, MANUAL }

/** Video resolutions offered in Settings; availability is gated by the capability profile. */
enum class VideoResolution(val width: Int, val height: Int, val label: String) {
    HD_720(1280, 720, "720p"),
    FHD_1080(1920, 1080, "1080p"),
    QHD_1440(2560, 1440, "1440p"),
    UHD_2160(3840, 2160, "4K");
}

enum class VideoCodec(val mimeType: String, val label: String) {
    H264("video/avc", "H.264 (most compatible)"),
    HEVC("video/hevc", "H.265/HEVC (smaller files)");
}

enum class EventSensitivity { LOW, MEDIUM, HIGH }

enum class OverlayMode {
    /** Overlays are composited into the recorded video (burned in). */
    STAMP,

    /** Overlays are shown on screen only; the recorded video is clean. */
    DISPLAY_ONLY,
}

enum class PlugInAction { NONE, START_RECORDING, PROMPT }

enum class UnplugAction { CONTINUE, STOP_IMMEDIATELY, STOP_AFTER_DELAY, BATTERY_SAVER_PROFILE }

enum class AppTheme { SYSTEM, LIGHT, DARK, OLED }

enum class CameraFacing { BACK, FRONT }

/**
 * The complete, validated application settings model. Persisted via DataStore;
 * every field has a deliberate default chosen for the Moto G04 baseline —
 * see docs/architecture.md and docs/device-profiles.md.
 */
data class DashcamSettings(
    // Recording
    val qualityMode: QualityMode = QualityMode.AUTO,
    val manualResolution: VideoResolution = VideoResolution.FHD_1080,
    val manualFps: Int = 30,
    val manualCodec: VideoCodec = VideoCodec.H264,
    val manualBitrateBps: Int = 0, // 0 = derive from resolution/codec
    val segmentMinutes: Int = 3,
    val startupDelaySeconds: Int = 3,
    val autoStartOnLaunch: Boolean = true,

    // Events
    val eventDetectionEnabled: Boolean = true,
    val eventSensitivity: EventSensitivity = EventSensitivity.MEDIUM,
    val preEventSeconds: Int = 30,
    val postEventSeconds: Int = 60,

    // Storage
    val loopMaxBytes: Long = 5L * GIB,
    val protectedBudgetBytes: Long = 2L * GIB,
    val preferRemovableStorage: Boolean = false,

    // Overlays
    val overlayMode: OverlayMode = OverlayMode.STAMP,
    val overlaySpeed: Boolean = true,
    val overlayGpsCoordinates: Boolean = false,
    val overlayDate: Boolean = true,
    val overlayTime: Boolean = true,
    val overlayWeather: Boolean = false,
    val overlayCustomLabel: String = "",

    // GPS
    val gpsEnabled: Boolean = true,
    val gpsEmbedInVideoMetadata: Boolean = false,
    val gpsWriteGpxTrack: Boolean = false,
    val gpsUpdateIntervalMs: Long = 1000,

    // Audio — OFF by default (privacy; permission requested only when enabled)
    val microphoneEnabled: Boolean = false,

    // Camera. Frames are always recorded sensor-native with standard MP4
    // rotation metadata — no rotation/crop options to get wrong.
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val stabilizationEnabled: Boolean = true,

    // Power
    val plugInAction: PlugInAction = PlugInAction.START_RECORDING,
    val unplugAction: UnplugAction = UnplugAction.STOP_AFTER_DELAY,
    val unplugStopDelaySeconds: Int = 60,
    val batteryFloorPercent: Int = 20,

    // Display
    val keepScreenOn: Boolean = true,
    val allowScreenOffRecording: Boolean = true,
    val theme: AppTheme = AppTheme.SYSTEM,

    // Map (secondary feature; never allowed to compromise recording)
    val mapEnabled: Boolean = false,
    val mapMaxFps: Int = 15,

    // Weather (optional; recording never depends on it)
    val weatherEnabled: Boolean = false,
) {
    companion object {
        const val GIB: Long = 1024L * 1024L * 1024L
    }
}
