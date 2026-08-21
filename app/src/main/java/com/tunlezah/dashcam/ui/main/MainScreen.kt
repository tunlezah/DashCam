package com.tunlezah.dashcam.ui.main

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunlezah.dashcam.domain.location.GpsFixQuality
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.domain.thermal.ThermalState
import com.tunlezah.dashcam.recording.RecorderState
import com.tunlezah.dashcam.recording.RecordingService
import com.tunlezah.dashcam.ui.LocalAppGraph
import com.tunlezah.dashcam.ui.theme.DashCamColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * The main dashcam screen, designed for a portrait-mounted phone:
 * status chips → dominant live preview → speed/storage panel → controls.
 * Controls are large (≥56 dp) for use while stationary; the layout stays
 * glanceable and does not invite interaction while driving.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val status by graph.orchestrator.status.collectAsStateWithLifecycle()
    val settings by graph.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = DashcamSettings())
    val gps by graph.gpsManager.state.collectAsStateWithLifecycle()
    val thermal by graph.thermalEngine.snapshot.collectAsStateWithLifecycle()
    val power by graph.powerMonitor.state.collectAsStateWithLifecycle()

    var storageStatus by remember { mutableStateOf<com.tunlezah.dashcam.domain.storage.StorageManager.StorageStatus?>(null) }
    LaunchedEffect(settings.loopMaxBytes, status.segmentsWritten) {
        while (true) {
            storageStatus = graph.storageManager.status(
                settings.loopMaxBytes, settings.protectedBudgetBytes,
                status.activeProfile?.bitrateBps ?: 0,
            )
            delay(5000)
        }
    }

    val isRecording = status.state == RecorderState.RECORDING ||
        status.state == RecorderState.RECORDING_DEGRADED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        StatusChipRow(status, gpsQuality = gps.quality, thermal = thermal.state, batteryPercent = power.batteryPercent, charging = power.isCharging, micActive = status.micActive)

        // --- Live preview: the dominant element ---
        val aspect = status.outputWidth.toFloat() / status.outputHeight.coerceAtLeast(1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .aspectRatio(aspect.coerceIn(0.5f, 2.2f))
                .background(Color.Black),
        ) {
            CameraPreview()
            if (!isRecording) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = when (status.state) {
                            RecorderState.COUNTDOWN -> "Starting in ${status.countdownSeconds}…"
                            RecorderState.INITIALIZING -> "Initialising…"
                            RecorderState.RECOVERING -> "Recovering…"
                            RecorderState.STOPPED_STORAGE -> "Stopped — storage full"
                            RecorderState.STOPPED_THERMAL -> "Stopped — device too hot"
                            RecorderState.STOPPED_BATTERY -> "Stopped — low battery"
                            RecorderState.STOPPED_ERROR -> "Stopped — ${status.statusMessage}"
                            else -> "Ready"
                        },
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            RecordingBadge(isRecording, status.recordingStartMs, degraded = status.state == RecorderState.RECORDING_DEGRADED, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SpeedPanel(gps.speedKmh, gps.quality, settings)
            storageStatus?.let { StoragePanel(it) }
        }

        ControlBar(
            isRecording = isRecording,
            countdown = status.state == RecorderState.COUNTDOWN,
            onRecordToggle = {
                if (isRecording || status.state == RecorderState.COUNTDOWN) {
                    RecordingService.stop(context)
                    graph.orchestrator.requestStopRecording()
                } else {
                    RecordingService.start(context, withMic = settings.microphoneEnabled)
                }
            },
            onProtect = { graph.orchestrator.protectNow() },
            protectedFlash = status.lastProtectedEventMs,
            onOpenLibrary = onOpenLibrary,
            onOpenSettings = onOpenSettings,
            onOpenDiagnostics = onOpenDiagnostics,
        )
    }
}

@Composable
private fun CameraPreview() {
    val graph = LocalAppGraph.current
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        graph.orchestrator.setPreviewSurface(holder.surface, width, height)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        graph.orchestrator.setPreviewSurface(null, 0, 0)
                    }
                })
            }
        },
    )
}

