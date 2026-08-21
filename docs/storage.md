# Storage Design

## Where recordings live

App-specific external storage (`getExternalFilesDir(Movies)`) — no permissions
required, invisible to the user's gallery, and removed on uninstall (stated in
the app's About text). Layout mirrors hardware-dashcam conventions:

```
Android/data/com.tunlezah.dashcam/files/Movies/
├── loop/         20260821_143059_F.mp4   circulating segments (fMP4)
├── protected/                            reserved for future physical moves;
│                                         protection is index-level today
├── quarantine/                           unreadable files found at recovery (bounded)
├── export/                               classic-MP4 remuxes for sharing
├── tracks/       20260821_143059.gpx     GPX tracks (optional)
└── maps/         *.pmtiles               offline map data (optional)
```

File naming is the Viofo-style `YYYYMMDD_HHMMSS_F.mp4` (`_C` for the cabin
camera) — chronologically sortable and tool-friendly.

A removable SD card (`getExternalFilesDirs`) can be preferred where present —
loop recording is a worst-case sustained-write workload, and spreading wear to
a replaceable card is kinder than wearing soldered UFS.

## The loop

- Default cap **5 GB** (≈1 h at the G04 Auto profile), options 2–30 GB, hard
  bounds 1–128 GB enforced by `SettingsValidator`.
- **Preemptive eviction**: before each segment starts, the manager ensures
  space within the cap AND above the device reserve by deleting oldest
  *unprotected* segments. Eviction runs on an IO coroutine, never on the
  recording threads — the sink consumes a pre-approved "permit", so a slow
  filesystem can never block a frame.
- **Device safety reserve**: max(1 GiB, 5 % of the volume) is never consumed.
  The user's loop cap is additionally clamped by actual device room
  (`effectiveLoopCap`), and recording stops cleanly with a clear status when
  space is truly exhausted.

## Protected footage

- Protection is metadata (the loop skips protected rows) — instantaneous, no
  file copying during an incident.
- Protected clips are **never auto-deleted**. To stop protected footage from
  silently eating the device, it has its own budget (default 2 GB): exceeding
  it warns on the main screen and in Diagnostics; the user manages clips in the
  Library (unprotect / share / delete).

## File safety (the crash-during-a-crash problem)

The moment most likely to corrupt a recording — a collision — is precisely the
moment the recording matters. Defences, in layers:

1. **Fragmented MP4** (`FragmentedMp4Muxer`, 1 s fragments): the file on disk
   is valid up to the last complete fragment at every instant. A classic MP4
   would be garbage without its end-of-file moov box.
2. **Keyframe-aligned rotation in the sink** — the encoder never stops at
   boundaries; every frame lands in exactly one file; PTS are rebased per file.
3. **fsync + close** on segment finalize.
4. **Startup recovery** (`StartupRecovery`): reconciles the Room index with the
   filesystem — interrupted RECORDING rows are probed (`MediaExtractor`) and
   either recovered (marked RECOVERED with true duration) or quarantined;
   orphan files with no index (destroyed DB) are probed and re-indexed, so
   footage survives even database loss; ghost rows are dropped. Quarantine is
   bounded (10 files / 512 MB, oldest dropped).
5. **The database is expendable, the files are not**: Room uses destructive
   migration as a last resort because recovery can rebuild the index from disk.
6. **GPX tracks** are flushed per point and repaired (closing tags appended)
   at startup if a crash left them unterminated.

## Export path

In-app playback (ExoPlayer) reads fMP4 natively. "Share" remuxes to a classic
MP4 (`MediaExtractor` → `MediaMuxer`, no re-encode, seconds of sequential IO)
into `export/`, then shares via `FileProvider` — the raw loop directory is
never exposed. "Save to gallery" uses MediaStore's `IS_PENDING` atomic-publish
pattern.

Why not remux every segment to classic MP4 on close? It would double the write
volume (flash wear) for files that are mostly consumed in-app or deleted by the
loop. Only shared footage pays the remux cost.
