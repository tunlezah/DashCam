package com.tunlezah.dashcam.domain.capability

import com.tunlezah.dashcam.domain.settings.VideoCodec

/**
 * Pure scoring logic (unit tested) that turns [DeviceCapabilities] into a
 * [DeviceTier]. The scoring is deliberately transparent and documented in
 * docs/device-profiles.md so its behaviour on the two reference devices can be
 * verified by inspection:
 *
 *  - Moto G04 (4 GB RAM, LIMITED camera, no HW HEVC, no gyro, 1.6 GHz)
 *    → score ≈ 0–2 → CONSTRAINED
 *  - Edge 60 Fusion (8–12 GB RAM, FULL camera, HW HEVC, 4K encoder, gyro)
 *    → score ≥ 8 → CAPABLE
 */
object CapabilityScorer {

    fun score(caps: DeviceCapabilities): Int {
        var score = 0

        // Memory: the strongest single predictor of "can run extras safely".
        score += when {
            caps.totalRamMb >= 7168 -> 3
            caps.totalRamMb >= 5120 -> 2
            caps.totalRamMb >= 3584 -> 1
            else -> 0
        }
        if (caps.isLowRamDevice) score -= 2

        // Camera hardware level.
        val backCamera = caps.camera(facingBack = true)
        score += when (backCamera?.hardwareLevel) {
            CameraHardwareLevel.LEVEL_3, CameraHardwareLevel.FULL -> 2
            CameraHardwareLevel.LIMITED -> 0
            else -> -1
        }

        // Hardware HEVC encoder implies a modern media block.
        if (caps.hardwareEncoder(VideoCodec.HEVC.mimeType) != null) score += 2

        // 4K-capable hardware AVC encoder implies significant encode headroom.
        val avc = caps.hardwareEncoder(VideoCodec.H264.mimeType)
        if (avc != null && avc.supports(3840, 2160)) score += 1

        // CPU: ≥2.3 GHz big cores distinguish mid-range from entry SoCs.
        if (caps.maxCpuFreqKhz >= 2_300_000) score += 1

        // 60 fps capable camera pipeline.
        if ((backCamera?.maxFixedFps ?: 0) >= 60) score += 1

        // Sensors: gyroscope enables EIS and better event classification.
        if (caps.hasGyroscope) score += 1

        return score
    }

    fun tier(caps: DeviceCapabilities): DeviceTier {
        val s = score(caps)
        return when {
            s >= 7 -> DeviceTier.CAPABLE
            s >= 3 -> DeviceTier.BALANCED
            else -> DeviceTier.CONSTRAINED
        }
    }
}
