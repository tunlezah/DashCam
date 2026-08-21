package com.tunlezah.dashcam.ui

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tunlezah.dashcam.AppGraph
import com.tunlezah.dashcam.DashCamApplication
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.ui.diagnostics.DiagnosticsScreen
import com.tunlezah.dashcam.ui.library.LibraryScreen
import com.tunlezah.dashcam.ui.library.PlayerScreen
import com.tunlezah.dashcam.ui.main.MainScreen
import com.tunlezah.dashcam.ui.settings.SettingsScreen
import com.tunlezah.dashcam.ui.theme.DashCamTheme

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }

class MainActivity : ComponentActivity() {

    private val graph: AppGraph get() = (application as DashCamApplication).graph

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // Re-evaluate GPS now that permissions may have changed.
            graph.orchestrator.setUiVisible(true)
            val cameraGranted = grants[Manifest.permission.CAMERA] == true
            if (cameraGranted) maybeAutoStart()
        }

    override fun onStart() {
        super.onStart()
        graph.orchestrator.setUiVisible(true)
    }

    override fun onStop() {
        graph.orchestrator.setUiVisible(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestCorePermissions()

        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                val settings by graph.settingsRepository.settings
                    .collectAsStateWithLifecycle(initialValue = DashcamSettings())
                KeepScreenOn(settings.keepScreenOn)
                DashCamTheme(settings.theme) {
                    AppNavHost()
                }
            }
        }
    }

    @Composable
    private fun KeepScreenOn(enabled: Boolean) {
        androidx.compose.runtime.SideEffect {
            if (enabled) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /**
     * Core permission request on launch: camera (mandatory), notifications
     * (FGS status), location (GPS overlays — the app still records without it).
     * The microphone is deliberately NOT requested here: it is requested only
     * when the user enables audio recording in Settings (docs/privacy.md).
     */
    private fun requestCorePermissions() {
        val wanted = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (wanted.isNotEmpty()) {
            permissionLauncher.launch(wanted.toTypedArray())
        } else {
            maybeAutoStart()
        }
    }

    /**
     * Auto-start recording on launch when enabled (brief §5). Gated on the
     * activity being RESUMED: a camera foreground service may only start while
     * the app is genuinely in the foreground on Android 14+.
     */
    private var autoStartAttempted = false

    private fun maybeAutoStart() {
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (!autoStartAttempted) {
                    autoStartAttempted = true
                    val g = graph
                    val settings = g.settingsRepository.current()
                    if (settings.autoStartOnLaunch && !g.orchestrator.isRecording) {
                        com.tunlezah.dashcam.recording.RecordingService.start(
                            this@MainActivity, withMic = settings.microphoneEnabled,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(
                onOpenSettings = { nav.navigate("settings") },
                onOpenLibrary = { nav.navigate("library") },
                onOpenDiagnostics = { nav.navigate("diagnostics") },
            )
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
        composable("library") {
            LibraryScreen(
                onBack = { nav.popBackStack() },
                onPlay = { segmentId -> nav.navigate("player/$segmentId") },
            )
        }
        composable("player/{segmentId}") { entry ->
            val id = entry.arguments?.getString("segmentId")?.toLongOrNull() ?: return@composable
            PlayerScreen(segmentId = id, onBack = { nav.popBackStack() })
        }
        composable("diagnostics") { DiagnosticsScreen(onBack = { nav.popBackStack() }) }
    }
}
