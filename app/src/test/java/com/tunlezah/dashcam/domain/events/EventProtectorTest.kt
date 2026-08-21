package com.tunlezah.dashcam.domain.events

import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.EventState
import com.tunlezah.dashcam.data.db.EventType
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import com.tunlezah.dashcam.domain.storage.StorageManager
import com.tunlezah.dashcam.testutil.FakeEventDao
import com.tunlezah.dashcam.testutil.FakeSegmentDao
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventProtectorTest {

    private val segmentDao = FakeSegmentDao()
    private val eventDao = FakeEventDao()
    private val storage = StorageManager(segmentDao, DiagnosticsLog(), { 50L shl 30 }, { 128L shl 30 })

    private suspend fun segment(startMs: Long, endMs: Long?): Long = segmentDao.insert(
        SegmentEntity(
            filePath = "/tmp/does-not-matter-$startMs.mp4", startWallMs = startMs, endWallMs = endMs,
            sizeBytes = 100, state = if (endMs == null) SegmentState.RECORDING else SegmentState.COMPLETE,
            width = 1920, height = 1080, codecMime = "video/avc",
        )
    )

    @Test
    fun `event protects pre-window immediately and post-window after the delay`() = runTest {
        // Timeline (seconds): segments [0-180], [180-360], live [360-...].
        val s1 = segment(0, 180_000)
        val s2 = segment(180_000, 360_000)
        val live = segment(360_000, null)

        val protector = EventProtector(
            eventDao, storage, DiagnosticsLog(), this, clock = { 400_000L },
        )
        // Event at t=400s with 30s pre / 60s post.
        val eventId = protector.protect(
            type = EventType.IMPACT, confidence = 0.9f, peakMagnitude = 42f,
            preSeconds = 30, postSeconds = 60, timestampMs = 400_000,
        )
        runCurrent()

        // Pre-window [370s, 400s]: only the live segment overlaps.
        assertThat(segmentDao.byId(live)!!.protected).isTrue()
        assertThat(segmentDao.byId(s1)!!.protected).isFalse()
        assertThat(segmentDao.byId(s2)!!.protected).isFalse()
        assertThat(eventDao.byId(eventId)!!.state).isEqualTo(EventState.PENDING)

        // A new segment begins during the post window…
        val postSegment = segment(420_000, null)

        // …after the post window elapses the event completes and covers it.
        advanceTimeBy(60_000 + EventProtector.GRACE_MS + 1000)
        runCurrent()
        assertThat(eventDao.byId(eventId)!!.state).isEqualTo(EventState.COMPLETE)
        assertThat(segmentDao.byId(postSegment)!!.protected).isTrue()
    }

    @Test
    fun `pre-window spanning a segment boundary protects the earlier segment too`() = runTest {
        val s1 = segment(0, 180_000)
        val s2 = segment(180_000, null)
        val protector = EventProtector(eventDao, storage, DiagnosticsLog(), this, clock = { 190_000L })

        // Event at 190s with 30s pre: window [160s, 190s] spans both segments —
        // the "impact at a segment boundary" case from the brief's final review.
        protector.protect(
            type = EventType.MANUAL, confidence = 1f, peakMagnitude = 0f,
            preSeconds = 30, postSeconds = 30, timestampMs = 190_000,
        )
        runCurrent()

        assertThat(segmentDao.byId(s1)!!.protected).isTrue()
        assertThat(segmentDao.byId(s2)!!.protected).isTrue()
    }

    @Test
    fun `pending events are completed by startup recovery`() = runTest {
        val live = segment(100_000, null)
        var now = 100_000L
        val protector = EventProtector(eventDao, storage, DiagnosticsLog(), this, clock = { now })
        val eventId = protector.protect(
            type = EventType.IMPACT, confidence = 0.9f, peakMagnitude = 40f,
            preSeconds = 30, postSeconds = 60, timestampMs = 100_000,
        )
        runCurrent()
        assertThat(eventDao.byId(eventId)!!.state).isEqualTo(EventState.PENDING)

        // Simulate: process died before the post-window timer fired; on restart
        // the clock is already past the due time.
        now = 500_000L
        protector.recoverPendingEvents()
        runCurrent()

        assertThat(eventDao.byId(eventId)!!.state).isEqualTo(EventState.COMPLETE)
        assertThat(segmentDao.byId(live)!!.protected).isTrue()
    }
}
