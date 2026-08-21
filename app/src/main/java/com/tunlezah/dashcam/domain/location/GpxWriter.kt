package com.tunlezah.dashcam.domain.location

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Streams a GPX 1.1 track to disk while recording. Track points carry speed
 * via an <extensions> block in a private namespace (GPX 1.1 removed <speed>
 * from the core schema — see docs/research/offline-maps-research.md §7).
 *
 * Written incrementally and flushed per point so a crash loses at most one
 * point; [close] appends the closing tags. A file left unterminated by a
 * crash is still readable by lenient GPX parsers, and [repairIfUnterminated]
 * completes it at startup.
 */
class GpxWriter(private val file: File) {

    private var writer: BufferedWriter? = null
    private var pointCount = 0

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun open(trackName: String) {
        val w = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
        writer = w
        w.write(
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="DashCam"
            |     xmlns="http://www.topografix.com/GPX/1/1"
            |     xmlns:dashcam="https://github.com/tunlezah/dashcam/gpx/1">
            |  <trk>
            |    <name>${trackName.escapeXml()}</name>
            |    <trkseg>
            |
            """.trimMargin()
        )
        w.flush()
    }

    fun writePoint(
        latitude: Double,
        longitude: Double,
        altitudeM: Double,
        speedMps: Float,
        timestampMs: Long,
    ) {
        val w = writer ?: return
        val ele = if (!altitudeM.isNaN()) "<ele>${"%.1f".format(Locale.US, altitudeM)}</ele>" else ""
        val speed = if (!speedMps.isNaN()) {
            "<extensions><dashcam:speed>${"%.2f".format(Locale.US, speedMps)}</dashcam:speed></extensions>"
        } else ""
        w.write(
            "      <trkpt lat=\"${"%.7f".format(Locale.US, latitude)}\" " +
                "lon=\"${"%.7f".format(Locale.US, longitude)}\">" +
                ele +
                "<time>${timeFormat.format(Date(timestampMs))}</time>" +
                speed +
                "</trkpt>\n"
        )
        w.flush()
        pointCount++
    }

    fun close() {
        val w = writer ?: return
        w.write("    </trkseg>\n  </trk>\n</gpx>\n")
        w.flush()
        w.close()
        writer = null
        // A track with no points is noise — remove it.
        if (pointCount == 0) file.delete()
    }

    companion object {
        /** Appends closing tags to a GPX file left open by a crash. */
        fun repairIfUnterminated(file: File) {
            if (!file.exists() || file.length() == 0L) return
            val tail = runCatching {
                file.inputStream().use { input ->
                    val skip = (file.length() - 64).coerceAtLeast(0)
                    input.skip(skip)
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrDefault("")
            if (!tail.contains("</gpx>")) {
                runCatching {
                    file.appendText("    </trkseg>\n  </trk>\n</gpx>\n")
                }
            }
        }
    }
}

private fun String.escapeXml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