@Composable
private fun StatusChipRow(
    status: com.tunlezah.dashcam.recording.RecorderStatus,
    gpsQuality: GpsFixQuality,
    thermal: ThermalState,
    batteryPercent: Int,
    charging: Boolean,
    micActive: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val gpsColor = when (gpsQuality) {
            GpsFixQuality.GOOD -> DashCamColors.okGreen
            GpsFixQuality.POOR -> DashCamColors.warningAmber
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        StatusChip(
            icon = if (gpsQuality == GpsFixQuality.GOOD || gpsQuality == GpsFixQuality.POOR) Icons.Filled.GpsFixed else Icons.Filled.GpsOff,
            label = when (gpsQuality) {
                GpsFixQuality.GOOD -> "GPS"
                GpsFixQuality.POOR -> "GPS ~"
                GpsFixQuality.SEARCHING -> "GPS…"
                GpsFixQuality.DISABLED -> "GPS off"
                GpsFixQuality.NO_PERMISSION -> "No GPS"
            },
            tint = gpsColor,
        )
        val thermalColor = when (thermal) {
            ThermalState.NOMINAL -> DashCamColors.okGreen
            ThermalState.WARM -> DashCamColors.warningAmber
            ThermalState.ELEVATED -> DashCamColors.warningAmber
            else -> DashCamColors.recordRed
        }
        StatusChip(Icons.Filled.Thermostat, thermal.name.lowercase().replaceFirstChar { it.uppercase() }, thermalColor)
        StatusChip(
            icon = if (charging) Icons.Filled.Bolt else Icons.Filled.BatteryStd,
            label = if (batteryPercent >= 0) "$batteryPercent%" else "—",
            tint = if (batteryPercent in 1..20 && !charging) DashCamColors.recordRed else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (micActive) StatusChip(Icons.Filled.Mic, "Mic", DashCamColors.warningAmber)
        Spacer(Modifier.weight(1f))
        status.activeProfile?.let {
            Text(
                text = "${it.height}p${it.fps}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordingBadge(recording: Boolean, startMs: Long, degraded: Boolean, modifier: Modifier = Modifier) {
    if (!recording) return
    var elapsed by remember { mutableLongStateOf(0L) }
    LaunchedEffect(startMs) {
        while (true) {
            elapsed = (System.currentTimeMillis() - startMs) / 1000
            delay(1000)
        }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(50), color = Color(0xAA000000)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = "Recording",
                tint = DashCamColors.recordRed,
                modifier = Modifier.size(12.dp),
            )
            val h = elapsed / 3600
            val m = (elapsed % 3600) / 60
            val s = elapsed % 60
            Text(
                text = "%02d:%02d:%02d".format(h, m, s) + if (degraded) "  · reduced" else "",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SpeedPanel(speedKmh: Float, quality: GpsFixQuality, settings: DashcamSettings) {
    if (!settings.overlaySpeed && !settings.gpsEnabled) return
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val display = when {
                quality == GpsFixQuality.GOOD || quality == GpsFixQuality.POOR ->
                    if (speedKmh.isNaN()) "--" else speedKmh.roundToInt().toString()
                else -> "--"
            }
            Text(
                text = display,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "km/h" + if (quality == GpsFixQuality.POOR) " (approx)" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StoragePanel(s: com.tunlezah.dashcam.domain.storage.StorageManager.StorageStatus) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val usedGb = s.loopBytes / 1e9
            val capGb = s.loopCapBytes / 1e9
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Loop storage", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "%.1f / %.1f GB".format(usedGb, capGb),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LinearProgressIndicator(
                progress = { (s.loopBytes.toFloat() / s.loopCapBytes.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            val remainMin = s.estimatedRemainingRecordingMs / 60000
            val protectedGb = s.protectedBytes / 1e9
            Text(
                text = buildString {
                    append("Protected %.1f GB".format(protectedGb))
                    if (s.protectedOverBudget) append(" — over budget")
                    if (remainMin > 0) append("  ·  ~${remainMin} min until loop reuse")
                    if (s.lowSpace) append("  ·  device space low")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (s.protectedOverBudget || s.lowSpace) DashCamColors.warningAmber
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlBar(
    isRecording: Boolean,
    countdown: Boolean,
    onRecordToggle: () -> Unit,
    onProtect: () -> Unit,
    protectedFlash: Long,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var flashUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(protectedFlash) {
        if (protectedFlash > 0) {
            flashUntil = System.currentTimeMillis() + 2500
            delay(2600)
            flashUntil = 0
        }
    }
    val flashing = System.currentTimeMillis() < flashUntil

    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = onOpenLibrary, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.VideoLibrary, contentDescription = "Recordings")
            }

            // Protect: single tap protects; kept prominent but distinct from stop.
            Surface(
                shape = CircleShape,
                color = if (flashing) DashCamColors.okGreen else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .combinedClickable(enabled = isRecording, onClick = onProtect),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Filled.Shield,
                        contentDescription = "Protect recording",
                        tint = if (flashing) Color.White else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            // Record / stop: the primary control.
            Surface(
                shape = CircleShape,
                color = if (isRecording || countdown) DashCamColors.recordRed else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .combinedClickable(onClick = onRecordToggle),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        if (isRecording || countdown) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop recording" else "Start recording",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            IconButton(onClick = onOpenDiagnostics, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Outlined.Insights, contentDescription = "Diagnostics")
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    }
    if (flashing) {
        Text(
            "Footage protected",
            modifier = Modifier
                .fillMaxWidth()
                .background(DashCamColors.okGreen)
                .padding(6.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
