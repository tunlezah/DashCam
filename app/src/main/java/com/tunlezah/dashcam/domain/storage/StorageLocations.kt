package com.tunlezah.dashcam.domain.storage

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves where recordings live. Uses app-specific external storage
 * (no permissions, not scanned into the user's gallery, removed on uninstall —
 * documented in docs/storage.md). Optionally prefers a removable SD card,
 * which spreads loop-write wear away from the soldered flash.
 *
 * Directory layout mirrors hardware-dashcam conventions:
 *   loop/       — circulating segments (deleted oldest-first)
 *   protected/  — event + manually protected footage (never auto-deleted)
 *   quarantine/ — unreadable files found during recovery (bounded)
 *   export/     — user-requested classic-MP4 remuxes before sharing
 *   tracks/     — GPX tracks
 */
class StorageLocations(private val context: Context, preferRemovable: Boolean) {

    val baseDir: File = run {
        val dirs = context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull()
        val removable = dirs.drop(1).firstOrNull {
            runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false) &&
                Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
        }
        (if (preferRemovable && removable != null) removable else dirs.firstOrNull())
            ?: context.filesDir.resolve("movies")
    }

    val loopDir: File get() = baseDir.resolve("loop").apply { mkdirs() }
    val protectedDir: File get() = baseDir.resolve("protected").apply { mkdirs() }
    val quarantineDir: File get() = baseDir.resolve("quarantine").apply { mkdirs() }
    val exportDir: File get() = baseDir.resolve("export").apply { mkdirs() }
    val tracksDir: File get() = baseDir.resolve("tracks").apply { mkdirs() }

    companion object {
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

        /**
         * Hardware-dashcam style naming: 20260821_143059_F.mp4
         * (_F = forward/road camera, _C = cabin/front camera) — sorts
         * chronologically and pairs with same-named .gpx tracks.
         */
        fun segmentFileName(startWallMs: Long, frontCamera: Boolean): String {
            val suffix = if (frontCamera) "C" else "F"
            return "${FILE_STAMP.format(Date(startWallMs))}_$suffix.mp4"
        }

        fun trackFileName(startWallMs: Long): String =
            "${FILE_STAMP.format(Date(startWallMs))}.gpx"
    }
}
