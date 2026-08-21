package com.tunlezah.dashcam.domain.storage

import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.SegmentDao
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import java.io.File

/**
 * Probes whether a media file is readable/playable. Production implementation
 * uses MediaExtractor; tests inject a fake.
 */
fun interface MediaProbe {
    /** Returns duration in ms if the file is a readable video, or null. */
    fun probeDurationMs(file: File): Long?
}

/**
 * Startup recovery pass (docs/architecture.md §8). Reconciles the segment
 * index with the filesystem after any crash, kill or reboot:
 *
 *  - index rows whose file vanished → dropped
 *  - rows stuck in RECORDING → file probed; readable → RECOVERED (fragmented
 *    MP4 is valid up to the last complete fragment), else CORRUPT + quarantined
 *  - files on disk with no row (index lost) → probed and re-indexed, so video
 *    survives even a destroyed database
 *
 * Quarantine is bounded: oldest quarantined files beyond the cap are deleted.
 */
class StartupRecovery(
    private val segmentDao: SegmentDao,
    private val locations: StorageLocations,
    private val probe: MediaProbe,
    private val diagnostics: DiagnosticsLog,
) {

    data class Result(val recovered: Int, val quarantined: Int, val reindexed: Int, val droppedRows: Int)

    suspend fun run(): Result {
        var recovered = 0
        var quarantined = 0
        var reindexed = 0
        var droppedRows = 0

        val known = segmentDao.all()
        val knownPaths = known.map { it.filePath }.toMutableSet()

        for (row in known) {
            val file = File(row.filePath)
            if (!file.exists()) {
                segmentDao.delete(row.id)
                droppedRows++
                continue
            }
            if (row.state == SegmentState.RECORDING) {
                val duration = probe.probeDurationMs(file)
                if (duration != null && duration > 0 && file.length() > 0) {
                    segmentDao.update(
                        row.copy(
                            state = SegmentState.RECOVERED,
                            endWallMs = row.startWallMs + duration,
                            sizeBytes = file.length(),
                        )
                    )
                    recovered++
                    diagnostics.log("Recovery", "recovered ${file.name} (${duration / 1000}s)")
                } else {
                    quarantine(file)
                    segmentDao.delete(row.id)
                    quarantined++
                }
            }
        }

        // Orphan files (no index row) in loop/ and protected/.
        for ((dir, isProtected) in listOf(locations.loopDir to false, locations.protectedDir to true)) {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }?.forEach { file ->
                if (file.absolutePath in knownPaths) return@forEach
                val duration = probe.probeDurationMs(file)
                if (duration != null && duration > 0) {
                    segmentDao.insert(
                        SegmentEntity(
                            filePath = file.absolutePath,
                            startWallMs = file.lastModified() - duration,
                            endWallMs = file.lastModified(),
                            sizeBytes = file.length(),
                            protected = isProtected,
                            state = SegmentState.RECOVERED,
                            width = 0, height = 0, codecMime = "",
                            frontCamera = file.name.endsWith("_C.mp4"),
                        )
                    )
                    knownPaths.add(file.absolutePath)
                    reindexed++
                    diagnostics.log("Recovery", "re-indexed orphan ${file.name}")
                } else {
                    quarantine(file)
                    quarantined++
                }
            }
        }

        trimQuarantine()
        val result = Result(recovered, quarantined, reindexed, droppedRows)
        diagnostics.log(
            "Recovery",
            "done: recovered=$recovered quarantined=$quarantined reindexed=$reindexed dropped=$droppedRows",
        )
        return result
    }

    private fun quarantine(file: File) {
        val target = locations.quarantineDir.resolve(file.name)
        val moved = file.renameTo(target)
        if (!moved) file.delete()
        diagnostics.log("Recovery", "quarantined unreadable ${file.name}")
    }

    private fun trimQuarantine() {
        val files = locations.quarantineDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val excess = files.size - MAX_QUARANTINE_FILES
        if (excess > 0) files.take(excess).forEach { it.delete() }
        val totalBytes = files.sumOf { it.length() }
        if (totalBytes > MAX_QUARANTINE_BYTES) {
            var toFree = totalBytes - MAX_QUARANTINE_BYTES
            for (f in files) {
                if (toFree <= 0) break
                toFree -= f.length()
                f.delete()
            }
        }
    }

    companion object {
        const val MAX_QUARANTINE_FILES = 10
        const val MAX_QUARANTINE_BYTES = 512L * 1024 * 1024
    }
}
