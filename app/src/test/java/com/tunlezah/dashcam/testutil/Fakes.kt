package com.tunlezah.dashcam.testutil

import com.tunlezah.dashcam.data.db.EventDao
import com.tunlezah.dashcam.data.db.EventEntity
import com.tunlezah.dashcam.data.db.EventState
import com.tunlezah.dashcam.data.db.SegmentDao
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import com.tunlezah.dashcam.domain.capability.CameraCapability
import com.tunlezah.dashcam.domain.capability.CameraHardwareLevel
import com.tunlezah.dashcam.domain.capability.DeviceCapabilities
import com.tunlezah.dashcam.domain.capability.EncoderCapability
import com.tunlezah.dashcam.domain.capability.VideoSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory SegmentDao for unit tests. */
open class FakeSegmentDao : SegmentDao {
    private val rows = MutableStateFlow<List<SegmentEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(segment: SegmentEntity): Long {
        val id = nextId++
        rows.value = rows.value + segment.copy(id = id)
        return id
    }

    override suspend fun update(segment: SegmentEntity) {
        rows.value = rows.value.map { if (it.id == segment.id) segment else it }
    }

    final override suspend fun byId(id: Long): SegmentEntity? = rows.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<SegmentEntity>> =
        rows.map { it.sortedByDescending { r -> r.startWallMs } }

    override fun observeProtected(): Flow<List<SegmentEntity>> =
        rows.map { list -> list.filter { it.protected } }

    override suspend fun inProgress(): List<SegmentEntity> =
        rows.value.filter { it.state == SegmentState.RECORDING }

    override suspend fun oldestUnprotected(limit: Int): List<SegmentEntity> =
        rows.value.filter { !it.protected && it.state != SegmentState.RECORDING }
            .sortedBy { it.startWallMs }.take(limit)

    override suspend fun loopBytes(): Long =
        rows.value.filter { !it.protected }.sumOf { it.sizeBytes }

    override suspend fun protectedBytes(): Long =
        rows.value.filter { it.protected }.sumOf { it.sizeBytes }

    override suspend fun overlapping(fromMs: Long, toMs: Long): List<SegmentEntity> =
        rows.value.filter { row ->
            val end = row.endWallMs
            row.startWallMs <= toMs && (end == null || end >= fromMs)
        }.sortedBy { it.startWallMs }

    override suspend fun protect(ids: List<Long>, eventId: Long) {
        rows.value = rows.value.map {
            if (it.id in ids) it.copy(protected = true, eventId = eventId) else it
        }
    }

    override suspend fun unprotect(id: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(protected = false, eventId = null) else it
        }
    }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    final override suspend fun all(): List<SegmentEntity> = rows.value

    override suspend fun deleteByPath(path: String) {
        rows.value = rows.value.filterNot { it.filePath == path }
    }
}

class FakeEventDao : EventDao {
    private val rows = MutableStateFlow<List<EventEntity>>(emptyList())
    private var nextId = 1L

    override suspend fun insert(event: EventEntity): Long {
        val id = nextId++
        rows.value = rows.value + event.copy(id = id)
        return id
    }

    override suspend fun update(event: EventEntity) {
        rows.value = rows.value.map { if (it.id == event.id) event else it }
    }

    override suspend fun byId(id: Long): EventEntity? = rows.value.firstOrNull { it.id == id }

    override fun observeAll(): Flow<List<EventEntity>> = rows

    override suspend fun pending(): List<EventEntity> =
        rows.value.filter { it.state == EventState.PENDING }

    override suspend fun delete(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }
}

/** Capability fixtures modelled on the research findings for the two targets. */
object DeviceFixtures {

    fun motoG04() = DeviceCapabilities(
        apiLevel = 34,
        deviceModel = "motorola moto g04",
        totalRamMb = 3700,
        isLowRamDevice = false,
        cpuCores = 8,
        maxCpuFreqKhz = 1_600_000,
        cameras = listOf(
            CameraCapability(
                cameraId = "0", facingBack = true,
                hardwareLevel = CameraHardwareLevel.LIMITED,
                sensorOrientationDegrees = 90,
                recorderSizes = listOf(VideoSize(1920, 1080), VideoSize(1280, 720), VideoSize(640, 480)),
                maxFixedFps = 30,
                supportsVideoStabilization = false,
                supportsOpticalStabilization = false,
            ),
            CameraCapability(
                cameraId = "1", facingBack = false,
                hardwareLevel = CameraHardwareLevel.LIMITED,
                sensorOrientationDegrees = 270,
                recorderSizes = listOf(VideoSize(1920, 1080), VideoSize(1280, 720)),
                maxFixedFps = 30,
                supportsVideoStabilization = false,
                supportsOpticalStabilization = false,
            ),
        ),
        videoEncoders = listOf(
            EncoderCapability(
                codecName = "c2.sprd.avc.encoder", mimeType = "video/avc",
                hardwareAccelerated = true, maxWidth = 1920, maxHeight = 1088,
                maxSupportedInstances = 1, bitrateRangeBps = 1L..40_000_000L,
            ),
            EncoderCapability(
                codecName = "c2.android.hevc.encoder", mimeType = "video/hevc",
                hardwareAccelerated = false, maxWidth = 1920, maxHeight = 1088,
                maxSupportedInstances = 1, bitrateRangeBps = 1L..20_000_000L,
            ),
        ),
        hasAccelerometer = true,
        hasGyroscope = false,
        hasMagnetometer = false,
        thermalHeadroomSupported = false,
        supportsConcurrentCameras = false,
        hasRemovableStorage = true,
    )

    fun edge60Fusion() = DeviceCapabilities(
        apiLevel = 35,
        deviceModel = "motorola edge 60 fusion",
        totalRamMb = 7800,
        isLowRamDevice = false,
        cpuCores = 8,
        maxCpuFreqKhz = 2_500_000,
        cameras = listOf(
            CameraCapability(
                cameraId = "0", facingBack = true,
                hardwareLevel = CameraHardwareLevel.FULL,
                sensorOrientationDegrees = 90,
                recorderSizes = listOf(
                    VideoSize(3840, 2160), VideoSize(2560, 1440),
                    VideoSize(1920, 1080), VideoSize(1280, 720),
                ),
                maxFixedFps = 60,
                supportsVideoStabilization = true,
                supportsOpticalStabilization = true,
            ),
        ),
        videoEncoders = listOf(
            EncoderCapability(
                codecName = "c2.mtk.avc.encoder", mimeType = "video/avc",
                hardwareAccelerated = true, maxWidth = 3840, maxHeight = 2176,
                maxSupportedInstances = 2, bitrateRangeBps = 1L..80_000_000L,
            ),
            EncoderCapability(
                codecName = "c2.mtk.hevc.encoder", mimeType = "video/hevc",
                hardwareAccelerated = true, maxWidth = 3840, maxHeight = 2176,
                maxSupportedInstances = 2, bitrateRangeBps = 1L..80_000_000L,
            ),
        ),
        hasAccelerometer = true,
        hasGyroscope = true,
        hasMagnetometer = true,
        thermalHeadroomSupported = true,
        supportsConcurrentCameras = true,
        hasRemovableStorage = true,
    )
}
