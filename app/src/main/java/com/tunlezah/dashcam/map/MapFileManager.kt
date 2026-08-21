package com.tunlezah.dashcam.map

import android.content.Context
import android.net.Uri
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages offline map data (docs/offline-maps.md). The app ships with no map
 * data (it would add hundreds of MB to the APK); instead the user downloads
 * their Australian region in-app from [MapRegionCatalog] — one tap, with
 * progress — or imports any PMTiles extract via the system file picker.
 * Everything renders fully offline afterwards; nothing else ever touches the
 * network.
 */
class MapFileManager(
    private val context: Context,
    private val baseDir: File,
    private val diagnostics: DiagnosticsLog,
) {

    private val mapsDir: File get() = baseDir.resolve("maps").apply { mkdirs() }

    private val _activeMapFile = MutableStateFlow(findMapFile())
    val activeMapFile: StateFlow<File?> = _activeMapFile

    data class ImportProgress(
        val label: String,
        val bytesCopied: Long,
        val totalBytes: Long,
        val done: Boolean,
        val error: String?,
    )

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress

    @Volatile
    private var cancelRequested = false

    @Volatile
    var transferActive = false
        private set

    fun findMapFile(): File? =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".pmtiles") }
            ?.maxByOrNull { it.lastModified() }

    fun cancelTransfer() {
        cancelRequested = true
    }

    /**
     * Downloads a built-in region into the maps directory. Explicitly
     * user-initiated; streams to a .part file (atomic rename on completion) so
     * an interrupted download never masquerades as a usable map. This is the
     * only network download in the app besides optional weather.
     */
    suspend fun downloadRegion(region: MapRegion) = withContext(Dispatchers.IO) {
        if (transferActive) return@withContext
        transferActive = true
        cancelRequested = false
        val dest = File(mapsDir, "${region.id}.pmtiles")
        val tmp = File(mapsDir, "${region.id}.pmtiles.part")
        try {
            val connection = java.net.URL(region.url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            try {
                check(connection.responseCode in 200..299) {
                    "server returned HTTP ${connection.responseCode} — has the map-data release been published?"
                }
                val total = connection.contentLengthLong
                var copied = 0L
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        var lastPublish = 0L
                        while (true) {
                            if (cancelRequested) throw InterruptedException("cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (copied - lastPublish > 512 * 1024) {
                                lastPublish = copied
                                _importProgress.value =
                                    ImportProgress(region.label, copied, total, done = false, error = null)
                            }
                        }
                        output.fd.sync()
                    }
                }
                check(total <= 0 || copied == total) { "download incomplete ($copied of $total bytes)" }
                check(tmp.renameTo(dest)) { "rename failed" }
                // One region at a time: reclaim the space of any previous map.
                mapsDir.listFiles { f -> f.name.endsWith(".pmtiles") && f != dest }?.forEach { it.delete() }
                _importProgress.value = ImportProgress(region.label, copied, total, done = true, error = null)
                _activeMapFile.value = findMapFile()
                diagnostics.log("Map", "downloaded ${region.id} (${copied / 1024 / 1024} MB)")
            } finally {
                connection.disconnect()
            }
        } catch (e: InterruptedException) {
            tmp.delete()
            _importProgress.value = ImportProgress(region.label, 0, 0, done = true, error = "cancelled")
            diagnostics.log("Map", "download cancelled: ${region.id}")
        } catch (e: Exception) {
            tmp.delete()
            _importProgress.value = ImportProgress(region.label, 0, 0, done = true, error = e.message)
            diagnostics.log("Map", "download failed: ${e.message}")
        } finally {
            transferActive = false
        }
    }

    /** Copies a user-picked .pmtiles document into the maps directory. */
    suspend fun importFromUri(uri: Uri, displayName: String) = withContext(Dispatchers.IO) {
        val safeName = displayName.substringAfterLast('/').ifBlank { "map.pmtiles" }
            .let { if (it.endsWith(".pmtiles")) it else "$it.pmtiles" }
        val dest = File(mapsDir, safeName)
        val tmp = File(mapsDir, "$safeName.tmp")
        try {
            val total = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            var copied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        _importProgress.value = ImportProgress(safeName, copied, total, done = false, error = null)
                    }
                    output.fd.sync()
                }
            } ?: throw IllegalStateException("could not open selected file")
            check(tmp.renameTo(dest)) { "rename failed" }
            _importProgress.value = ImportProgress(safeName, copied, total, done = true, error = null)
            _activeMapFile.value = findMapFile()
            diagnostics.log("Map", "imported $safeName (${copied / 1024 / 1024} MB)")
        } catch (e: Exception) {
            tmp.delete()
            _importProgress.value = ImportProgress(safeName, 0, 0, done = true, error = e.message)
            diagnostics.log("Map", "import failed: ${e.message}")
        }
    }

    fun deleteMap(file: File) {
        file.delete()
        _activeMapFile.value = findMapFile()
    }

    /**
     * Minimal offline MapLibre style over the Protomaps basemap schema:
     * earth/water/roads only. Deliberately label-free — text layers would need
     * offline glyph assets, and a small nav panel doesn't need them. Dark and
     * light variants follow the app theme.
     */
    fun buildStyleJson(mapFile: File, dark: Boolean): String {
        val bg = if (dark) "#101418" else "#e8ecef"
        val earth = if (dark) "#161c22" else "#f4f2ec"
        val water = if (dark) "#0d2331" else "#a5c8e0"
        val minor = if (dark) "#33404c" else "#ffffff"
        val major = if (dark) "#4a5a68" else "#f6c76d"
        val highway = if (dark) "#5d6f7e" else "#f2a55f"
        return """
        {
          "version": 8,
          "name": "dashcam-offline",
          "sources": {
            "protomaps": { "type": "vector", "url": "pmtiles://${mapFile.absolutePath}" }
          },
          "layers": [
            { "id": "background", "type": "background", "paint": { "background-color": "$bg" } },
            { "id": "earth", "type": "fill", "source": "protomaps", "source-layer": "earth",
              "paint": { "fill-color": "$earth" } },
            { "id": "water", "type": "fill", "source": "protomaps", "source-layer": "water",
              "paint": { "fill-color": "$water" } },
            { "id": "roads-minor", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["in", "kind", "minor_road", "other", "path"],
              "paint": { "line-color": "$minor", "line-width": ["interpolate", ["linear"], ["zoom"], 10, 0.4, 16, 3 ] } },
            { "id": "roads-major", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["in", "kind", "major_road", "medium_road"],
              "paint": { "line-color": "$major", "line-width": ["interpolate", ["linear"], ["zoom"], 8, 0.8, 16, 5 ] } },
            { "id": "roads-highway", "type": "line", "source": "protomaps", "source-layer": "roads",
              "filter": ["==", "kind", "highway"],
              "paint": { "line-color": "$highway", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 1.0, 16, 7 ] } }
          ]
        }
        """.trimIndent()
    }
}
