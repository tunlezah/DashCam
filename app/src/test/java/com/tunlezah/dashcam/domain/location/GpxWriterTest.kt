package com.tunlezah.dashcam.domain.location

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpxWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes a valid gpx track with speed extension`() {
        val file = tmp.newFile("track.gpx")
        val writer = GpxWriter(file)
        writer.open("Test drive")
        writer.writePoint(-37.8136, 144.9631, 31.5, 16.67f, 1_766_300_000_000)
        writer.writePoint(-37.8140, 144.9640, 32.0, 17.20f, 1_766_300_001_000)
        writer.close()

        val text = file.readText()
        assertThat(text).contains("<gpx version=\"1.1\"")
        assertThat(text).contains("lat=\"-37.8136000\"")
        assertThat(text).contains("lon=\"144.9631000\"")
        assertThat(text).contains("<ele>31.5</ele>")
        assertThat(text).contains("<dashcam:speed>16.67</dashcam:speed>")
        assertThat(text).contains("</trkseg>")
        assertThat(text.trim()).endsWith("</gpx>")
    }

    @Test
    fun `empty track file is deleted on close`() {
        val file = tmp.newFile("empty.gpx")
        val writer = GpxWriter(file)
        writer.open("Empty")
        writer.close()
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `crash-truncated track is repaired at startup`() {
        val file = tmp.newFile("crashed.gpx")
        val writer = GpxWriter(file)
        writer.open("Crashed drive")
        writer.writePoint(-37.0, 145.0, 10.0, 5f, 1_766_300_000_000)
        // No close(): simulates process death. Points are flushed per write.
        val before = file.readText()
        assertThat(before).doesNotContain("</gpx>")

        GpxWriter.repairIfUnterminated(file)

        val after = file.readText()
        assertThat(after).contains("<trkpt")
        assertThat(after.trim()).endsWith("</gpx>")
    }

    @Test
    fun `repair leaves complete files untouched`() {
        val file = tmp.newFile("complete.gpx")
        val writer = GpxWriter(file)
        writer.open("Done")
        writer.writePoint(-37.0, 145.0, 10.0, 5f, 1_766_300_000_000)
        writer.close()
        val before = file.readText()

        GpxWriter.repairIfUnterminated(file)

        assertThat(file.readText()).isEqualTo(before)
    }
}
