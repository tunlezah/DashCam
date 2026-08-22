package com.tunlezah.dashcam.map

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class MapStyleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun manager() = MapFileManager(
        ApplicationProvider.getApplicationContext(),
        tmp.root,
        DiagnosticsLog(),
    )

    /**
     * MapLibre Native's LocalFileSource only accepts URLs that start with
     * "file://" (local_file_source.cpp, android-v13.5.0); a bare absolute
     * path inside "pmtiles://" is rejected with "Invalid file URL" and the
     * map silently renders nothing but the background. This is the exact bug
     * behind "the map doesn't go to your location" in field testing — pin
     * the correct scheme so it can never regress.
     */
    @Test
    fun `style source url uses the pmtiles-over-file scheme MapLibre requires`() {
        val mapFile = File("/data/user/0/com.tunlezah.dashcam/files/maps/au-tas.pmtiles")
        val json = manager().buildStyleJson(mapFile, dark = false)
        val style = Json.parseToJsonElement(json).jsonObject
        val url = style["sources"]!!.jsonObject["protomaps"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertThat(url).isEqualTo("pmtiles://file://${mapFile.absolutePath}")
    }

    @Test
    fun `style is valid json with the expected layers in both themes`() {
        val mapFile = File(tmp.root, "x.pmtiles")
        for (dark in listOf(true, false)) {
            val style = Json.parseToJsonElement(manager().buildStyleJson(mapFile, dark)).jsonObject
            assertThat(style["version"]!!.jsonPrimitive.content).isEqualTo("8")
            val layerIds = style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertThat(layerIds).containsExactly(
                "background", "earth", "water", "roads-minor", "roads-major", "roads-highway",
            ).inOrder()
        }
    }
}
