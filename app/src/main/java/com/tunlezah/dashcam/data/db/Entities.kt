package com.tunlezah.dashcam.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Lifecycle state of a recorded segment file. */
enum class SegmentState {
    /** Currently being written by the muxer. */
    RECORDING,

    /** Finalized cleanly. */
    COMPLETE,

    /** Found after a crash/restart and verified readable. */
    RECOVERED,

    /** Found after a crash/restart and NOT readable; quarantined. */
    CORRUPT,
}

@Entity(
    tableName = "segments",
    indices = [Index("startWallMs"), Index("protected"), Index("state")],
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Absolute path of the segment file. */
    val filePath: String,
    /** Wall-clock time the first frame of this segment was captured. */
    val startWallMs: Long,
    /** Wall-clock end; null while recording. */
    val endWallMs: Long?,
    val sizeBytes: Long,
    val protected: Boolean = false,
    /** Event that caused protection, if any. */
    val eventId: Long? = null,
    val state: SegmentState,
    val width: Int,
    val height: Int,
    val codecMime: String,
    val frontCamera: Boolean = false,
)

enum class EventType { IMPACT, HARD_BRAKING, POTHOLE, SPEED_BUMP, MANUAL, DEVICE_MOVEMENT }

enum class EventState {
    /** Detected; post-event window still recording. */
    PENDING,

    /** All pre/post footage protected. */
    COMPLETE,
}

@Entity(tableName = "events", indices = [Index("timestampMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val type: EventType,
    /** 0.0–1.0 detector confidence (1.0 for manual protection). */
    val confidence: Float,
    val preEventSeconds: Int,
    val postEventSeconds: Int,
    val state: EventState,
    /** Peak acceleration magnitude (m/s²) that triggered the event, 0 for manual. */
    val peakMagnitude: Float = 0f,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speedMps: Float? = null,
)
