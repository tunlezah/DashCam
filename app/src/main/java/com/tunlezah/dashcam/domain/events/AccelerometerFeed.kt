package com.tunlezah.dashcam.domain.events

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Streams accelerometer samples on a dedicated handler thread (event detection
 * math must never contend with the UI or camera threads). ~50 Hz
 * (SENSOR_DELAY_GAME) is ample for impact signatures while staying cheap.
 */
class AccelerometerFeed(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _samples = MutableSharedFlow<AccelSample>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val samples: SharedFlow<AccelSample> = _samples

    val available: Boolean get() = accelerometer != null

    private var thread: HandlerThread? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            _samples.tryEmit(
                AccelSample(
                    timestampMs = event.timestamp / 1_000_000,
                    x = event.values[0],
                    y = event.values[1],
                    z = event.values[2],
                )
            )
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val sensor = accelerometer ?: return
        if (thread != null) return
        val t = HandlerThread("AccelFeed").also { it.start() }
        thread = t
        sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(t.looper)
        )
    }

    fun stop() {
        sensorManager.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
    }
}
