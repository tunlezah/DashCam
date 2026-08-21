package com.tunlezah.dashcam.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaFormat
import androidx.core.content.ContextCompat
import com.tunlezah.dashcam.core.DiagnosticsLog
import com.tunlezah.dashcam.data.db.EventType
import com.tunlezah.dashcam.data.db.SegmentDao
import com.tunlezah.dashcam.data.db.SegmentEntity
import com.tunlezah.dashcam.data.db.SegmentState
import com.tunlezah.dashcam.domain.capability.DeviceCapabilities
import com.tunlezah.dashcam.domain.capability.DeviceTier
import com.tunlezah.dashcam.domain.capability.ProfileSelector
import com.tunlezah.dashcam.domain.capability.RecordingProfile
import com.tunlezah.dashcam.domain.events.AccelerometerFeed
import com.tunlezah.dashcam.domain.events.EventDetector
import com.tunlezah.dashcam.domain.events.EventProtector
import com.tunlezah.dashcam.domain.location.GpsManager
import com.tunlezah.dashcam.domain.location.GpxWriter
import com.tunlezah.dashcam.domain.power.PowerEvent
import com.tunlezah.dashcam.domain.power.PowerMonitor
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.domain.settings.OverlayMode
import com.tunlezah.dashcam.domain.settings.PlugInAction
import com.tunlezah.dashcam.domain.settings.SettingsRepository
import com.tunlezah.dashcam.domain.settings.UnplugAction
import com.tunlezah.dashcam.domain.storage.StorageLocations
import com.tunlezah.dashcam.domain.storage.StorageManager
import com.tunlezah.dashcam.domain.thermal.ThermalEngine
import com.tunlezah.dashcam.domain.thermal.ThermalMitigations
import com.tunlezah.dashcam.domain.thermal.ThermalPolicy
import com.tunlezah.dashcam.recording.engine.Camera2Controller
import com.tunlezah.dashcam.recording.engine.FrameGeometry
import com.tunlezah.dashcam.recording.engine.GlRenderPipeline
import com.tunlezah.dashcam.recording.engine.AudioPipeline
import com.tunlezah.dashcam.recording.engine.SegmentSink
import com.tunlezah.dashcam.recording.engine.VideoEncoderCore
import com.tunlezah.dashcam.recording.overlay.OverlayRenderer
import com.tunlezah.dashcam.weather.WeatherClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** High-level lifecycle state shown to the UI and the service notification. */
enum class RecorderState {
    IDLE,
    INITIALIZING,
    COUNTDOWN,
    RECORDING,
    RECORDING_DEGRADED,
    RECOVERING,
    STOPPED_ERROR,
    STOPPED_STORAGE,
    STOPPED_THERMAL,
    STOPPED_BATTERY,
}

data class RecorderStatus(
    val state: RecorderState = RecorderState.IDLE,
    val profile: RecordingProfile? = null,
    val activeProfile: RecordingProfile? = null,
    val countdownSeconds: Int = 0,
    val recordingStartMs: Long = 0,
    val segmentsWritten: Long = 0,
    val framesRendered: Long = 0,
    val lastProtectedEventMs: Long = 0,
    val statusMessage: String = "",
    val micActive: Boolean = false,
    val storageWarning: String = "",
    /** Encoded output dimensions (post-crop) — drives the preview aspect ratio. */
    val outputWidth: Int = 16,
    val outputHeight: Int = 9,
)

/**
 * The recording state machine (docs/architecture.md §9): owns the engine
 * components, wires storage/thermal/events/GPS/power into them, and performs
 * controlled recovery with bounded backoff. Runs for the lifetime of the app
 * process; the foreground service represents it to the OS.
 */
