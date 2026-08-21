package com.tunlezah.dashcam.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.tunlezah.dashcam.ui.LocalAppGraph
import java.io.File

/** In-app playback of a recorded segment (ExoPlayer plays fMP4 natively). */
@Composable
fun PlayerScreen(segmentId: Long, onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    var filePath by remember { mutableStateOf<String?>(null) }
    var missing by remember { mutableStateOf(false) }

    LaunchedEffect(segmentId) {
        val segment = graph.database.segmentDao().byId(segmentId)
        val path = segment?.filePath
        if (path != null && File(path).exists()) filePath = path else missing = true
    }

    Box(Modifier
        .fillMaxSize()
        .background(Color.Black)) {
        filePath?.let { path ->
            val player = remember(path) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(path))))
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) {
                onDispose { player.release() }
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
            )
        }
        if (missing) {
            Text(
                "Recording file not found",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
    }
}
