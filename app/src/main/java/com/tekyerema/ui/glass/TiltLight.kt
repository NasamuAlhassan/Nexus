package com.tekyerema.ui.glass

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Liquid glass's light responds to motion (WWDC25 "Meet Liquid Glass"): tilt the phone and the rim's light slides with it,
 * up to ±20°, smoothed. The sensor is on only while a glass surface is showing and only when [enabled] — the FULL glass
 * tier with motion allowed (a 3 GB phone, power saving or calm motion keep the light still).
 */
@Composable
fun rememberTiltLight(enabled: Boolean): State<Float> {
    val ctx = LocalContext.current
    val angle = remember { mutableFloatStateOf(DEFAULT_LIGHT_DEGREES) }
    DisposableEffect(enabled) {
        if (!enabled) { angle.floatValue = DEFAULT_LIGHT_DEGREES; return@DisposableEffect onDispose { } }
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var x = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                x += (e.values[0] - x) * 0.12f                          // low-pass ≈ 150 ms at SENSOR_DELAY_UI
                angle.floatValue = DEFAULT_LIGHT_DEGREES + (x / SensorManager.GRAVITY_EARTH * 20f).coerceIn(-20f, 20f)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(listener) }
    }
    return angle
}
