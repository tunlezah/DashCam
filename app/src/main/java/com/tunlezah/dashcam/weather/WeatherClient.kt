package com.tunlezah.dashcam.weather

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tunlezah.dashcam.core.DiagnosticsLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class WeatherInfo(
    val temperatureC: Float,
    val weatherCode: Int,
    val description: String,
    val fetchedAtMs: Long,
)

/**
 * Optional weather for the overlay, via Open-Meteo (no API key — nothing
 * secret to commit; https://open-meteo.com, free for non-commercial use).
 *
 * Design constraints from the brief (§19):
 *  - the app must work perfectly with no connectivity: every failure path
 *    returns null and the overlay simply omits weather
 *  - no battery-wasting retry loops: at most one attempt per
 *    [MIN_FETCH_INTERVAL_MS]; a cached value serves for [CACHE_TTL_MS]
 *  - never on the recording path: callers fire-and-forget from their own scope
 */
class WeatherClient(
    private val context: Context,
    private val diagnostics: DiagnosticsLog,
) {

    private val _weather = MutableStateFlow<WeatherInfo?>(null)
    val weather: StateFlow<WeatherInfo?> = _weather

    private var lastAttemptMs = 0L

    suspend fun refreshIfNeeded(latitude: Double, longitude: Double, nowMs: Long = System.currentTimeMillis()) {
        val cached = _weather.value
        if (cached != null && nowMs - cached.fetchedAtMs < CACHE_TTL_MS) return
        if (nowMs - lastAttemptMs < MIN_FETCH_INTERVAL_MS) return
        if (!hasInternet()) return
        lastAttemptMs = nowMs

        val result = withContext(Dispatchers.IO) { fetch(latitude, longitude, nowMs) }
        if (result != null) {
            _weather.value = result
            diagnostics.log("Weather", "updated: ${result.temperatureC}°C ${result.description}")
        }
    }

    private fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun fetch(latitude: Double, longitude: Double, nowMs: Long): WeatherInfo? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.US, latitude, longitude) +
                "&current=temperature_2m,weather_code"
        )
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        try {
            val body = connection.inputStream.bufferedReader().readText()
            val current = Json.parseToJsonElement(body).jsonObject["current"]?.jsonObject ?: return null
            val temp = current["temperature_2m"]?.jsonPrimitive?.content?.toFloatOrNull() ?: return null
            val code = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            WeatherInfo(temp, code, describe(code), nowMs)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        const val MIN_FETCH_INTERVAL_MS = 5 * 60 * 1000L

        /** WMO weather interpretation codes used by Open-Meteo. */
        fun describe(code: Int): String = when (code) {
            0 -> "Clear"
            1, 2 -> "Partly cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77 -> "Snow"
            in 80..82 -> "Showers"
            85, 86 -> "Snow showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "—"
        }
    }
}
