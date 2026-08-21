package com.tunlezah.dashcam.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Bounded in-memory diagnostics log. Every significant decision the app makes
 * (profile selection, thermal transitions, storage evictions, recovery actions)
 * is recorded here so the diagnostics screen can show and export it.
 *
 * Deliberately not a logging framework: a ring buffer with a copy-on-read
 * snapshot is all the app needs, and it can never grow unbounded.
 */
class DiagnosticsLog(private val capacity: Int = 500) {

    data class Entry(val timestampMs: Long, val tag: String, val message: String)

    private val entries = ArrayDeque<Entry>(capacity)
    private val lock = Any()

    fun log(tag: String, message: String) {
        Log.i("DashCam/$tag", message)
        synchronized(lock) {
            if (entries.size >= capacity) entries.removeFirst()
            entries.addLast(Entry(System.currentTimeMillis(), tag, message))
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun exportText(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return snapshot().joinToString("\n") { e ->
            "${fmt.format(Date(e.timestampMs))} [${e.tag}] ${e.message}"
        }
    }
}
