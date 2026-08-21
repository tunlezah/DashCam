package com.tunlezah.dashcam.domain.capability

import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.domain.settings.QualityMode
import com.tunlezah.dashcam.domain.settings.VideoCodec
import com.tunlezah.dashcam.domain.settings.VideoResolution

/**
 * A concrete, validated recording configuration handed to the pipeline.
 * [width]/[height] are the encoded output dimensions (after any portrait crop).
 */
data class RecordingProfile(
    val width: Int,
    val height: Int,
    val fps: Int,
    val codec: VideoCodec,
    val bitrateBps: Int,
    /** Human-readable trail of why this profile was chosen (diagnostics). */
    val rationale: String,
) {
    val estimatedBytesPerHour: Long get() = bitrateBps.toLong() / 8L * 3600L

    fun label(): String = "${height}p$fps ${codec.name} ${"%.1f".format(bitrateBps / 1_000_000.0)} Mbps"
}

/**
 * Selects the recording profile. Pure logic — unit tested against synthetic
 * capability fixtures for both reference devices.
 *
 * The Auto policy (docs/device-profiles.md, docs/benchmarking.md):
 *
 * 1. Resolution: 1080p on every tier. This is a deliberate reliability choice,
 *    not a lowest-common-denominator: 1080p30 at dashcam bitrates matches
 *    dedicated hardware dashcams, halves the thermal/storage cost of 1440p+,
 *    and is the maximum the Moto G04 supports anyway. Higher resolutions are
 *    available manually on devices whose camera AND hardware encoder support
 *    them; Auto never selects them because sustained windshield-mount thermal
 *    behaviour has not been measured on target hardware (docs/benchmarking.md).
 * 2. FPS: 30 (dashcam standard; 60 available manually where supported).
 * 3. Codec: H.264 always for Auto — universal evidence playability. HEVC is a
 *    manual "smaller files" option, permitted only with a hardware encoder.
 * 4. Bitrate: tier-scaled within the hardware-dashcam norm (docs/research:
 *    1080p ≈ 10–16 Mbps).
 *
 * Manual settings are honoured but clamped to what the hardware can do.
 */
object ProfileSelector {

    fun select(caps: DeviceCapabilities, settings: DashcamSettings, tier: DeviceTier): RecordingProfile {
        val camera = caps.camera(facingBack = settings.cameraFacing == com.tunlezah.dashcam.domain.settings.CameraFacing.BACK)
            ?: caps.cameras.firstOrNull()

        return if (settings.qualityMode == QualityMode.AUTO) {
            autoProfile(caps, camera, tier)
        } else {
            manualProfile(caps, camera, settings, tier)
        }
    }

    private fun autoProfile(
        caps: DeviceCapabilities,
        camera: CameraCapability?,
        tier: DeviceTier,
    ): RecordingProfile {
        val reasons = mutableListOf("auto mode", "tier=$tier")

        // Highest resolution Auto will use is 1080p; step down if unsupported.
        val target = bestSupported(caps, camera, VideoResolution.FHD_1080, VideoCodec.H264)
        reasons += "resolution=${target.height}p (camera+encoder verified)"

        val fps = 30
        val bitrate = autoBitrate(target, tier)
        reasons += "bitrate=${bitrate / 1_000_000} Mbps (tier-scaled dashcam norm)"
        reasons += "codec=H264 (universal playability; HEVC available manually)"

        return RecordingProfile(
            width = target.width,
            height = target.height,
            fps = fps,
            codec = VideoCodec.H264,
            bitrateBps = bitrate,
            rationale = reasons.joinToString("; "),
        )
    }

