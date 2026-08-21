package com.tunlezah.dashcam.domain.events

import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.EventDao
import com.tunlezah.dashcam.data.db.EventEntity
import com.tunlezah.dashcam.data.db.EventState
import com.tunlezah.dashcam.data.db.EventType
import com.tunlezah.dashcam.domain.storage.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turns a detection (or the manual protect button) into protected footage.
 *
 * Pre-event footage exists because the loop is ALWAYS recording — protection
 * is retroactive marking of segments already on disk (the rolling-buffer
 * architecture required by the brief), never reconstruction:
 *
 *  1. The event row is journalled FIRST (so a crash mid-protection is
 *     re-driven by startup recovery).
 *  2. Segments overlapping [event - preSeconds, event] are marked protected
 *     immediately — including the in-progress segment.
 *  3. After postSeconds, segments overlapping (event, event + postSeconds]
 *     are protected and the event completes.
 *
 * The recording pipeline is completely untouched — protection is metadata.
 */
class EventProtector(
    private val eventDao: EventDao,
    private val storageManager: StorageManager,
    private val diagnostics: DiagnosticsLog,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Currently latest protected-event id, for UI feedback. */
    @Volatile
    var lastEventId: Long = -1
        private set

    suspend fun protect(
        type: EventType,
        confidence: Float,
        peakMagnitude: Float,
        preSeconds: Int,
        postSeconds: Int,
        latitude: Double? = null,
        longitude: Double? = null,
        speedMps: Float? = null,
        timestampMs: Long = clock(),
    ): Long {
        val eventId = eventDao.insert(
            EventEntity(
                timestampMs = timestampMs,
                type = type,
                confidence = confidence,
                preEventSeconds = preSeconds,
                postEventSeconds = postSeconds,
                state = EventState.PENDING,
                peakMagnitude = peakMagnitude,
                latitude = latitude,
                longitude = longitude,
                speedMps = speedMps,
            )
        )
        lastEventId = eventId

        // Protect the pre-event window right away (covers the in-progress segment).
        val protectedCount =
            storageManager.protectWindow(timestampMs - preSeconds * 1000L, timestampMs, eventId)
        diagnostics.log(
            "Event",
            "$type conf=${"%.2f".format(confidence)} peak=${"%.1f".format(peakMagnitude)} " +
                "pre=${preSeconds}s/${protectedCount}seg post=${postSeconds}s pending",
        )

        // Complete the post-event window later; recovery re-drives PENDING
        // events if the process dies before this fires.
        scope.launch {
            delay(postSeconds * 1000L + GRACE_MS)
            completeEvent(eventId)
        }
        return eventId
    }

    suspend fun completeEvent(eventId: Long) {
        val event = eventDao.byId(eventId) ?: return
        if (event.state == EventState.COMPLETE) return
        storageManager.protectWindow(
            event.timestampMs,
            event.timestampMs + event.postEventSeconds * 1000L,
            eventId,
        )
        eventDao.update(event.copy(state = EventState.COMPLETE))
        diagnostics.log("Event", "event $eventId protection complete")
    }

    /**
     * Called at startup: any event still PENDING had its post-window
     * interrupted by a crash/kill — finish protecting whatever footage exists.
     */
    suspend fun recoverPendingEvents() {
        for (event in eventDao.pending()) {
            val dueAt = event.timestampMs + event.postEventSeconds * 1000L
            if (clock() >= dueAt) {
                completeEvent(event.id)
            } else {
                scope.launch {
                    delay(dueAt - clock() + GRACE_MS)
                    completeEvent(event.id)
                }
            }
        }
    }

    companion object {
        /** Small buffer so the post window includes the segment in progress at its end. */
        const val GRACE_MS = 2_000L
    }
}
