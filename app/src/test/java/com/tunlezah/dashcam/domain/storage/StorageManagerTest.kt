package com.tunlezah.dashcam.domain.storage

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import com.tunlezah.dashcam.testutil.FakeSegmentDao
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeSegmentDao()
    private var freeBytes = 50L * GB
    private var totalBytes = 128L * GB
    private val manager = StorageManager(dao, DiagnosticsLog(), { freeBytes }, { totalBytes })

    private suspend fun addSegment(
        startMs: Long,
        sizeBytes: Long,
        protected: Boolean = false,
        state: SegmentState = SegmentState.COMPLETE,
    ): SegmentEntity {
        val file = tmp.newFile("seg_$startMs.mp4")
        file.writeBytes(ByteArray(16)) // placeholder content
        val id = dao.insert(
            SegmentEntity(
                filePath = file.absolutePath, startWallMs = startMs,
                endWallMs = startMs + 180_000, sizeBytes = sizeBytes,
                protected = protected, state = state,
                width = 1920, height = 1080, codecMime = "video/avc",
            )
        )
        return dao.byId(id)!!
    }

    @Test
    fun `evicts oldest unprotected segments to fit the loop cap`() = runTest {
        val old1 = addSegment(1_000, 2L * GB)
        val old2 = addSegment(2_000, 2L * GB)
        addSegment(3_000, 1L * GB)

        val ok = manager.ensureSpaceForNextSegment(1L * GB, loopCapBytes = 5L * GB)

        assertThat(ok).isTrue()
        // 5 GB used + 1 GB incoming > 5 GB cap → evicting the single oldest
        // (2 GB) is enough: 3 GB + 1 GB ≤ cap. Eviction is minimal, not greedy.
        assertThat(dao.byId(old1.id)).isNull()
        assertThat(dao.byId(old2.id)).isNotNull()
        assertThat(dao.loopBytes()).isEqualTo(3L * GB)
    }

    @Test
    fun `protected segments are never evicted`() = runTest {
        val protected1 = addSegment(1_000, 3L * GB, protected = true)
        val loop = addSegment(2_000, 3L * GB)

        val ok = manager.ensureSpaceForNextSegment(1L * GB, loopCapBytes = 3L * GB)

        assertThat(ok).isTrue()
        assertThat(dao.byId(protected1.id)).isNotNull()
        assertThat(dao.byId(loop.id)).isNull()
    }

    @Test
    fun `refuses to record when device free space is below the reserve`() = runTest {
        freeBytes = 500L * MB // below the 1 GiB reserve
        val ok = manager.ensureSpaceForNextSegment(300L * MB, loopCapBytes = 5L * GB)
        assertThat(ok).isFalse()
    }

    @Test
    fun `evicts loop segments to restore the device reserve`() = runTest {
        totalBytes = 16L * GB // reserve = 1 GiB
        // A filesystem model where deleting a segment frees its bytes.
        val fsDao = object : FakeSegmentDao() {
            override suspend fun delete(id: Long) {
                val row = byId(id)
                super.delete(id)
                if (row != null) freeBytes += row.sizeBytes
            }
        }
        val managerWithFs = StorageManager(fsDao, DiagnosticsLog(), { freeBytes }, { totalBytes })
        val file = tmp.newFile("fs_seg.mp4")
        fsDao.insert(
            SegmentEntity(
                filePath = file.absolutePath, startWallMs = 1_000, endWallMs = 181_000,
                sizeBytes = 2L * GB, state = SegmentState.COMPLETE,
                width = 1920, height = 1080, codecMime = "video/avc",
            )
        )
        freeBytes = 1200L * MB // reserve intact, but a 500 MB segment would breach it

        val ok = managerWithFs.ensureSpaceForNextSegment(500L * MB, loopCapBytes = 50L * GB)

        assertThat(ok).isTrue()
        assertThat(fsDao.loopBytes()).isEqualTo(0L) // the old segment was evicted
        assertThat(freeBytes).isGreaterThan(3L * GB)
    }

    @Test
    fun `safety reserve is at least 1GiB and scales with volume size`() {
        totalBytes = 8L * GB
        assertThat(manager.safetyReserveBytes()).isEqualTo(1L * GB)
        totalBytes = 256L * GB
        assertThat(manager.safetyReserveBytes()).isEqualTo(256L * GB / 20)
    }

    @Test
    fun `status reports usage remaining time and warnings`() = runTest {
        addSegment(1_000, 2L * GB)
        addSegment(2_000, 3L * GB, protected = true)
        val status = manager.status(
            loopCapBytes = 5L * GB,
            protectedBudgetBytes = 2L * GB,
            currentBitrateBps = 10_000_000,
        )
        assertThat(status.loopBytes).isEqualTo(2L * GB)
        assertThat(status.protectedBytes).isEqualTo(3L * GB)
        assertThat(status.protectedOverBudget).isTrue()
        // 3 GB loop headroom at 10 Mbps = 1.25 MB/s → ~2400 s.
        val remainingS = status.estimatedRemainingRecordingMs / 1000
        assertThat(remainingS).isGreaterThan(2000)
        assertThat(remainingS).isLessThan(3000)
    }

    @Test
    fun `effective loop cap is clamped by available device room`() = runTest {
        totalBytes = 32L * GB // reserve = 1.6 GB
        freeBytes = 3L * GB
        val cap = manager.effectiveLoopCap(30L * GB)
        // Free (3 GB) minus the 1.6 GB reserve leaves ~1.4 GB of loop room.
        assertThat(cap).isLessThan(30L * GB)
        assertThat(cap).isGreaterThan(0L)
    }

    @Test
    fun `effective loop cap is zero when free space is already below reserve`() = runTest {
        freeBytes = 500L * MB // reserve for 128 GB volume is 6.4 GB
        assertThat(manager.effectiveLoopCap(30L * GB)).isEqualTo(0L)
    }

    @Test
    fun `protectWindow marks overlapping segments including in-progress`() = runTest {
        addSegment(0, 100)                       // ends 180s — outside window
        val s2 = addSegment(200_000, 100)        // overlaps window start
        val inProgressFile = tmp.newFile("live.mp4")
        val liveId = dao.insert(
            SegmentEntity(
                filePath = inProgressFile.absolutePath, startWallMs = 360_000,
                endWallMs = null, sizeBytes = 0, state = SegmentState.RECORDING,
                width = 0, height = 0, codecMime = "",
            )
        )
        val count = manager.protectWindow(fromMs = 350_000, toMs = 400_000, eventId = 7)
        assertThat(count).isEqualTo(2) // s2 ends at 380s → overlaps; live overlaps
        assertThat(dao.byId(s2.id)!!.protected).isTrue()
        assertThat(dao.byId(liveId)!!.protected).isTrue()
        assertThat(dao.byId(1)!!.protected).isFalse()
    }

    companion object {
        private const val GB = 1024L * 1024 * 1024
        private const val MB = 1024L * 1024
    }
}
