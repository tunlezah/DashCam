package com.tunlezah.dashcam.domain.storage

import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.SegmentDao
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import java.io.File

/**
 * Loop storage manager. Responsibilities (docs/storage.md):
 *  - keep circulating footage under the user's loop cap (default 5 GB)
 *  - never let the whole device fill: a hard safety reserve is maintained
 *  - never auto-delete protected footage; warn when its budget is exceeded
 *  - evict PREEMPTIVELY (before each segment starts), so eviction can never
 *    block or race the recording pipeline mid-write
 *
 * Pure-ish: filesystem access goes through [File]; free-space via an
 * injectable provider so the logic is unit-testable.
 */
class StorageManager(
    private val segmentDao: SegmentDao,
    private val diagnostics: DiagnosticsLog,
    /** Returns free bytes on the volume holding the recordings. */
    private val freeBytesProvider: () -> Long,
    /** Returns total bytes of that volume. */
    private val totalBytesProvider: () -> Long,
) {

    data class StorageStatus(
        val loopBytes: Long,
        val loopCapBytes: Long,
        val protectedBytes: Long,
        val protectedBudgetBytes: Long,
        val freeDeviceBytes: Long,
        val safetyReserveBytes: Long,
        val estimatedRemainingRecordingMs: Long,
        val protectedOverBudget: Boolean,
        val lowSpace: Boolean,
    )

    /** The device-level floor the app never eats into. */
    fun safetyReserveBytes(): Long =
        maxOf(MIN_RESERVE_BYTES, totalBytesProvider() / 20) // max(1 GiB, 5% of volume)

    /**
     * Called before each new segment begins. Evicts oldest unprotected
     * segments until there is room for [estimatedSegmentBytes] within the loop
     * cap AND the device reserve holds. Returns false when recording cannot
     * continue safely (nothing left to evict and space is still short).
     */
    suspend fun ensureSpaceForNextSegment(
        estimatedSegmentBytes: Long,
        loopCapBytes: Long,
    ): Boolean {
        var loopBytes = segmentDao.loopBytes()
        val reserve = safetyReserveBytes()

        var guard = 0
        while (guard++ < MAX_EVICTIONS_PER_CALL) {
            val needsLoopEviction = loopBytes + estimatedSegmentBytes > loopCapBytes
            val needsDeviceEviction = freeBytesProvider() - estimatedSegmentBytes < reserve
            if (!needsLoopEviction && !needsDeviceEviction) return true

            val victims = segmentDao.oldestUnprotected(limit = 1)
            if (victims.isEmpty()) {
                val ok = !needsDeviceEviction
                if (!ok) diagnostics.log("Storage", "cannot evict further; free space below reserve")
                return ok
            }
            val victim = victims.first()
            deleteSegment(victim)
            loopBytes -= victim.sizeBytes
        }
        diagnostics.log("Storage", "eviction guard tripped after $MAX_EVICTIONS_PER_CALL deletions")
        return freeBytesProvider() - estimatedSegmentBytes >= reserve
    }

    suspend fun deleteSegment(segment: SegmentEntity) {
        val file = File(segment.filePath)
        val deleted = !file.exists() || file.delete()
        if (deleted) {
            segmentDao.delete(segment.id)
            diagnostics.log("Storage", "evicted ${file.name} (${segment.sizeBytes / 1024 / 1024} MB)")
        } else {
            // Deletion failure must not wedge the loop: drop the index row so the
            // recovery scan re-examines the orphan file later.
            segmentDao.delete(segment.id)
            diagnostics.log("Storage", "failed to delete ${file.name}; row dropped, file left for recovery")
        }
    }

    suspend fun status(
        loopCapBytes: Long,
        protectedBudgetBytes: Long,
        currentBitrateBps: Int,
    ): StorageStatus {
        val loopBytes = segmentDao.loopBytes()
        val protectedBytes = segmentDao.protectedBytes()
        val free = freeBytesProvider()
        val reserve = safetyReserveBytes()

        // Remaining time: the loop reuses its own space once full, so the real
        // limit is the smaller of (a) loop cap headroom and (b) device free
        // space above the reserve — after which the loop cycles indefinitely.
        val bytesPerMs = if (currentBitrateBps > 0) currentBitrateBps / 8L / 1000L else 0L
        val usableBytes = minOf(
            (loopCapBytes - loopBytes).coerceAtLeast(0),
            (free - reserve).coerceAtLeast(0),
        )
        val remainingMs = if (bytesPerMs > 0) usableBytes / bytesPerMs else 0L

        return StorageStatus(
            loopBytes = loopBytes,
            loopCapBytes = loopCapBytes,
            protectedBytes = protectedBytes,
            protectedBudgetBytes = protectedBudgetBytes,
            freeDeviceBytes = free,
            safetyReserveBytes = reserve,
            estimatedRemainingRecordingMs = remainingMs,
            protectedOverBudget = protectedBytes > protectedBudgetBytes,
            lowSpace = free < reserve * 2,
        )
    }

    /**
     * Effective loop cap: the user's setting clamped so loop + protected can
     * never squeeze the device below its reserve.
     */
    suspend fun effectiveLoopCap(userLoopCapBytes: Long): Long {
        val reserve = safetyReserveBytes()
        val protectedBytes = segmentDao.protectedBytes()
        val loopBytes = segmentDao.loopBytes()
        // Space the loop could still legitimately grow into.
        val deviceRoom = (freeBytesProvider() + loopBytes - reserve).coerceAtLeast(0)
        val clamped = minOf(userLoopCapBytes, deviceRoom)
        if (clamped < userLoopCapBytes) {
            diagnostics.log(
                "Storage",
                "loop cap clamped ${userLoopCapBytes / MB}MB -> ${clamped / MB}MB " +
                    "(free=${freeBytesProvider() / MB}MB protected=${protectedBytes / MB}MB)",
            )
        }
        return clamped
    }

    /** Marks segments overlapping the window as protected. Returns count. */
    suspend fun protectWindow(fromMs: Long, toMs: Long, eventId: Long): Int {
        val segments = segmentDao.overlapping(fromMs, toMs)
        if (segments.isEmpty()) return 0
        segmentDao.protect(segments.map { it.id }, eventId)
        diagnostics.log("Storage", "protected ${segments.size} segment(s) for event $eventId")
        return segments.size
    }

    suspend fun unprotect(segmentId: Long) {
        segmentDao.unprotect(segmentId)
    }

    companion object {
        const val MIN_RESERVE_BYTES = 1024L * 1024L * 1024L
        const val MAX_EVICTIONS_PER_CALL = 64
        private const val MB = 1024L * 1024L
    }
}