@androidx.media3.common.util.UnstableApi
class RecordingOrchestrator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val diagnostics: DiagnosticsLog,
    private val settingsRepository: SettingsRepository,
    private val capabilities: DeviceCapabilities,
    private val tier: DeviceTier,
    private val storageManager: StorageManager,
    private val storageLocations: StorageLocations,
    private val segmentDao: SegmentDao,
    private val thermalEngine: ThermalEngine,
    private val gpsManager: GpsManager,
    private val powerMonitor: PowerMonitor,
    private val accelerometerFeed: AccelerometerFeed,
    private val eventDetector: EventDetector,
    private val eventProtector: EventProtector,
    private val weatherClient: WeatherClient,
) {

    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status

    private val engineMutex = Mutex()

    // Engine components (rebuilt per profile change).
    private var camera: Camera2Controller? = null
    private var glPipeline: GlRenderPipeline? = null
    private var encoder: VideoEncoderCore? = null
    private var sink: SegmentSink? = null
    private var audio: AudioPipeline? = null
    private var overlayRenderer: OverlayRenderer? = null
    private var gpxWriter: GpxWriter? = null

    private var settings: DashcamSettings = DashcamSettings()
    private var baseProfile: RecordingProfile? = null
    private var activeProfile: RecordingProfile? = null
    private var appliedStepDowns = 0
    private var pendingRebuild = false

    // Segment planning: one pre-approved file permit at all times.
    private val plannedPermits = AtomicInteger(0)
    private val rowIdByPath = HashMap<String, Long>()

    // Preview attachment request from the UI.
    private var previewSurface: android.view.Surface? = null
    private var previewW = 0
    private var previewH = 0
    private var previewAllowedByThermal = true

    private var supervisorJobs = mutableListOf<Job>()
    private var unplugStopJob: Job? = null
    private var cameraRetries = 0
    private var encoderRetries = 0

    fun start() {
        powerMonitor.start()
        thermalEngine.start()
        scope.launch(Dispatchers.IO) { eventProtector.recoverPendingEvents() }
        scope.launch {
            settingsRepository.settings.collect { s ->
                val previous = settings
                settings = s
                onSettingsChanged(previous, s)
            }
        }
    }

    // ------------------------------------------------------------------
    // Public controls
    // ------------------------------------------------------------------

    fun requestStartRecording() {
        scope.launch { startRecordingInternal() }
    }

    fun requestStopRecording(reason: String = "user") {
        scope.launch { stopRecordingInternal(RecorderState.IDLE, reason) }
    }

    fun protectNow() {
        val s = settings
        val gps = gpsManager.state.value
        scope.launch(Dispatchers.IO) {
            eventProtector.protect(
                type = EventType.MANUAL,
                confidence = 1f,
                peakMagnitude = 0f,
                preSeconds = s.preEventSeconds,
                postSeconds = s.postEventSeconds,
                latitude = gps.latitude.takeIf { !it.isNaN() },
                longitude = gps.longitude.takeIf { !it.isNaN() },
                speedMps = gps.speedMps.takeIf { !it.isNaN() },
            )
            _status.value = _status.value.copy(lastProtectedEventMs = System.currentTimeMillis())
        }
    }

    fun setPreviewSurface(surface: android.view.Surface?, width: Int, height: Int) {
        previewSurface = surface
        previewW = width
        previewH = height
        applyPreviewAttachment()
    }

    val isRecording: Boolean
        get() = _status.value.state in setOf(
            RecorderState.RECORDING, RecorderState.RECORDING_DEGRADED, RecorderState.RECOVERING,
        )

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    private suspend fun startRecordingInternal() {
        if (isRecording || _status.value.state == RecorderState.COUNTDOWN ||
            _status.value.state == RecorderState.INITIALIZING
        ) return
        if (!hasCameraPermission()) {
            _status.value = _status.value.copy(
                state = RecorderState.IDLE, statusMessage = "Camera permission required",
            )
            return
        }

        _status.value = _status.value.copy(state = RecorderState.INITIALIZING, statusMessage = "")
        val s = settings

        // Startup delay (configurable, default ~3 s) with visible countdown.
        for (remaining in s.startupDelaySeconds downTo 1) {
            _status.value = _status.value.copy(state = RecorderState.COUNTDOWN, countdownSeconds = remaining)
            delay(1000)
            if (_status.value.state != RecorderState.COUNTDOWN) return // cancelled
        }

        engineMutex.withLock {
            val profile = ProfileSelector.select(capabilities, s, tier)
            baseProfile = profile
            appliedStepDowns = 0
            diagnostics.log("Orchestrator", "profile: ${profile.label()} [${profile.rationale}]")
            val ok = buildAndStartEngine(profile, s)
            if (!ok) {
                _status.value = _status.value.copy(
                    state = RecorderState.STOPPED_ERROR,
                    statusMessage = "Could not start the camera pipeline",
                )
                return
            }
        }
        startSupervisors()
        _status.value = _status.value.copy(
            state = RecorderState.RECORDING,
            recordingStartMs = System.currentTimeMillis(),
            activeProfile = activeProfile,
            profile = baseProfile,
        )
    }

    private suspend fun stopRecordingInternal(finalState: RecorderState, reason: String) {
        // NonCancellable: a stop may be initiated from a supervisor job that
        // stopSupervisors() cancels — teardown must still run to completion so
        // the in-progress segment is finalized.
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            if (_status.value.state == RecorderState.COUNTDOWN) {
                _status.value = _status.value.copy(state = RecorderState.IDLE, countdownSeconds = 0)
                return@withContext
            }
            if (!isRecording) return@withContext
            diagnostics.log("Orchestrator", "stopping: $reason")
            stopSupervisors()
            engineMutex.withLock { tearDownEngine() }
            _status.value = _status.value.copy(
                state = finalState,
                statusMessage = if (finalState == RecorderState.IDLE) "" else reason,
            )
        }
    }

    // ------------------------------------------------------------------
    // Engine assembly
    // ------------------------------------------------------------------

    private suspend fun buildAndStartEngine(profile: RecordingProfile, s: DashcamSettings): Boolean {
        val facingBack = s.cameraFacing == com.tunlezah.dashcam.domain.settings.CameraFacing.BACK
        val cameraCaps = capabilities.camera(facingBack) ?: capabilities.cameras.firstOrNull()
        if (cameraCaps == null) {
            diagnostics.log("Orchestrator", "no usable camera found")
            return false
        }

        // Mount orientation from gravity → MP4 rotation metadata. Frames are
        // recorded sensor-native; players rotate at display time
        // (docs/architecture.md §11 — reworked after on-device feedback).
        val gravity = sampleGravity()
        val mountRotation = FrameGeometry.mountRotationFromGravity(gravity.first, gravity.second)
        val bufferRotation = FrameGeometry.bufferRotationDegrees(
            cameraCaps.sensorOrientationDegrees, mountRotation, facingFront = !facingBack,
        )
        diagnostics.log(
            "Orchestrator",
            "mount=$mountRotation° rotation metadata=${bufferRotation}° " +
                "(sensor=${cameraCaps.sensorOrientationDegrees}°), recording ${profile.width}x${profile.height} native",
        )

        // Storage: pre-approve the first segment before opening hardware.
        val estBytes = estimatedSegmentBytes(profile, s)
        val loopCap = storageManager.effectiveLoopCap(s.loopMaxBytes)
        if (!storageManager.ensureSpaceForNextSegment(estBytes, loopCap)) {
            _status.value = _status.value.copy(statusMessage = "Not enough storage to record")
            return false
        }
        plannedPermits.set(1)

        return try {
            val enc = VideoEncoderCore(diagnostics)
            enc.configure(
                mimeType = profile.codec.mimeType,
                width = profile.width,
                height = profile.height,
                fps = profile.fps,
                bitrateBps = profile.bitrateBps,
            )
            encoder = enc

            val overlay = OverlayRenderer(profile.width, profile.height, bufferRotation)
            overlayRenderer = overlay

            val segmentSink = SegmentSink(
                diagnostics,
                syncFrameRequester = { enc.requestSyncFrame() },
                planner = { startWallMs -> takePlannedFile(startWallMs) },
                listener = sinkListener,
            )
            segmentSink.configure(s.segmentMinutes, s.gpsEmbedInVideoMetadata, bufferRotation)
            sink = segmentSink

            val gl = GlRenderPipeline(diagnostics)
            gl.start(
                encoderSurface = requireNotNull(enc.inputSurface),
                width = profile.width,
                height = profile.height,
                rotation = bufferRotation,
                burnIn = s.overlayMode == OverlayMode.STAMP && overlay.anyEnabled(s),
                overlays = overlay,
            )
            glPipeline = gl

            enc.start(encoderListener)

            val cam = Camera2Controller(context, diagnostics)
            cam.start(
                cameraId = cameraCaps.cameraId,
                target = requireNotNull(gl.cameraSurface),
                fps = profile.fps,
                stabilization = s.stabilizationEnabled && cameraCaps.supportsVideoStabilization,
                errorListener = cameraErrorListener,
            )
            camera = cam

            // Optional microphone (never blocks video).
            var micActive = false
            if (s.microphoneEnabled && hasMicPermission()) {
                val a = AudioPipeline(segmentSink, diagnostics)
                micActive = a.start()
                audio = if (micActive) a else null
            }

            // GPS + GPX.
            if (s.gpsEnabled && hasLocationPermission()) {
                gpsManager.start(s.gpsUpdateIntervalMs)
                if (s.gpsWriteGpxTrack) {
                    val trackFile = File(
                        storageLocations.tracksDir,
                        StorageLocations.trackFileName(System.currentTimeMillis()),
                    )
                    gpxWriter = GpxWriter(trackFile).also { it.open("DashCam ${trackFile.name}") }
                }
            }

            activeProfile = profile
            applyPreviewAttachment()
            _status.value = _status.value.copy(
                micActive = micActive,
                // Upright display dimensions — drive the preview aspect ratio.
                outputWidth = FrameGeometry.uprightWidth(profile.width, profile.height, bufferRotation),
                outputHeight = FrameGeometry.uprightHeight(profile.width, profile.height, bufferRotation),
            )
            true
        } catch (e: Exception) {
            diagnostics.log("Orchestrator", "engine start failed: ${e.message}")
            tearDownEngine()
            false
        }
    }

    private fun tearDownEngine() {
        runCatching { camera?.stop() }
        camera = null
        runCatching { audio?.stop() }
        audio = null
        // Stop the encoder before the sink so the final frames drain through.
        runCatching { encoder?.stop() }
        encoder = null
        runCatching { sink?.finish() }
        sink = null
        runCatching { glPipeline?.stop() }
        glPipeline = null
        runCatching { gpxWriter?.close() }
        gpxWriter = null
        runCatching { gpsManager.stop() }
        overlayRenderer = null
    }

    // ------------------------------------------------------------------
    // Segment planning + index
    // ------------------------------------------------------------------

    private fun takePlannedFile(startWallMs: Long): File? {
        if (plannedPermits.getAndUpdate { if (it > 0) it - 1 else 0 } <= 0) return null
        // Refill the permit asynchronously — eviction never blocks the drain thread.
        scope.launch(Dispatchers.IO) { refillPermit() }
        val frontCamera = settings.cameraFacing == com.tunlezah.dashcam.domain.settings.CameraFacing.FRONT
        return File(storageLocations.loopDir, StorageLocations.segmentFileName(startWallMs, frontCamera))
    }

    private suspend fun refillPermit() {
        val s = settings
        val profile = activeProfile ?: return
        val estBytes = estimatedSegmentBytes(profile, s)
        val loopCap = storageManager.effectiveLoopCap(s.loopMaxBytes)
        val ok = storageManager.ensureSpaceForNextSegment(estBytes, loopCap)
        if (ok) {
            plannedPermits.set(min(plannedPermits.get() + 1, 1))
        } else {
            diagnostics.log("Orchestrator", "storage exhausted — cannot plan next segment")
            scope.launch { stopRecordingInternal(RecorderState.STOPPED_STORAGE, "Storage full") }
        }
    }

    private fun estimatedSegmentBytes(profile: RecordingProfile, s: DashcamSettings): Long {
        val videoBytes = profile.bitrateBps.toLong() / 8 * 60 * s.segmentMinutes
        val audioBytes = if (s.microphoneEnabled) AudioPipeline.BITRATE.toLong() / 8 * 60 * s.segmentMinutes else 0
        return (videoBytes + audioBytes) * 11 / 10 // +10% container margin
    }

    private val sinkListener = object : SegmentSink.Listener {
        override fun onSegmentStarted(file: File, startWallMs: Long) {
            val profile = activeProfile
            scope.launch(Dispatchers.IO) {
                val id = segmentDao.insert(
                    SegmentEntity(
                        filePath = file.absolutePath,
                        startWallMs = startWallMs,
                        endWallMs = null,
                        sizeBytes = 0,
                        state = SegmentState.RECORDING,
                        width = profile?.width ?: 0,
                        height = profile?.height ?: 0,
                        codecMime = profile?.codec?.mimeType ?: "",
                        frontCamera = file.name.endsWith("_C.mp4"),
                    )
                )
                synchronized(rowIdByPath) { rowIdByPath[file.absolutePath] = id }
            }
        }

        override fun onSegmentFinalized(file: File, startWallMs: Long, endWallMs: Long, sizeBytes: Long) {
            _status.value = _status.value.copy(segmentsWritten = (sink?.segmentsWritten ?: 0))
            scope.launch(Dispatchers.IO) {
                val id = synchronized(rowIdByPath) { rowIdByPath.remove(file.absolutePath) }
                val row = id?.let { segmentDao.byId(it) }
                if (row != null) {
                    segmentDao.update(
                        row.copy(state = SegmentState.COMPLETE, endWallMs = endWallMs, sizeBytes = sizeBytes)
                    )
                }
                // A thermal profile change waits for a boundary so no frames are lost.
                if (pendingRebuild) {
                    pendingRebuild = false
                    rebuildEngineForProfileChange()
                }
            }
        }

        override fun onSinkStarved() {
            scope.launch { stopRecordingInternal(RecorderState.STOPPED_STORAGE, "Storage full") }
        }

        override fun onSinkError(error: Exception) {
            scope.launch { recoverFromEncoderOrSinkFailure("sink: ${error.message}") }
        }
    }

    private val encoderListener = object : VideoEncoderCore.Listener {
        override fun onOutputFormat(format: MediaFormat) {
            sink?.setVideoFormat(format)
        }

        override fun onEncodedFrame(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            sink?.writeVideoSample(buffer, info)
        }

        override fun onEncoderError(error: Exception) {
            scope.launch { recoverFromEncoderOrSinkFailure("encoder: ${error.message}") }
        }
    }

    private val cameraErrorListener = object : Camera2Controller.ErrorListener {
        override fun onCameraDisconnected() {
            scope.launch { recoverFromCameraFailure("camera disconnected") }
        }

        override fun onCameraError(code: Int) {
            scope.launch { recoverFromCameraFailure("camera error $code") }
        }
    }

    // ------------------------------------------------------------------
    // Supervision: watchdog, thermal, GPS, events, power, overlay ticker
    // ------------------------------------------------------------------

    private fun startSupervisors() {
        stopSupervisors()
        val s = settings

        // Encoder watchdog: recording must produce frames continuously.
        supervisorJobs += scope.launch {
            var lastCount = -1L
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                if (!isRecording) continue
                val count = glPipeline?.framesRendered?.get() ?: 0
                if (count == lastCount) {
                    diagnostics.log("Watchdog", "no frames for ${WATCHDOG_INTERVAL_MS / 1000}s")
                    recoverFromCameraFailure("stalled pipeline (screen-off OEM block or camera stall)")
                }
                lastCount = count
                _status.value = _status.value.copy(framesRendered = count)
            }
        }

        // Thermal mitigation ladder.
        supervisorJobs += scope.launch {
            thermalEngine.snapshot.collect { snap ->
                if (!isRecording) return@collect
                applyThermalMitigations(ThermalPolicy.mitigationsFor(snap.state), snap.platformStatus)
            }
        }

        // Accelerometer → event detection.
        if (s.eventDetectionEnabled && accelerometerFeed.available) {
            eventDetector.sensitivity = s.eventSensitivity
            accelerometerFeed.start()
            supervisorJobs += scope.launch(Dispatchers.Default) {
                accelerometerFeed.samples.collect { sample ->
                    val detection = eventDetector.process(sample)
                    if (detection != null) {
                        diagnostics.log(
                            "Event",
                            "${detection.type} conf=${"%.2f".format(detection.confidence)} " +
                                "peak=${"%.1f".format(detection.peakMagnitude)} protect=${detection.shouldProtect}",
                        )
                        if (detection.shouldProtect) {
                            val gps = gpsManager.state.value
                            eventProtector.protect(
                                type = detection.type,
                                confidence = detection.confidence,
                                peakMagnitude = detection.peakMagnitude,
                                preSeconds = settings.preEventSeconds,
                                postSeconds = settings.postEventSeconds,
                                latitude = gps.latitude.takeIf { !it.isNaN() },
                                longitude = gps.longitude.takeIf { !it.isNaN() },
                                speedMps = gps.speedMps.takeIf { !it.isNaN() },
                                timestampMs = System.currentTimeMillis(),
                            )
                            _status.value = _status.value.copy(lastProtectedEventMs = System.currentTimeMillis())
                        }
                    }
                }
            }
        }

        // GPS consumers: detector corroboration, GPX track, sink location, weather.
        supervisorJobs += scope.launch {
            gpsManager.state.collect { gps ->
                if (!gps.speedMps.isNaN()) {
                    eventDetector.reportSpeed(gps.speedMps, System.currentTimeMillis())
                }
                if (gps.hasFix) {
                    sink?.updateLocation(gps.latitude, gps.longitude, settings.gpsEmbedInVideoMetadata)
                }
            }
        }
        supervisorJobs += scope.launch(Dispatchers.IO) {
            while (true) {
                delay(GPX_POINT_INTERVAL_MS)
                val gps = gpsManager.state.value
                val writer = gpxWriter
                if (writer != null && gps.hasFix) {
                    writer.writePoint(gps.latitude, gps.longitude, gps.altitudeM, gps.speedMps, System.currentTimeMillis())
                }
            }
        }

        // Overlay refresh at 1 Hz (the GL stage uploads only on change).
        supervisorJobs += scope.launch {
            while (true) {
                overlayRenderer?.refresh(settings, gpsManager.state.value, weatherClient.weather.value)
                delay(1000)
            }
        }

        // Weather refresh (optional; cached; never blocks anything).
        supervisorJobs += scope.launch {
            while (true) {
                delay(WEATHER_CHECK_INTERVAL_MS)
                val st = settings
                if (st.weatherEnabled && st.overlayWeather && !weatherPaused) {
                    val gps = gpsManager.state.value
                    if (gps.hasFix) weatherClient.refreshIfNeeded(gps.latitude, gps.longitude)
                }
            }
        }

        // Power behaviour.
        supervisorJobs += scope.launch {
            powerMonitor.events.collect { event ->
                when (event) {
                    PowerEvent.PLUGGED_IN -> {
                        unplugStopJob?.cancel()
                        unplugStopJob = null
                        if (!isRecording && settings.plugInAction == PlugInAction.START_RECORDING) {
                            diagnostics.log("Power", "auto-start on power connect")
                            requestStartRecording()
                        }
                    }
                    PowerEvent.UNPLUGGED -> handleUnplug()
                }
            }
        }
        supervisorJobs += scope.launch {
            powerMonitor.state.collect { p ->
                if (isRecording && !p.isPluggedIn && p.batteryPercent in 1 until settings.batteryFloorPercent) {
                    stopRecordingInternal(
                        RecorderState.STOPPED_BATTERY,
                        "Battery below ${settings.batteryFloorPercent}%",
                    )
                }
            }
        }
    }

    private fun handleUnplug() {
        if (!isRecording) return
        when (settings.unplugAction) {
            UnplugAction.CONTINUE -> Unit
            UnplugAction.STOP_IMMEDIATELY -> scope.launch {
                stopRecordingInternal(RecorderState.IDLE, "Power disconnected")
            }
            UnplugAction.STOP_AFTER_DELAY -> {
                unplugStopJob?.cancel()
                unplugStopJob = scope.launch {
                    delay(settings.unplugStopDelaySeconds * 1000L)
                    if (!powerMonitor.state.value.isPluggedIn) {
                        stopRecordingInternal(RecorderState.IDLE, "Power disconnected")
                    }
                }
            }
            UnplugAction.BATTERY_SAVER_PROFILE -> scope.launch {
                // One profile step-down approximates a battery-saver profile.
                engineMutex.withLock {
                    if (appliedStepDowns == 0) {
                        appliedStepDowns = 1
                        applyProfileStepDowns(1, immediateBitrateOnly = true)
                    }
                }
            }
        }
    }

    private fun stopSupervisors() {
        supervisorJobs.forEach { it.cancel() }
        supervisorJobs.clear()
        accelerometerFeed.stop()
        unplugStopJob?.cancel()
        unplugStopJob = null
    }

    // ------------------------------------------------------------------
    // Thermal mitigation application
    // ------------------------------------------------------------------

    @Volatile
    private var weatherPaused = false

    private suspend fun applyThermalMitigations(m: ThermalMitigations, platformStatus: Int) {
        glPipeline?.setPreviewFpsCap(m.previewFpsCap)
        weatherPaused = m.pauseWeather
        previewAllowedByThermal = !m.detachPreview
        applyPreviewAttachment()

        if (ThermalPolicy.shouldStopForPlatformStatus(platformStatus)) {
            diagnostics.log("Thermal", "platform EMERGENCY — clean stop to preserve footage")
            stopRecordingInternal(RecorderState.STOPPED_THERMAL, "Device critically hot")
            return
        }

        engineMutex.withLock {
            if (m.profileStepDowns != appliedStepDowns) {
                val goingUp = m.profileStepDowns < appliedStepDowns
                appliedStepDowns = m.profileStepDowns
                applyProfileStepDowns(m.profileStepDowns, immediateBitrateOnly = !goingUp)
            }
        }

        val degraded = m.profileStepDowns > 0 || m.detachPreview
        if (isRecording) {
            _status.value = _status.value.copy(
                state = if (degraded) RecorderState.RECORDING_DEGRADED else RecorderState.RECORDING,
                activeProfile = activeProfile,
            )
        }
    }

    /**
     * Applies N step-downs from the base profile. A bitrate-only change is
     * applied live (no gap); a resolution/fps change waits for the next
     * segment boundary and rebuilds the pipeline there.
     */
    private fun applyProfileStepDowns(steps: Int, immediateBitrateOnly: Boolean) {
        val base = baseProfile ?: return
        var target = base
        repeat(steps) { target = ProfileSelector.stepDown(target) ?: target }
        val current = activeProfile ?: return
        if (target == current) return

        val sameGeometry = target.width == current.width && target.height == current.height &&
            target.fps == current.fps
        if (sameGeometry) {
            encoder?.updateBitrate(target.bitrateBps)
            activeProfile = current.copy(bitrateBps = target.bitrateBps)
            diagnostics.log("Thermal", "live bitrate change -> ${target.bitrateBps / 1_000_000} Mbps")
        } else if (immediateBitrateOnly) {
            // Reduce bitrate right now, schedule the geometry change for the boundary.
            encoder?.updateBitrate(target.bitrateBps)
            activeProfile = current.copy(bitrateBps = target.bitrateBps)
            pendingRebuild = true
            diagnostics.log("Thermal", "geometry change queued for segment boundary: ${target.label()}")
        } else {
            pendingRebuild = true
            diagnostics.log("Thermal", "profile restore queued for segment boundary: ${target.label()}")
        }
    }

    private suspend fun rebuildEngineForProfileChange() {
        engineMutex.withLock {
            if (!isRecording) return
            val base = baseProfile ?: return
            var target = base
            repeat(appliedStepDowns) { target = ProfileSelector.stepDown(target) ?: target }
            diagnostics.log("Orchestrator", "rebuilding engine for ${target.label()}")
            tearDownEngine()
            val ok = buildAndStartEngine(target, settings)
            if (!ok) {
                _status.value = _status.value.copy(
                    state = RecorderState.STOPPED_ERROR,
                    statusMessage = "Pipeline rebuild failed",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Recovery
    // ------------------------------------------------------------------

    private suspend fun recoverFromCameraFailure(reason: String) {
        if (!isRecording) return
        if (cameraRetries >= MAX_RETRIES) {
            stopRecordingInternal(RecorderState.STOPPED_ERROR, "Camera failed repeatedly: $reason")
            return
        }
        cameraRetries++
        _status.value = _status.value.copy(state = RecorderState.RECOVERING, statusMessage = reason)
        diagnostics.log("Recovery", "camera recovery #$cameraRetries: $reason")
        delay(backoffMs(cameraRetries))
        engineMutex.withLock {
            tearDownEngine()
            val profile = activeProfile ?: baseProfile ?: return
            if (buildAndStartEngine(profile, settings)) {
                _status.value = _status.value.copy(state = RecorderState.RECORDING, statusMessage = "")
                scope.launch { resetRetriesAfterStablePeriod() }
            } else {
                scope.launch { recoverFromCameraFailure("rebuild failed") }
            }
        }
    }

    private suspend fun recoverFromEncoderOrSinkFailure(reason: String) {
        if (!isRecording) return
        if (encoderRetries >= MAX_RETRIES) {
            stopRecordingInternal(RecorderState.STOPPED_ERROR, "Encoder failed repeatedly: $reason")
            return
        }
        encoderRetries++
        _status.value = _status.value.copy(state = RecorderState.RECOVERING, statusMessage = reason)
        diagnostics.log("Recovery", "encoder recovery #$encoderRetries: $reason")
        delay(backoffMs(encoderRetries))
        engineMutex.withLock {
            tearDownEngine()
            // Second encoder failure at the same profile → step the profile down.
            var profile = activeProfile ?: baseProfile ?: return
            if (encoderRetries >= 2) {
                profile = ProfileSelector.stepDown(profile) ?: profile
                diagnostics.log("Recovery", "stepping profile down to ${profile.label()}")
            }
            if (buildAndStartEngine(profile, settings)) {
                _status.value = _status.value.copy(state = RecorderState.RECORDING, statusMessage = "")
                scope.launch { resetRetriesAfterStablePeriod() }
            } else {
                scope.launch { recoverFromEncoderOrSinkFailure("rebuild failed") }
            }
        }
    }

    private suspend fun resetRetriesAfterStablePeriod() {
        delay(STABLE_RESET_MS)
        if (_status.value.state == RecorderState.RECORDING ||
            _status.value.state == RecorderState.RECORDING_DEGRADED
        ) {
            cameraRetries = 0
            encoderRetries = 0
        }
    }

    private fun backoffMs(attempt: Int): Long = min(1000L shl (attempt - 1), 8000L)

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    private fun applyPreviewAttachment() {
        val gl = glPipeline ?: return
        val surface = previewSurface
        if (surface != null && previewAllowedByThermal) {
            gl.setPreviewSurface(surface, previewW, previewH)
        } else {
            gl.setPreviewSurface(null, 0, 0)
        }
    }

    private fun onSettingsChanged(previous: DashcamSettings, current: DashcamSettings) {
        eventDetector.sensitivity = current.eventSensitivity
        if (isRecording) {
            sink?.configure(current.segmentMinutes, current.gpsEmbedInVideoMetadata)
            glPipeline?.setBurnInOverlay(
                current.overlayMode == OverlayMode.STAMP &&
                    (overlayRenderer?.anyEnabled(current) ?: false)
            )
            if (previous.gpsUpdateIntervalMs != current.gpsUpdateIntervalMs && current.gpsEnabled) {
                gpsManager.updateInterval(current.gpsUpdateIntervalMs)
            }
        }
    }

    /** One-shot gravity sample for mount detection (median of a short burst). */
    private suspend fun sampleGravity(): Pair<Float, Float> {
        if (!accelerometerFeed.available) return 0f to 9.81f
        accelerometerFeed.start()
        val samples = try {
            kotlinx.coroutines.withTimeoutOrNull(1000) {
                val list = ArrayList<Pair<Float, Float>>(8)
                accelerometerFeed.samples.take(8).collect { list.add(it.x to it.y) }
                list
            } ?: emptyList()
        } finally {
            // The event-detection supervisor restarts the feed if it needs it.
            if (!settings.eventDetectionEnabled) accelerometerFeed.stop()
        }
        if (samples.isEmpty()) return 0f to 9.81f
        val mx = samples.map { it.first }.sorted()[samples.size / 2]
        val my = samples.map { it.second }.sorted()[samples.size / 2]
        return mx to my
    }

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val WATCHDOG_INTERVAL_MS = 5_000L
        const val GPX_POINT_INTERVAL_MS = 1_000L
        const val WEATHER_CHECK_INTERVAL_MS = 60_000L
        const val MAX_RETRIES = 5
        const val STABLE_RESET_MS = 10 * 60_000L
    }
}
