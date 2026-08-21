package com.tunlezah.dashcam

import android.content.Context
import android.media.MediaExtractor
import android.os.StatFs
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.DashcamDatabase
import com.tunlezah.dashcam.domain.capability.CapabilityScorer
import com.tunlezah.dashcam.domain.capability.DeviceCapabilityProfiler
import com.tunlezah.dashcam.domain.events.AccelerometerFeed
import com.tunlezah.dashcam.domain.events.EventDetector
import com.tunlezah.dashcam.domain.events.EventProtector
import com.tunlezah.dashcam.domain.location.GpsManager
import com.tunlezah.dashcam.domain.location.GpxWriter
import com.tunlezah.dashcam.domain.power.PowerMonitor
import com.tunlezah.dashcam.domain.settings.SettingsRepository
import com.tunlezah.dashcam.domain.storage.MediaProbe
import com.tunlezah.dashcam.domain.storage.StartupRecovery
import com.tunlezah.dashcam.domain.storage.StorageLocations
import com.tunlezah.dashcam.domain.storage.StorageManager
import com.tunlezah.dashcam.domain.thermal.AndroidThermalSource
import com.tunlezah.dashcam.domain.thermal.ThermalEngine
import com.tunlezah.dashcam.recording.RecordingOrchestrator
import com.tunlezah.dashcam.weather.WeatherClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hand-wired dependency graph. Kept deliberately simple instead of a DI
 * framework: the object graph is small, construction order is explicit and
 * this keeps build times and APK size down on the low-end target
 * (docs/architecture.md §2).
 */
class AppGraph(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val diagnostics = DiagnosticsLog()

    val settingsRepository = SettingsRepository(context)

    val database = DashcamDatabase.build(context)

    val capabilityProfiler = DeviceCapabilityProfiler(context, diagnostics)
    val capabilities by lazy { capabilityProfiler.profile() }
    val tier by lazy { CapabilityScorer.tier(capabilities) }

    // Storage prefers the internal app-specific dir by default; the removable
    // preference is applied when the graph is (re)built at process start.
    val storageLocations = StorageLocations(context, preferRemovable = false)

    val storageManager = StorageManager(
        segmentDao = database.segmentDao(),
        diagnostics = diagnostics,
        freeBytesProvider = { statFs().availableBytes },
        totalBytesProvider = { statFs().totalBytes },
    )

    private val mediaProbe = MediaProbe { file -> probeDuration(file) }

    val startupRecovery = StartupRecovery(database.segmentDao(), storageLocations, mediaProbe, diagnostics)

    val thermalEngine = ThermalEngine(
        source = AndroidThermalSource(context, diagnostics, appScope),
        diagnostics = diagnostics,
        scope = appScope,
    )

    val gpsManager = GpsManager(context, diagnostics)
    val powerMonitor = PowerMonitor(context, diagnostics)
    val accelerometerFeed = AccelerometerFeed(context)
    val eventDetector = EventDetector()
    val eventProtector = EventProtector(database.eventDao(), storageManager, diagnostics, appScope)
    val weatherClient = WeatherClient(context, diagnostics)
    val mapFileManager = com.tunlezah.dashcam.map.MapFileManager(context, storageLocations.baseDir, diagnostics)

    val orchestrator by lazy {
        RecordingOrchestrator(
            context = context,
            scope = appScope,
            diagnostics = diagnostics,
            settingsRepository = settingsRepository,
            capabilities = capabilities,
            tier = tier,
            storageManager = storageManager,
            storageLocations = storageLocations,
            segmentDao = database.segmentDao(),
            thermalEngine = thermalEngine,
            gpsManager = gpsManager,
            powerMonitor = powerMonitor,
            accelerometerFeed = accelerometerFeed,
            eventDetector = eventDetector,
            eventProtector = eventProtector,
            weatherClient = weatherClient,
        )
    }

    fun initialize() {
        appScope.launch(Dispatchers.IO) {
            // Repair anything a previous crash left behind before recording starts.
            startupRecovery.run()
            storageLocations.tracksDir.listFiles()?.forEach { GpxWriter.repairIfUnterminated(it) }
        }
        orchestrator.start()
        diagnostics.log("App", "graph initialized; tier=$tier")
    }

    private fun statFs() = StatFs(storageLocations.baseDir.absolutePath)

    private fun probeDuration(file: File): Long? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    duration = maxOf(duration, format.getLong(android.media.MediaFormat.KEY_DURATION) / 1000)
                }
            }
            if (duration > 0) duration else null
        } finally {
            extractor.release()
        }
    }.getOrNull()
}
