package com.tunlezah.dashcam.recording.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tunlezah.dashcam.domain.location.GpsFixQuality
import com.tunlezah.dashcam.domain.location.GpsState
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.recording.engine.GlRenderPipeline
import com.tunlezah.dashcam.weather.WeatherInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders the overlay strip stamped into the video (and mirrored on the
 * preview). A translucent bar across the bottom of the frame carrying, per
 * user toggles: date, time, speed (km/h), GPS coordinates, weather, custom
 * label — matching hardware-dashcam conventions.
 *
 * Performance contract: [refresh] is called at most once per second from the
 * orchestrator (never per frame); the GL stage re-uploads only when [version]
 * changes. The strip is 1/12 of frame height, so at 1080p it's a 1920×90
 * ARGB bitmap — a ~0.7 MB upload per second, negligible.
 */
class OverlayRenderer(
    private val outputWidth: Int,
    private val outputHeight: Int,
) : GlRenderPipeline.OverlaySource {

    private val stripHeight = (outputHeight / 12).coerceAtLeast(48)
    private val bitmap: Bitmap = Bitmap.createBitmap(outputWidth, stripHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    @Volatile
    private var version = 0L

    private val bgPaint = Paint().apply { color = Color.argb(96, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = stripHeight * 0.42f
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val speedPaint = Paint(textPaint).apply { textSize = stripHeight * 0.58f }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun refresh(
        settings: DashcamSettings,
        gps: GpsState,
        weather: WeatherInfo?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        canvas.drawRect(0f, 0f, outputWidth.toFloat(), stripHeight.toFloat(), bgPaint)

        val baseline = stripHeight * 0.68f
        val pad = stripHeight * 0.3f
        var x = pad

        // Left side: date/time + custom label.
        val leftParts = buildList {
            if (settings.overlayDate) add(dateFormat.format(Date(nowMs)))
            if (settings.overlayTime) add(timeFormat.format(Date(nowMs)))
            if (settings.overlayCustomLabel.isNotBlank()) add(settings.overlayCustomLabel)
        }
        if (leftParts.isNotEmpty()) {
            val text = leftParts.joinToString("  ")
            canvas.drawText(text, x, baseline, textPaint)
            x += textPaint.measureText(text) + pad * 2
        }

        // Middle: GPS coordinates and weather.
        val midParts = buildList {
            if (settings.overlayGpsCoordinates && gps.hasFix) {
                add("%.5f %.5f".format(Locale.US, gps.latitude, gps.longitude))
            }
            if (settings.overlayWeather && weather != null) {
                add("${weather.temperatureC.roundToInt()}°C ${weather.description}")
            }
        }
        if (midParts.isNotEmpty()) {
            canvas.drawText(midParts.joinToString("  "), x, baseline, textPaint)
        }

        // Right side: speed (km/h). Honest about GPS state: shows "--" without a
        // fix and marks poor accuracy with '~' rather than inventing precision.
        if (settings.overlaySpeed) {
            val speedText = when {
                !settings.gpsEnabled || gps.quality == GpsFixQuality.NO_PERMISSION -> ""
                !gps.hasFix || gps.speedKmh.isNaN() -> "-- km/h"
                gps.quality == GpsFixQuality.POOR -> "~${gps.speedKmh.roundToInt()} km/h"
                else -> "${gps.speedKmh.roundToInt()} km/h"
            }
            if (speedText.isNotEmpty()) {
                val w = speedPaint.measureText(speedText)
                canvas.drawText(speedText, outputWidth - w - pad, baseline, speedPaint)
            }
        }

        version++
    }

    /** True when at least one overlay element is enabled. */
    fun anyEnabled(settings: DashcamSettings): Boolean =
        settings.overlaySpeed || settings.overlayGpsCoordinates || settings.overlayDate ||
            settings.overlayTime || settings.overlayWeather || settings.overlayCustomLabel.isNotBlank()

    override fun currentOverlay(): Pair<Bitmap, Long>? =
        if (version == 0L) null else bitmap to version

    override fun overlayRect(): FloatArray {
        // Bottom strip in normalized device coordinates: full width, height 1/6
        // of clip space (= 1/12 of frame height since NDC spans 2).
        val h = 2f * stripHeight / outputHeight
        return floatArrayOf(-1f, -1f, 1f, -1f + h)
    }
}
