package com.tunlezah.dashcam.domain.capability

/**
 * Everything the app learned about this device at runtime. Nothing in here is
 * derived from the model name — see docs/device-profiles.md for why (the same
 * model ships with different SoCs in different regions).
 */
data class DeviceCapabilities(
    val apiLevel: Int,
    val deviceModel: String,
    val totalRamMb: Int,
    val isLowRamDevice: Boolean,
    val cpuCores: Int,
    val maxCpuFreqKhz: Long,
    val cameras: List<CameraCapability>,
    val videoEncoders: List<EncoderCapability>,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasMagnetometer: Boolean,
    val thermalHeadroomSupported: Boolean,
    val supportsConcurrentCameras: Boolean,
    val hasRemovableStorage: Boolean,
) {
    fun camera(facingBack: Boolean): CameraCapability? =
        cameras.firstOrNull { it.facingBack == facingBack }

    fun hardwareEncoder(mimeType: String): EncoderCapability? =
        videoEncoders.firstOrNull { it.mimeType == mimeType && it.hardwareAccelerated }
}

/** Camera2 hardware levels in increasing capability order. */
enum class CameraHardwareLevel { LEGACY, EXTERNAL, LIMITED, FULL, LEVEL_3 }

data class CameraCapability(
    val cameraId: String,
    val facingBack: Boolean,
    val hardwareLevel: CameraHardwareLevel,
    val sensorOrientationDegrees: Int,
    /** Output sizes valid for a MediaCodec/MediaRecorder surface. */
    val recorderSizes: List<VideoSize>,
    /** Max fixed-rate FPS achievable across AE target ranges (e.g. 30 or 60). */
    val maxFixedFps: Int,
    val supportsVideoStabilization: Boolean,
    val supportsOpticalStabilization: Boolean,
) {
    fun supportsSize(width: Int, height: Int): Boolean =
        recorderSizes.any { it.width == width && it.height == height }
}

data class VideoSize(val width: Int, val height: Int)

data class EncoderCapability(
    val codecName: String,
    val mimeType: String,
    val hardwareAccelerated: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxSupportedInstances: Int,
    val bitrateRangeBps: LongRange,
) {
    fun supports(width: Int, height: Int): Boolean = width <= maxWidth && height <= maxHeight
}

/**
 * Coarse device tier derived from [DeviceCapabilities] by [CapabilityScorer].
 * Drives feature defaults (map, weather refresh cadence, encoder choices) —
 * never hard-coded by model name.
 */
enum class DeviceTier {
    /** Moto G04 class: ≤4 GB RAM, LIMITED camera, H.264-only hardware encode. */
    CONSTRAINED,

    /** Mid-range: comfortable 1080p30, hardware HEVC, FULL camera. */
    BALANCED,

    /** Edge 60 Fusion and above: 4K-capable encoder, headroom for extras. */
    CAPABLE,
}
