package com.tunlezah.dashcam.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.shape.RoundedCornerShape
import com.tunlezah.dashcam.domain.location.GpsState
import com.tunlezah.dashcam.domain.settings.AppTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File

/**
 * The optional offline map panel (docs/offline-maps.md). Battery/thermal
 * playbook from the research: north-up, capped FPS, camera jump per GPS fix
 * (no animation), tiny stripped style, lifecycle-paused. It renders below the
 * preview and can never affect the recording pipeline — any failure collapses
 * to a placeholder message.
 */
@Composable
fun MapPanel(
    mapFile: File,
    styleJson: String,
    gps: GpsState,
    maxFps: Int,
    paused: Boolean,
    theme: AppTheme,
    modifier: Modifier = Modifier,
    onDiagnostic: (String) -> Unit = {},
) {
    var initFailed by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) {
        if (initFailed) {
            Text(
                "Map unavailable on this device",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (paused) {
            Text(
                "Map paused (device warm)",
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
            var viewRef by remember { mutableStateOf<MapView?>(null) }

            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    try {
                        MapLibre.getInstance(ctx)
                        val view = MapView(ctx)
                        view.onCreate(null)
                        // Failures here were previously silent (a blank panel
                        // indistinguishable from "no tiles at this location").
                        // Surface them on the panel and in diagnostics.
                        view.addOnDidFailLoadingMapListener { message ->
                            loadError = message
                            onDiagnostic("load failed: $message")
                        }
                        view.onStart()
                        view.onResume()
                        view.setMaximumFps(maxFps)
                        view.getMapAsync { map ->
                            map.uiSettings.setAllGesturesEnabled(false)
                            map.uiSettings.isAttributionEnabled = true
                            map.uiSettings.isLogoEnabled = false
                            map.setStyle(
                                org.maplibre.android.maps.Style.Builder().fromJson(styleJson)
                            ) {
                                loadError = null
                                onDiagnostic("style loaded (${mapFile.name})")
                            }
                            mapRef = map
                        }
                        viewRef = view
                        view
                    } catch (e: Throwable) {
                        initFailed = true
                        onDiagnostic("init failed: ${e.message}")
                        android.widget.FrameLayout(ctx)
                    }
                },
            )
            loadError?.let { err ->
                Text(
                    "Map error: $err",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFB4AB),
                )
            }
            // Camera follow driven explicitly by GPS updates (not by view
            // recomposition side effects): jump-set per fix, north-up, fixed
            // zoom — the battery playbook from the research.
            val map = mapRef
            LaunchedEffect(map, gps.latitude, gps.longitude, gps.hasFix) {
                if (map != null && gps.hasFix &&
                    !gps.latitude.isNaN() && !gps.longitude.isNaN()
                ) {
                    map.cameraPosition = CameraPosition.Builder()
                        .target(LatLng(gps.latitude, gps.longitude))
                        .zoom(14.5)
                        .bearing(0.0) // north-up: rotating dirties every tile
                        .build()
                }
            }
            // The camera centres on the fix, so a fixed centre dot IS the
            // vehicle marker — no LocationComponent machinery needed.
            if (gps.hasFix) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(14.dp)
                        .background(Color(0xFF2D9CDB), androidx.compose.foundation.shape.CircleShape)
                        .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                )
            } else {
                Text(
                    "Waiting for GPS…",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0x99000000), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                )
            }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching {
                        viewRef?.onPause()
                        viewRef?.onStop()
                        viewRef?.onDestroy()
                    }
                }
            }
        }
        // ODbL attribution (required): kept visible over the map corner.
        Text(
            "© OpenStreetMap",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (theme == AppTheme.LIGHT) Color(0xAA000000) else Color(0xAAFFFFFF),
        )
    }
}