    private fun manualProfile(
        caps: DeviceCapabilities,
        camera: CameraCapability?,
        settings: DashcamSettings,
        tier: DeviceTier,
    ): RecordingProfile {
        val reasons = mutableListOf("manual mode")

        // Codec: fall back to H.264 if the requested codec has no hardware encoder.
        val codec = if (settings.manualCodec == VideoCodec.HEVC &&
            caps.hardwareEncoder(VideoCodec.HEVC.mimeType) == null
        ) {
            reasons += "HEVC requested but no hardware encoder — using H.264"
            VideoCodec.H264
        } else settings.manualCodec

        val resolution = bestSupported(caps, camera, settings.manualResolution, codec)
        if (resolution.height != settings.manualResolution.height) {
            reasons += "${settings.manualResolution.label} not supported — clamped to ${resolution.height}p"
        }

        // FPS: clamp to what the camera can sustain at fixed rate.
        val maxFps = camera?.maxFixedFps ?: 30
        val fps = settings.manualFps.coerceAtMost(maxFps).coerceAtLeast(24)
        if (fps != settings.manualFps) reasons += "fps clamped to $fps (camera max $maxFps)"

        val bitrate = if (settings.manualBitrateBps > 0) {
            settings.manualBitrateBps.also { reasons += "user bitrate" }
        } else {
            defaultBitrate(resolution, codec, fps, tier).also { reasons += "derived bitrate" }
        }

        return RecordingProfile(resolution.width, resolution.height, fps, codec, bitrate, reasons.joinToString("; "))
    }

    /**
     * Walks down from [preferred] until both the camera (recorder-surface
     * output size) and a hardware encoder support the size. Falls back to
     * 720p if nothing is verifiable (e.g. capability probe failed) — recording
     * at a modest profile beats not recording.
     */
    private fun bestSupported(
        caps: DeviceCapabilities,
        camera: CameraCapability?,
        preferred: VideoResolution,
        codec: VideoCodec,
    ): VideoResolution {
        val encoder = caps.hardwareEncoder(codec.mimeType)
            ?: caps.videoEncoders.firstOrNull { it.mimeType == codec.mimeType }
        val ordered = VideoResolution.entries
            .filter { it.ordinal <= preferred.ordinal }
            .sortedByDescending { it.ordinal }
        for (candidate in ordered) {
            val cameraOk = camera?.supportsSize(candidate.width, candidate.height) ?: false
            val encoderOk = encoder?.supports(candidate.width, candidate.height) ?: false
            if (cameraOk && encoderOk) return candidate
        }
        return VideoResolution.HD_720
    }

    private fun autoBitrate(resolution: VideoResolution, tier: DeviceTier): Int = when (resolution) {
        VideoResolution.HD_720 -> if (tier == DeviceTier.CONSTRAINED) 5_000_000 else 6_000_000
        VideoResolution.FHD_1080 -> when (tier) {
            DeviceTier.CONSTRAINED -> 10_000_000
            DeviceTier.BALANCED -> 12_000_000
            DeviceTier.CAPABLE -> 14_000_000
        }
        VideoResolution.QHD_1440 -> 24_000_000
        VideoResolution.UHD_2160 -> 45_000_000
    }

    /** Manual-mode default bitrates; HEVC gets ~65% of the H.264 rate. */
    fun defaultBitrate(resolution: VideoResolution, codec: VideoCodec, fps: Int, tier: DeviceTier): Int {
        var rate = autoBitrate(resolution, tier)
        if (fps > 30) rate = (rate * 1.5).toInt()
        if (codec == VideoCodec.HEVC) rate = (rate * 0.65).toInt()
        return rate
    }

    /**
     * One thermal step down from [profile]: bitrate first (cheapest, no
     * pipeline change), then resolution/fps. Returns null when already at the
     * floor (720p24 at minimum bitrate).
     */
    fun stepDown(profile: RecordingProfile): RecordingProfile? {
        val minBitrateFor = { h: Int -> if (h >= 1080) 6_000_000 else 3_000_000 }
        return when {
            profile.bitrateBps > minBitrateFor(profile.height) -> profile.copy(
                bitrateBps = (profile.bitrateBps * 0.7).toInt().coerceAtLeast(minBitrateFor(profile.height)),
                rationale = profile.rationale + "; thermal step-down: bitrate",
            )
            profile.fps > 30 -> profile.copy(fps = 30, rationale = profile.rationale + "; thermal step-down: fps→30")
            profile.height > 720 -> profile.copy(
                width = 1280, height = 720,
                bitrateBps = 4_000_000,
                rationale = profile.rationale + "; thermal step-down: 720p",
            )
            profile.fps > 24 -> profile.copy(fps = 24, rationale = profile.rationale + "; thermal step-down: fps→24")
            else -> null
        }
    }
}
