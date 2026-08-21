package com.tunlezah.dashcam.recording

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tunlezah.dashcam.DashCamApplication
import com.tunlezah.dashcam.R
import com.tunlezah.dashcam.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The foreground service that represents the recording pipeline to Android.
 * Declares camera|location(|microphone) FGS types (Android 14+ requirement);
 * MUST be started from a visible activity — background camera-FGS starts are
 * forbidden by the platform (docs/research §3).
 *
 * Holds a PARTIAL_WAKE_LOCK for the life of the recording: a foreground
 * service alone does not keep the CPU awake with the screen off.
 */
class RecordingService : Service() {

    private val orchestrator: RecordingOrchestrator
        get() = (application as DashCamApplication).graph.orchestrator

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var statusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                orchestrator.requestStopRecording()
                stopSelfClean()
                return START_NOT_STICKY
            }
            ACTION_PROTECT -> {
                orchestrator.protectNow()
                return START_NOT_STICKY
            }
        }

        createChannel()
        val micEnabled = intent?.getBooleanExtra(EXTRA_WITH_MIC, false) ?: false
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"), fgsTypes(micEnabled))
        acquireWakeLock()
        orchestrator.requestStartRecording()
        observeStatus()
        // If the process is killed, Android may restart the service, but a
        // camera FGS cannot begin from the background — the notification the
        // restart posts becomes the user's tap-to-resume affordance.
        return START_NOT_STICKY
    }

    private fun fgsTypes(withMic: Boolean): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (withMic && hasPermission(Manifest.permission.RECORD_AUDIO)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        return types
    }

    private fun observeStatus() {
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            orchestrator.status.collect { status ->
                val text = when (status.state) {
                    RecorderState.COUNTDOWN -> "Starting in ${status.countdownSeconds}s"
                    RecorderState.RECORDING -> "Recording ${status.activeProfile?.label() ?: ""}"
                    RecorderState.RECORDING_DEGRADED -> "Recording (reduced quality — device warm)"
                    RecorderState.RECOVERING -> "Recovering: ${status.statusMessage}"
                    RecorderState.STOPPED_ERROR -> "Stopped: ${status.statusMessage}"
                    RecorderState.STOPPED_STORAGE -> "Stopped: storage full"
                    RecorderState.STOPPED_THERMAL -> "Stopped: device too hot"
                    RecorderState.STOPPED_BATTERY -> "Stopped: ${status.statusMessage}"
                    else -> "Idle"
                }
                notify(buildNotification(text))
                if (status.state == RecorderState.IDLE) stopSelfClean()
            }
        }
    }

    private fun stopSelfClean() {
        statusJob?.cancel()
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashCam:recording").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_recording),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { setShowBadge(false) }
        )
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val protectIntent = PendingIntent.getService(
            this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_PROTECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.notification_action_protect), protectIntent)
            .addAction(0, getString(R.string.notification_action_stop), stopIntent)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.tunlezah.dashcam.action.STOP"
        const val ACTION_PROTECT = "com.tunlezah.dashcam.action.PROTECT"
        const val EXTRA_WITH_MIC = "with_mic"

        /** 12 h hard ceiling — a safety net, not an expected runtime. */
        const val MAX_WAKELOCK_MS = 12 * 60 * 60 * 1000L

        fun start(context: Context, withMic: Boolean) {
            val intent = Intent(context, RecordingService::class.java)
                .putExtra(EXTRA_WITH_MIC, withMic)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
