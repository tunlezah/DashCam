package com.tunlezah.dashcam.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** GPS fix quality shown in the UI/diagnostics. */
enum class GpsFixQuality { NO_PERMISSION, DISABLED, SEARCHING, POOR, GOOD }

data class GpsState(
    val quality: GpsFixQuality = GpsFixQuality.SEARCHING,
    val latitude: Double = Double.NaN,
    val longitude: Double = Double.NaN,
    /** Smoothed Doppler speed, m/s; NaN when unknown. */
    val speedMps: Float = Float.NaN,
    /** Raw last speed, m/s. */
    val rawSpeedMps: Float = Float.NaN,
    val accuracyM: Float = Float.NaN,
    val bearingDeg: Float = Float.NaN,
    val altitudeM: Double = Double.NaN,
    val satellitesUsed: Int = 0,
    val satellitesVisible: Int = 0,
    val fixTimeMs: Long = 0,
) {
    val hasFix: Boolean get() = quality == GpsFixQuality.GOOD || quality == GpsFixQuality.POOR
    val speedKmh: Float get() = if (speedMps.isNaN()) Float.NaN else speedMps * 3.6f
}

/**
 * Pure-GNSS location source using LocationManager's GPS provider directly:
 * works with no SIM, no network, no Play services (docs/research §7).
 * Recording NEVER depends on this — GPS loss only degrades overlays/track.
 *
 * Speed is Doppler-derived from the receiver ([Location.getSpeed]), smoothed
 * with a light exponential filter and zero-clamped below walking pace so a
 * stationary car doesn't display jitter (1–2 km/h noise is normal).
 */
class GpsManager(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    private val locationManager get() =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _state = MutableStateFlow(GpsState())
    val state: StateFlow<GpsState> = _state

    private var thread: HandlerThread? = null
    private var smoothedSpeed = Float.NaN
    private var visibleSats = 0
    private var usedSats = 0

    private val listener = LocationListener { location -> onLocation(location) }

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            visibleSats = status.satelliteCount
            usedSats = (0 until status.satelliteCount).count { status.usedInFix(it) }
            _state.value = _state.value.copy(
                satellitesUsed = usedSats,
                satellitesVisible = visibleSats,
            )
        }
    }

    val isProviderEnabled: Boolean
        get() = runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }
            .getOrDefault(false)

    @SuppressLint("MissingPermission") // caller checks; SecurityException handled
    fun start(updateIntervalMs: Long) {
        stop()
        if (!isProviderEnabled) {
            _state.value = _state.value.copy(quality = GpsFixQuality.DISABLED)
            diagnostics.log("GPS", "location provider disabled")
            return
        }
        val t = HandlerThread("GpsFeed").also { it.start() }
        thread = t
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, updateIntervalMs, 0f, listener, t.looper
            )
            locationManager.registerGnssStatusCallback(gnssCallback, Handler(t.looper))
            _state.value = _state.value.copy(quality = GpsFixQuality.SEARCHING)
            diagnostics.log("GPS", "started, interval=${updateIntervalMs}ms")
        } catch (e: SecurityException) {
            _state.value = _state.value.copy(quality = GpsFixQuality.NO_PERMISSION)
            diagnostics.log("GPS", "no location permission: ${e.message}")
        }
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(listener) }
        runCatching { locationManager.unregisterGnssStatusCallback(gnssCallback) }
        thread?.quitSafely()
        thread = null
    }

    /** Restart with a different interval (thermal mitigation). */
    fun updateInterval(updateIntervalMs: Long) {
        if (thread != null) start(updateIntervalMs)
    }

    private fun onLocation(location: Location) {
        val raw = if (location.hasSpeed()) location.speed else Float.NaN
        smoothedSpeed = when {
            raw.isNaN() -> smoothedSpeed
            smoothedSpeed.isNaN() -> raw
            else -> smoothedSpeed + SPEED_ALPHA * (raw - smoothedSpeed)
        }
        // Zero-clamp below walking pace: stationary GNSS speed noise is ~0.3 m/s.
        val display = if (!smoothedSpeed.isNaN() && smoothedSpeed < STATIONARY_MPS) 0f else smoothedSpeed

        val accuracy = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val quality = when {
            accuracy.isNaN() -> GpsFixQuality.POOR
            accuracy <= GOOD_ACCURACY_M -> GpsFixQuality.GOOD
            else -> GpsFixQuality.POOR
        }

        _state.value = GpsState(
            quality = quality,
            latitude = location.latitude,
            longitude = location.longitude,
            speedMps = display,
            rawSpeedMps = raw,
            accuracyM = accuracy,
            bearingDeg = if (location.hasBearing()) location.bearing else Float.NaN,
            altitudeM = if (location.hasAltitude()) location.altitude else Double.NaN,
            satellitesUsed = usedSats,
            satellitesVisible = visibleSats,
            fixTimeMs = location.time,
        )
    }

    companion object {
        const val SPEED_ALPHA = 0.4f
        const val STATIONARY_MPS = 0.7f

        /** Horizontal accuracy at/below which the fix is considered good. */
        const val GOOD_ACCURACY_M = 25f
    }
}
