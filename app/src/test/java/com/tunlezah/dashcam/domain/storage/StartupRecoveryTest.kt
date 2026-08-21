package com.tunlezah.dashcam.domain.storage

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import com.tunlezah.dashcam.testutil.FakeSegmentDao
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StartupRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dao = FakeSegmentDao()
    private lateinit var loopDir: File
    private lateinit var protectedDir: File
    private lateinit var quarantineDir: File

    /** Fake probe: files containing "GOOD" are readable with 90 s duration. */
    private val probe = MediaProbe { file ->
        if (file.readBytes().decodeToString().contains("GOOD")) 90_000L else null
    }

    private lateinit var recovery: StartupRecovery

    @Before
    fun setUp() {
        loopDir = tmp.newFolder("loop")
        protectedDir = tmp.newFolder("protected")
        quarantineDir = tmp.newFolder("quarantine")
        recovery = StartupRecovery(dao, loopDir, protectedDir, quarantineDir, probe, DiagnosticsLog())
    }

    private suspend fun insertRow(file: File, state: SegmentState): Long = dao.insert(
        SegmentEntity(
            filePath = file.absolutePath, startWallMs = 1_000_000, endWallMs = null,
            sizeBytes = file.length(), state = state,
            width = 1920, height = 1080, codecMime = "video/avc",
        )
    )

    @Test
    fun `interrupted recording with readable file is recovered`() = runTest {
        val file = File(loopDir, "20260821_100000_F.mp4").apply { writeText("GOOD fmp4 data") }
        val id = insertRow(file, SegmentState.RECORDING)

        val result = recovery.run()

        assertThat(result.recovered).isEqualTo(1)
        val row = dao.byId(id)!!
        assertThat(row.state).isEqualTo(SegmentState.RECOVERED)
        assertThat(row.endWallMs).isEqualTo(1_000_000 + 90_000)
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `interrupted recording with unreadable file is quarantined`() = runTest {
        val file = File(loopDir, "20260821_100000_F.mp4").apply { writeText("garbage") }
        val id = insertRow(file, SegmentState.RECORDING)

        val result = recovery.run()

        assertThat(result.quarantined).isEqualTo(1)
        assertThat(dao.byId(id)).isNull()
        assertThat(file.exists()).isFalse()
        assertThat(File(quarantineDir, file.name).exists()).isTrue()
    }

    @Test
    fun `rows whose files vanished are dropped`() = runTest {
        val ghost = File(loopDir, "gone.mp4")
        val id = insertRow(ghost, SegmentState.COMPLETE)

        val result = recovery.run()

        assertThat(result.droppedRows).isEqualTo(1)
        assertThat(dao.byId(id)).isNull()
    }

    @Test
    fun `orphan files are re-indexed so footage survives a destroyed database`() = runTest {
        File(loopDir, "20260821_110000_F.mp4").writeText("GOOD orphan")
        File(protectedDir, "20260821_120000_F.mp4").writeText("GOOD protected orphan")

        val result = recovery.run()

        assertThat(result.reindexed).isEqualTo(2)
        val all = dao.all()
        assertThat(all).hasSize(2)
        assertThat(all.count { it.protected }).isEqualTo(1)
        assertThat(all.all { it.state == SegmentState.RECOVERED }).isTrue()
    }

    @Test
    fun `unreadable orphans are quarantined not indexed`() = runTest {
        File(loopDir, "junk.mp4").writeText("zero bytes of sense")

        val result = recovery.run()

        assertThat(result.reindexed).isEqualTo(0)
        assertThat(result.quarantined).isEqualTo(1)
        assertThat(dao.all()).isEmpty()
    }

    @Test
    fun `quarantine is bounded by file count`() = runTest {
        repeat(StartupRecovery.MAX_QUARANTINE_FILES + 5) { i ->
            File(loopDir, "junk_$i.mp4").writeText("garbage $i")
        }
        recovery.run()
        val quarantined = quarantineDir.listFiles()?.size ?: 0
        assertThat(quarantined).isAtMost(StartupRecovery.MAX_QUARANTINE_FILES)
    }
}
