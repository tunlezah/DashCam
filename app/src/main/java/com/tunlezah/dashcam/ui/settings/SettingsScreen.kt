package com.tunlezah.dashcam.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunlezah.dashcam.domain.capability.DeviceTier
import com.tunlezah.dashcam.domain.settings.AppTheme
import com.tunlezah.dashcam.domain.settings.CameraFacing
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.domain.settings.EventSensitivity
import com.tunlezah.dashcam.domain.settings.OverlayMode
import com.tunlezah.dashcam.domain.settings.PlugInAction
import com.tunlezah.dashcam.domain.settings.PortraitCaptureMode
import com.tunlezah.dashcam.domain.settings.QualityMode
import com.tunlezah.dashcam.domain.settings.SettingsValidator
import com.tunlezah.dashcam.domain.settings.UnplugAction
import com.tunlezah.dashcam.domain.settings.VideoCodec
import com.tunlezah.dashcam.domain.settings.VideoResolution
import com.tunlezah.dashcam.ui.LocalAppGraph
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val settings by graph.settingsRepository.settings
        .collectAsStateWithLifecycle(initialValue = DashcamSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun update(transform: (DashcamSettings) -> DashcamSettings) {
        scope.launch { graph.settingsRepository.update(transform) }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) update { it.copy(microphoneEnabled = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // ---------------- Recording ----------------
            item { SectionHeader("Recording") }
            item {
                ChoiceRow(
                    title = "Quality",
                    subtitle = if (settings.qualityMode == QualityMode.AUTO)
                        "Auto — best sustainable profile for this device" else "Manual",
                    options = QualityMode.entries.map { it to if (it == QualityMode.AUTO) "Auto (recommended)" else "Manual" },
                    selected = settings.qualityMode,
                ) { update { s -> s.copy(qualityMode = it) } }
            }
            if (settings.qualityMode == QualityMode.MANUAL) {
                item {
                    val camera = graph.capabilities.camera(settings.cameraFacing == CameraFacing.BACK)
                    val available = VideoResolution.entries.filter { res ->
                        camera?.supportsSize(res.width, res.height) ?: (res <= VideoResolution.FHD_1080)
                    }
                    ChoiceRow(
                        title = "Resolution",
                        subtitle = settings.manualResolution.label,
                        options = available.map { it to it.label },
                        selected = settings.manualResolution,
                    ) { update { s -> s.copy(manualResolution = it) } }
                }
                item {
                    ChoiceRow(
                        title = "Frame rate",
                        subtitle = "${settings.manualFps} fps",
                        options = SettingsValidator.FPS_OPTIONS.map { it to "$it fps" },
                        selected = settings.manualFps,
                    ) { update { s -> s.copy(manualFps = it) } }
                }
                item {
                    val hasHevc = graph.capabilities.hardwareEncoder(VideoCodec.HEVC.mimeType) != null
                    ChoiceRow(
                        title = "Codec",
                        subtitle = settings.manualCodec.label + if (!hasHevc) " (HEVC: no hardware encoder)" else "",
                        options = VideoCodec.entries.map { it to it.label },
                        selected = settings.manualCodec,
                    ) { update { s -> s.copy(manualCodec = it) } }
                }
            }
            item {
                ChoiceRow(
                    title = "Segment length",
                    subtitle = "${settings.segmentMinutes} minutes",
                    options = SettingsValidator.SEGMENT_MINUTES_OPTIONS.map { it to "$it min" },
                    selected = settings.segmentMinutes,
                ) { update { s -> s.copy(segmentMinutes = it) } }
            }
            item {
                ChoiceRow(
                    title = "Startup delay",
                    subtitle = "${settings.startupDelaySeconds} seconds after launch",
                    options = listOf(0, 3, 5, 10, 15, 30).map { it to "$it s" },
                    selected = settings.startupDelaySeconds,
                ) { update { s -> s.copy(startupDelaySeconds = it) } }
            }
            item {
                SwitchRow("Start recording on app launch", null, settings.autoStartOnLaunch) {
                    update { s -> s.copy(autoStartOnLaunch = it) }
                }
            }

            // ---------------- Events ----------------
            item { SectionHeader("Incident detection") }
            item {
                SwitchRow(
                    "Automatic event detection",
                    "Protects footage when an impact is detected",
                    settings.eventDetectionEnabled,
                ) { update { s -> s.copy(eventDetectionEnabled = it) } }
            }
            item {
                ChoiceRow(
                    title = "Sensitivity",
                    subtitle = settings.eventSensitivity.name.lowercase().replaceFirstChar { it.uppercase() } +
                        " — lower reduces pothole false alarms",
                    options = EventSensitivity.entries.map {
                        it to it.name.lowercase().replaceFirstChar { c -> c.uppercase() }
                    },
                    selected = settings.eventSensitivity,
                ) { update { s -> s.copy(eventSensitivity = it) } }
            }
            item {
                ChoiceRow(
                    title = "Pre-event protection",
                    subtitle = "${settings.preEventSeconds} s before the event",
                    options = SettingsValidator.PRE_EVENT_OPTIONS.map { it to "$it s" },
                    selected = settings.preEventSeconds,
                ) { update { s -> s.copy(preEventSeconds = it) } }
            }
            item {
                ChoiceRow(
                    title = "Post-event protection",
                    subtitle = "${settings.postEventSeconds} s after the event",
                    options = SettingsValidator.POST_EVENT_OPTIONS.map { it to "$it s" },
                    selected = settings.postEventSeconds,
                ) { update { s -> s.copy(postEventSeconds = it) } }
            }

            // ---------------- Storage ----------------
            item { SectionHeader("Storage") }
            item {
                ChoiceRow(
                    title = "Maximum loop storage",
                    subtitle = "%.0f GB — oldest unprotected footage is reused first".format(settings.loopMaxBytes / 1e9),
                    options = SettingsValidator.LOOP_SIZE_OPTIONS_GIB.map {
                        (it * DashcamSettings.GIB) to "$it GB"
                    },
                    selected = settings.loopMaxBytes,
                ) { update { s -> s.copy(loopMaxBytes = it) } }
            }
            item {
                ChoiceRow(
                    title = "Protected footage budget",
                    subtitle = "%.0f GB — warns when protected clips exceed this".format(settings.protectedBudgetBytes / 1e9),
                    options = listOf(1L, 2L, 5L, 10L).map { (it * DashcamSettings.GIB) to "$it GB" },
                    selected = settings.protectedBudgetBytes,
                ) { update { s -> s.copy(protectedBudgetBytes = it) } }
            }

            // ---------------- Overlays ----------------
            item { SectionHeader("Overlays") }
            item {
                ChoiceRow(
                    title = "Overlay mode",
                    subtitle = if (settings.overlayMode == OverlayMode.STAMP)
                        "Stamped into the video file" else "Shown on screen only",
                    options = listOf(
                        OverlayMode.STAMP to "Stamp into video",
                        OverlayMode.DISPLAY_ONLY to "Display only",
                    ),
                    selected = settings.overlayMode,
                ) { update { s -> s.copy(overlayMode = it) } }
            }
            item { SwitchRow("Speed", "km/h from GPS", settings.overlaySpeed) { update { s -> s.copy(overlaySpeed = it) } } }
            item { SwitchRow("GPS coordinates", null, settings.overlayGpsCoordinates) { update { s -> s.copy(overlayGpsCoordinates = it) } } }
            item { SwitchRow("Date", null, settings.overlayDate) { update { s -> s.copy(overlayDate = it) } } }
            item { SwitchRow("Time", null, settings.overlayTime) { update { s -> s.copy(overlayTime = it) } } }
            item {
                SwitchRow("Weather", "Requires the optional weather feature", settings.overlayWeather && settings.weatherEnabled) {
                    update { s -> s.copy(overlayWeather = it, weatherEnabled = it || s.weatherEnabled) }
                }
            }

            // ---------------- GPS ----------------
            item { SectionHeader("GPS") }
            item {
                SwitchRow("Use GPS", "Works without SIM or internet. Recording never depends on GPS.", settings.gpsEnabled) {
                    update { s -> s.copy(gpsEnabled = it) }
                }
            }
            item {
                SwitchRow("Write GPX track files", "Saved locally next to recordings; never uploaded", settings.gpsWriteGpxTrack) {
                    update { s -> s.copy(gpsWriteGpxTrack = it) }
                }
            }
            item {
                SwitchRow("Embed location in video metadata", "Stores start coordinates inside each file", settings.gpsEmbedInVideoMetadata) {
                    update { s -> s.copy(gpsEmbedInVideoMetadata = it) }
                }
            }

            // ---------------- Audio ----------------
            item { SectionHeader("Audio") }
            item {
                SwitchRow(
                    "Record microphone audio",
                    "Off by default. Recording conversations may require consent from everyone in the vehicle — check the laws in your state.",
                    settings.microphoneEnabled,
                ) { enable ->
                    if (enable) {
                        val granted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) update { s -> s.copy(microphoneEnabled = true) }
                        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        update { s -> s.copy(microphoneEnabled = false) }
                    }
                }
            }

            // ---------------- Camera ----------------
            item { SectionHeader("Camera") }
            item {
                ChoiceRow(
                    title = "Camera",
                    subtitle = if (settings.cameraFacing == CameraFacing.BACK) "Rear (road)" else "Front (cabin)",
                    options = listOf(CameraFacing.BACK to "Rear (road)", CameraFacing.FRONT to "Front (cabin)"),
                    selected = settings.cameraFacing,
                ) { update { s -> s.copy(cameraFacing = it) } }
            }
            item {
                val supported = graph.capabilities.camera(settings.cameraFacing == CameraFacing.BACK)
                    ?.supportsVideoStabilization ?: false
                SwitchRow(
                    "Video stabilisation",
                    if (supported) "Electronic stabilisation" else "Not supported by this camera",
                    settings.stabilizationEnabled && supported,
                ) { update { s -> s.copy(stabilizationEnabled = it) } }
            }
            item {
                ChoiceRow(
                    title = "Portrait mounting output",
                    subtitle = if (settings.portraitCaptureMode == PortraitCaptureMode.CROP_16_9)
                        "Wide 16:9 road band (recommended)" else "Full portrait frame",
                    options = listOf(
                        PortraitCaptureMode.CROP_16_9 to "Wide 16:9 road band (recommended)",
                        PortraitCaptureMode.FULL_FRAME to "Full portrait frame (9:16 video)",
                    ),
                    selected = settings.portraitCaptureMode,
                ) { update { s -> s.copy(portraitCaptureMode = it) } }
            }

            // ---------------- Power ----------------
            item { SectionHeader("Power") }
            item {
                ChoiceRow(
                    title = "When power is connected",
                    subtitle = when (settings.plugInAction) {
                        PlugInAction.START_RECORDING -> "Start recording (while app is open)"
                        PlugInAction.PROMPT -> "Do nothing (start manually)"
                        PlugInAction.NONE -> "Do nothing"
                    },
                    options = listOf(
                        PlugInAction.START_RECORDING to "Start recording (while app is open)",
                        PlugInAction.NONE to "Do nothing",
                    ),
                    selected = if (settings.plugInAction == PlugInAction.PROMPT) PlugInAction.NONE else settings.plugInAction,
                ) { update { s -> s.copy(plugInAction = it) } }
            }
            item {
                ChoiceRow(
                    title = "When power is disconnected",
                    subtitle = when (settings.unplugAction) {
                        UnplugAction.CONTINUE -> "Keep recording on battery"
                        UnplugAction.STOP_IMMEDIATELY -> "Stop recording"
                        UnplugAction.STOP_AFTER_DELAY -> "Stop after ${settings.unplugStopDelaySeconds} s"
                        UnplugAction.BATTERY_SAVER_PROFILE -> "Switch to battery-saver quality"
                    },
                    options = listOf(
                        UnplugAction.STOP_AFTER_DELAY to "Stop after a delay (default)",
                        UnplugAction.STOP_IMMEDIATELY to "Stop immediately",
                        UnplugAction.CONTINUE to "Keep recording on battery",
                        UnplugAction.BATTERY_SAVER_PROFILE to "Switch to battery-saver quality",
                    ),
                    selected = settings.unplugAction,
                ) { update { s -> s.copy(unplugAction = it) } }
            }
            if (settings.unplugAction == UnplugAction.STOP_AFTER_DELAY) {
                item {
                    ChoiceRow(
                        title = "Stop delay",
                        subtitle = "${settings.unplugStopDelaySeconds} seconds",
                        options = listOf(15, 30, 60, 120, 300).map { it to "$it s" },
                        selected = settings.unplugStopDelaySeconds,
                    ) { update { s -> s.copy(unplugStopDelaySeconds = it) } }
                }
            }
            item {
                ChoiceRow(
                    title = "Stop below battery level",
                    subtitle = "${settings.batteryFloorPercent}% (when not charging)",
                    options = listOf(5, 10, 15, 20, 30, 40).map { it to "$it%" },
                    selected = settings.batteryFloorPercent,
                ) { update { s -> s.copy(batteryFloorPercent = it) } }
            }

            // ---------------- Display ----------------
            item { SectionHeader("Display") }
            item {
                SwitchRow("Keep screen on while recording", null, settings.keepScreenOn) {
                    update { s -> s.copy(keepScreenOn = it) }
                }
            }
            item {
                SwitchRow(
                    "Allow screen-off recording",
                    "Recording continues with the screen off. Some manufacturers block this — the app detects it and warns.",
                    settings.allowScreenOffRecording,
                ) { update { s -> s.copy(allowScreenOffRecording = it) } }
            }
            item {
                ChoiceRow(
                    title = "Theme",
                    subtitle = settings.theme.name.lowercase().replaceFirstChar { it.uppercase() },
                    options = listOf(
                        AppTheme.SYSTEM to "System",
                        AppTheme.LIGHT to "Light",
                        AppTheme.DARK to "Dark",
                        AppTheme.OLED to "OLED true black",
                    ),
                    selected = settings.theme,
                ) { update { s -> s.copy(theme = it) } }
            }

            // ---------------- Map & weather ----------------
            item { SectionHeader("Map & weather (optional)") }
            item {
                val constrained = graph.tier == DeviceTier.CONSTRAINED
                SwitchRow(
                    "Offline map panel",
                    if (constrained)
                        "Off by default on this device class — map rendering competes with recording. Requires an offline map file."
                    else "Shows an offline map below the preview. Requires an offline map file.",
                    settings.mapEnabled,
                ) { update { s -> s.copy(mapEnabled = it) } }
            }
            item {
                SwitchRow(
                    "Weather updates",
                    "Uses the internet when available (Open-Meteo). Recording never depends on it.",
                    settings.weatherEnabled,
                ) { update { s -> s.copy(weatherEnabled = it) } }
            }

            // ---------------- About ----------------
            item { SectionHeader("About") }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("DashCam", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Privacy-first dashcam. All footage, GPS tracks and settings stay on this device. " +
                            "Device tier: ${graph.tier}. Map data © OpenStreetMap contributors (when maps are used).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelect(value)
                                    showDialog = false
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = value == selected, onClick = {
                                onSelect(value)
                                showDialog = false
                            })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) { Text("Close") }
            },
        )
    }
}
