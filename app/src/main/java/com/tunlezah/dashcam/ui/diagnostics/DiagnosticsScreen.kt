package com.tunlezah.dashcam.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.ui.LocalAppGraph
import kotlinx.coroutines.delay

/**
 * Live diagnostics for field testing (brief §42): device, profile, thermal,
 * GPS, storage, encoder counters, plus the decision log — exportable as plain
 * text via the share sheet. Contains no precise location (rounded to ~1 km)
 * unless the user includes it themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val status by graph.orchestrator.status.collectAsStateWithLifecycle()
    val thermal by graph.thermalEngine.snapshot.collectAsStateWithLifecycle()
    val gps by graph.gpsManager.state.collectAsStateWithLifecycle()
    val power by graph.powerMonitor.state.collectAsStateWithLifecycle()
    val settings by graph.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = DashcamSettings())

    var storage by remember { mutableStateOf<com.tunlezah.dashcam.domain.storage.StorageManager.StorageStatus?>(null) }
    var logText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            storage = graph.storageManager.status(
                settings.loopMaxBytes, settings.protectedBudgetBytes,
                status.activeProfile?.bitrateBps ?: 0,
            )
            logText = graph.diagnostics.exportText()
            delay(2000)
        }
    }

    val caps = graph.capabilities
    val rows: List<Pair<String, String>> = buildList {
        add(
            "App version" to "${com.tunlezah.dashcam.BuildConfig.VERSION_NAME} " +
                "(${com.tunlezah.dashcam.BuildConfig.VERSION_CODE})"
        )
        add("Device" to caps.deviceModel)
        add("Android" to "API ${caps.apiLevel}")
        add("Tier" to graph.tier.name)
        add("RAM" to "${caps.totalRamMb} MB${if (caps.isLowRamDevice) " (low-RAM)" else ""}")
        add("CPU" to "${caps.cpuCores} cores @ ${caps.maxCpuFreqKhz / 1000} MHz")
        add("Cameras" to caps.cameras.joinToString { "${if (it.facingBack) "rear" else "front"}:${it.hardwareLevel}" })
        add("HW encoders" to caps.videoEncoders.filter { it.hardwareAccelerated }
            .joinToString { "${it.mimeType.substringAfter('/')}≤${it.maxWidth}x${it.maxHeight}" }
            .ifEmpty { "none found" })
        add("Gyroscope" to if (caps.hasGyroscope) "yes" else "no (accel-only event detection)")
        add("State" to status.state.name)
        add("Profile" to (status.activeProfile?.label() ?: "—"))
        add("Output" to "${status.outputWidth}x${status.outputHeight}")
        add("Profile rationale" to (status.profile?.rationale ?: "—"))
        add("Segments written" to status.segmentsWritten.toString())
        add("Frames rendered" to status.framesRendered.toString())
        add("Microphone" to if (status.micActive) "recording" else "off")
        add("Thermal state" to thermal.state.name)
        add(
            "Thermal signals" to
                "headroom=${if (thermal.headroom.isNaN()) "n/a" else "%.2f".format(thermal.headroom)} " +
                "status=${thermal.platformStatus} batt=${if (thermal.batteryTempC.isNaN()) "n/a" else "%.1f°C".format(thermal.batteryTempC)} " +
                "src=${thermal.source}"
        )
        add("GPS" to "${gps.quality} sats ${gps.satellitesUsed}/${gps.satellitesVisible} " +
            "acc=${if (gps.accuracyM.isNaN()) "n/a" else "%.0fm".format(gps.accuracyM)}")
        add("Battery" to "${power.batteryPercent}% ${if (power.isCharging) "charging" else "discharging"} " +
            "${if (power.batteryTempC.isNaN()) "" else "%.1f°C".format(power.batteryTempC)}")
        storage?.let {
            add("Loop storage" to "%.2f / %.2f GB".format(it.loopBytes / 1e9, it.loopCapBytes / 1e9))
            add("Protected" to "%.2f GB (budget %.0f GB)".format(it.protectedBytes / 1e9, it.protectedBudgetBytes / 1e9))
            add("Free device" to "%.2f GB (reserve %.2f GB)".format(it.freeDeviceBytes / 1e9, it.safetyReserveBytes / 1e9))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnostics") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val report = buildString {
                            appendLine("DashCam diagnostics report")
                            appendLine("Generated ${java.util.Date()}")
                            appendLine()
                            rows.forEach { (k, v) -> appendLine("$k: $v") }
                            appendLine()
                            appendLine("--- Decision log ---")
                            appendLine(logText)
                        }
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, report)
                        }
                        context.startActivity(Intent.createChooser(send, "Export diagnostics"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export report")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            items(rows.size) { i ->
                val (key, value) = rows[i]
                Row(Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.4f))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "Decision log",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    logText.ifEmpty { "(empty)" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
