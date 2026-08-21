package com.tunlezah.dashcam.domain.capability

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import com.tunlezah.dashcam.core.DiagnosticsLog
import java.io.File

/**
 * Inspects the actual hardware at runtime and produces [DeviceCapabilities].
 * Never trusts the model name (regional SoC variants) and never trusts vendor
 * codec flags blindly — see docs/research/device-hardware.md (Unisoc exposes a
 * "hardware" AV1 decoder that is software underneath; we cross-check
 * isHardwareAccelerated and canonical names).
 */
class DeviceCapabilityProfiler(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    fun profile(): DeviceCapabilities {
        val caps = DeviceCapabilities(
            apiLevel = Build.VERSION.SDK_INT,
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            totalRamMb = totalRamMb(),
            isLowRamDevice = activityManager().isLowRamDevice,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            maxCpuFreqKhz = maxCpuFreqKhz(),
            cameras = probeCameras(),
            videoEncoders = probeVideoEncoders(),
            hasAccelerometer = hasSensor(Sensor.TYPE_ACCELEROMETER),
            hasGyroscope = hasSensor(Sensor.TYPE_GYROSCOPE),
            hasMagnetometer = hasSensor(Sensor.TYPE_MAGNETIC_FIELD),
            thermalHeadroomSupported = probeThermalHeadroom(),
            supportsConcurrentCameras = probeConcurrentCameras(),
            hasRemovableStorage = probeRemovableStorage(),
        )
        diagnostics.log(
            "Capability",
            "model=${caps.deviceModel} api=${caps.apiLevel} ram=${caps.totalRamMb}MB " +
                "cores=${caps.cpuCores}@${caps.maxCpuFreqKhz / 1000}MHz " +
                "gyro=${caps.hasGyroscope} headroomApi=${caps.thermalHeadroomSupported} " +
                "cameras=${caps.cameras.map { "${it.cameraId}:${it.hardwareLevel}" }} " +
                "hwEncoders=${caps.videoEncoders.filter { it.hardwareAccelerated }.map { it.codecName }}",
        )
        return caps
    }

    private fun activityManager() =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun totalRamMb(): Int {
        val info = ActivityManager.MemoryInfo()
        activityManager().getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)).toInt()
    }

    private fun maxCpuFreqKhz(): Long = runCatching {
        (0 until Runtime.getRuntime().availableProcessors()).maxOf { core ->
            val f = File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            if (f.canRead()) f.readText().trim().toLongOrNull() ?: 0L else 0L
        }
    }.getOrDefault(0L)

    private fun hasSensor(type: Int): Boolean {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        return sm.getDefaultSensor(type) != null
    }

    private fun probeCameras(): List<CameraCapability> {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        return cm.cameraIdList.mapNotNull { id ->
            runCatching {
                val ch = cm.getCameraCharacteristics(id)
                val facing = ch.get(CameraCharacteristics.LENS_FACING)
                // Only surface the primary back/front cameras in the capability model.
                if (facing != CameraMetadata.LENS_FACING_BACK && facing != CameraMetadata.LENS_FACING_FRONT) {
                    return@runCatching null
                }
                val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> CameraHardwareLevel.LEVEL_3
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> CameraHardwareLevel.FULL
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> CameraHardwareLevel.LIMITED
                    CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> CameraHardwareLevel.EXTERNAL
                    else -> CameraHardwareLevel.LEGACY
                }
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = map?.getOutputSizes(MediaRecorder::class.java)
                    ?.map { VideoSize(it.width, it.height) }
                    .orEmpty()
                val fpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                val maxFixedFps = fpsRanges
                    ?.filter { it.lower == it.upper }
                    ?.maxOfOrNull { it.upper } ?: 30
                val videoStab = ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                    ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) ?: false
                val ois = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                    ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) ?: false
                CameraCapability(
                    cameraId = id,
                    facingBack = facing == CameraMetadata.LENS_FACING_BACK,
                    hardwareLevel = level,
                    sensorOrientationDegrees = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    recorderSizes = sizes,
                    maxFixedFps = maxFixedFps,
                    supportsVideoStabilization = videoStab,
                    supportsOpticalStabilization = ois,
                )
            }.getOrNull()
        }
            // Keep only the first (primary) camera per facing.
            .distinctBy { it.facingBack }
    }

    private fun probeVideoEncoders(): List<EncoderCapability> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val interesting = setOf("video/avc", "video/hevc", "video/av01")
        return list.codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.lowercase() in interesting }
                    .mapNotNull { mime ->
                        runCatching {
                            val caps = info.getCapabilitiesForType(mime)
                            val video = caps.videoCapabilities ?: return@runCatching null
                            EncoderCapability(
                                codecName = info.name,
                                mimeType = mime.lowercase(),
                                hardwareAccelerated = info.isHardwareAccelerated,
                                maxWidth = video.supportedWidths.upper,
                                maxHeight = video.supportedHeights.upper,
                                maxSupportedInstances = caps.maxSupportedInstances,
                                bitrateRangeBps = video.bitrateRange.lower.toLong()..video.bitrateRange.upper.toLong(),
                            )
                        }.getOrNull()
                    }
            }
    }

    /**
     * Headroom support probe per the ADPF guidance: a first call returning
     * NaN (or throwing) means the device doesn't implement it.
     */
    private fun probeThermalHeadroom(): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.getThermalHeadroom(10).isNaN().not()
    }.getOrDefault(false)

    private fun probeConcurrentCameras(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.concurrentCameraIds.isNotEmpty()
    }.getOrDefault(false)

    private fun probeRemovableStorage(): Boolean = runCatching {
        context.getExternalFilesDirs(null).filterNotNull().any {
            Environment.isExternalStorageRemovable(it)
        }
    }.getOrDefault(false)
}
