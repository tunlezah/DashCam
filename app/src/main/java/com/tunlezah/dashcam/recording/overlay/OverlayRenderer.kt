package com.tunlezah.dashcam.recording.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.tunlezah.dashcam.domain.location.GpsFixQuality
import com.tunlezah.dashcam.domain.location.GpsState
import com.tunlezah.dashcam.domain.settings.DashcamSettings
import com.tunlezah.dashcam.recording.engine.FrameGeometry
import com.tunlezah.dashcam.recording.engine.GlRenderPipeline
import com.tunlezah.dashcam.weather.WeatherInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Renders the overlay strip stamped into the video: a translucent bar
 * carrying, per user toggles, date, time, speed (km/h), GPS coordinates,
 * weather and a custom label.
 *
 * Because recorded frames are the RAW camera buffer with upright display
 * achieved via MP4 rotation metadata (see FrameGeometry), the strip is drawn
 * pre-rotated in buffer space: a canvas transform places upright-strip
 * coordinates (u,v) onto the buffer edge that becomes the bottom of the
 * displayed video after the player applies the rotation. Mappings
 * (pixel-derived, y-down coordinates; W×H = buffer dims, h = strip px):
 *
 *   θ=0:   identity, bottom edge (bitmap W×h)
 *   θ=90:  (u,v) → (v, H−u), right-edge vertical bitmap h×H
 *   θ=180: (u,v) → (W−u, h−v), top edge (bitmap W×h)
 *   θ=270: (u,v) → (h−v, u), left-edge vertical bitmap h×H
 *
 * Performance contract unchanged: [refresh] runs at most once per second; the
 * GL stage re-uploads only when [version] changes.
 */
class OverlayRenderer(
    private val bufferWidth: Int,
    private val bufferHeight: Int,
    private val rotationDegrees: Int,
) : GlRenderPipeline.OverlaySource {

    private val uprightWidth = FrameGeometry.uprightWidth(bufferWidth, bufferHeight, rotationDegrees)
    private val uprightHeight = FrameGeometry.uprightHeight(bufferWidth, bufferHeight, rotationDegrees)

    /** Strip thickness in upright pixels. */
    private val stripHeight = (uprightHeight / 12).coerceAtLeast(48)

    private val sideways = rotationDegrees % 180 != 0
    private val bitmap: Bitmap = if (sideways) {
        Bitmap.createBitmap(stripHeight, bufferHeight, Bitmap.Config.ARGB_8888)
    } else {
        Bitmap.createBitmap(bufferWidth, stripHeight, Bitmap.Config.ARGB_8888)
    }
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
        canvas.save()
        // Map upright-strip coordinates onto the buffer-space bitmap.
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> {
                canvas.translate(0f, bufferHeight.toFloat())
                canvas.rotate(-90f)
            }
            180 -> {
                canvas.translate(bufferWidth.toFloat(), stripHeight.toFloat())
                canvas.rotate(180f)
            }
            270 -> {
                canvas.translate(stripHeight.toFloat(), 0f)
                canvas.rotate(90f)
            }
        }
        drawUprightStrip(settings, gps, weather, nowMs)
        canvas.restore()
        version++
    }

    /** Draws the strip in upright coordinates: width [uprightWidth], height [stripHeight]. */
    private fun drawUprightStrip(
        settings: DashcamSettings,
        gps: GpsState,
        weather: WeatherInfo?,
        nowMs: Long,
    ) {
        canvas.drawRect(0f, 0f, uprightWidth.toFloat(), stripHeight.toFloat(), bgPaint)

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

        // Right side: speed (km/h). Honest about GPS state: "--" without a fix,
        // '~' marks poor accuracy rather than inventing precision.
        if (settings.overlaySpeed) {
            val speedText = when {
                !settings.gpsEnabled || gps.quality == GpsFixQuality.NO_PERMISSION -> ""
                !gps.hasFix || gps.speedKmh.isNaN() -> "-- km/h"
                gps.quality == GpsFixQuality.POOR -> "~${gps.speedKmh.roundToInt()} km/h"
                else -> "${gps.speedKmh.roundToInt()} km/h"
            }
            if (speedText.isNotEmpty()) {
                val w = speedPaint.measureText(speedText)
                canvas.drawText(speedText, uprightWidth - w - pad, baseline, speedPaint)
            }
        }
    }

    /** True when at least one overlay element is enabled. */
    fun anyEnabled(settings: DashcamSettings): Boolean =
        settings.overlaySpeed || settings.overlayGpsCoordinates || settings.overlayDate ||
            settings.overlayTime || settings.overlayWeather || settings.overlayCustomLabel.isNotBlank()

    override fun currentOverlay(): Pair<Bitmap, Long>? =
        if (version == 0L) null else bitmap to version

    override fun overlayRect(): FloatArray =
        FrameGeometry.overlayRectNdc(rotationDegrees, stripHeight.toFloat() / uprightHeight)
}
