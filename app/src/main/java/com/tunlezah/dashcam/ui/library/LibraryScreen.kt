package com.tunlezah.dashcam.ui.library

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.recording.SegmentExporter
import com.tunlezah.dashcam.ui.LocalAppGraph
import com.tunlezah.dashcam.ui.theme.DashCamColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onPlay: (Long) -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allSegments by graph.database.segmentDao().observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var tab by remember { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<SegmentEntity?>(null) }
    var busyMessage by remember { mutableStateOf<String?>(null) }

    val shown = if (tab == 0) allSegments else allSegments.filter { it.protected }
    val dateFormat = remember { SimpleDateFormat("EEE d MMM HH:mm:ss", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier
            .fillMaxSize()
            .padding(padding)) {
            androidx.compose.material3.PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("All") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Protected") })
            }
            busyMessage?.let {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (shown.isEmpty()) {
                Text(
                    "No recordings yet",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn {
                items(shown, key = { it.id }) { segment ->
                    SegmentRow(
                        segment = segment,
                        dateFormat = dateFormat,
                        onPlay = { onPlay(segment.id) },
                        onToggleProtect = {
                            scope.launch {
                                val dao = graph.database.segmentDao()
                                if (segment.protected) dao.unprotect(segment.id)
                                else dao.protect(listOf(segment.id), eventId = 0)
                            }
                        },
                        onShare = {
                            scope.launch {
                                busyMessage = "Preparing shareable MP4…"
                                val exporter = SegmentExporter(context, graph.diagnostics)
                                val source = File(segment.filePath)
                                val dest = File(graph.storageLocations.exportDir, source.name)
                                val ok = exporter.exportToFile(source, dest)
                                busyMessage = null
                                if (ok) {
                                    val uri = FileProvider.getUriForFile(
                                        context, "${context.packageName}.files", dest,
                                    )
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "video/mp4"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share recording"))
                                } else {
                                    busyMessage = "Export failed"
                                }
                            }
                        },
                        onDelete = { confirmDelete = segment },
                    )
                }
            }
        }
    }

    confirmDelete?.let { segment ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete recording?") },
            text = {
                Text(
                    (if (segment.protected) "This recording is PROTECTED. " else "") +
                        "This permanently deletes ${File(segment.filePath).name}."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        graph.storageManager.deleteSegment(segment)
                        confirmDelete = null
                    }
                }) { Text("Delete", color = DashCamColors.recordRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: SegmentEntity,
    dateFormat: SimpleDateFormat,
    onPlay: () -> Unit,
    onToggleProtect: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = "Play",
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(dateFormat.format(Date(segment.startWallMs)), style = MaterialTheme.typography.bodyMedium)
            val duration = segment.endWallMs?.let { (it - segment.startWallMs) / 1000 } ?: 0
            Text(
                buildString {
                    append("${duration / 60}m ${duration % 60}s · %.0f MB".format(segment.sizeBytes / 1e6))
                    if (segment.state.name == "RECOVERED") append(" · recovered")
                    if (segment.frontCamera) append(" · cabin")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleProtect) {
            Icon(
                if (segment.protected) Icons.Filled.Shield else Icons.Outlined.Shield,
                contentDescription = if (segment.protected) "Unprotect" else "Protect",
                tint = if (segment.protected) DashCamColors.okGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Filled.Share, contentDescription = "Share")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
