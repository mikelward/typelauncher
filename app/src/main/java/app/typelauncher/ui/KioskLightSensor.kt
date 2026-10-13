package app.typelauncher

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** What the light sensor last said, for the Settings row that sets the light level. */
internal sealed interface KioskLightReading {
    /** No reading yet. */
    data object Waiting : KioskLightReading

    /** The device has no light sensor, or it would not report. */
    data object NoSensor : KioskLightReading

    data class Lux(val lux: Float) : KioskLightReading
}

/**
 * "Wake up using light level": calls [onLitChanged] with whether the room is
 * lit by [thresholdLux] (see [KioskRoomLightTracker]), once for the first
 * reading and then each time it changes, while this is in composition. A room
 * is only lit while the sensor is reporting it: when listening fails to start,
 * it counts as dark, so a lit reading from before can't hold the display awake
 * for good. The sensor needs no permission, and its readings are compared here
 * and dropped; nothing is kept or sent.
 */
@Composable
internal fun KioskRoomLight(thresholdLux: Float, onLitChanged: (Boolean) -> Unit) {
    // A fresh tracker per level, so a changed level starts over from its first reading.
    val tracker = remember(thresholdLux) { KioskRoomLightTracker(thresholdLux) }
    LightSensorReadings(
        key = tracker,
        onReading = { lux -> tracker.onReading(lux)?.let(onLitChanged) },
        onUnavailable = {
            // Not an error: plenty of tablets have no light sensor. The display
            // still wakes on a touch, and on motion when camera wake is on.
            LauncherDebugLog.warning("KioskRoomLight: light sensor unavailable")
            tracker.onUnavailable()?.let(onLitChanged)
        },
    )
}

/** The light sensor's current reading while in composition, for showing beside the light level. */
@Composable
internal fun rememberKioskLightReading(): State<KioskLightReading> {
    val reading = remember { mutableStateOf<KioskLightReading>(KioskLightReading.Waiting) }
    LightSensorReadings(
        key = Unit,
        onReading = { lux -> reading.value = KioskLightReading.Lux(lux) },
        onUnavailable = { reading.value = KioskLightReading.NoSensor },
    )
    return reading
}

/**
 * Listens to the ambient light sensor while in composition and the screen is
 * started, restarting when [key] changes. Stopping the screen (an app opened
 * from a widget, the screen turned off) stops listening until it starts again.
 * [onReading] gets each reading in lux and [onUnavailable] runs when
 * listening fails to start (no sensor, or it won't register), once until a
 * start succeeds again, both on the main thread and never after
 * this leaves composition, so a reading still in flight then can't act on a
 * screen that has moved on. Looking the sensor up and registering are binder
 * calls, so those happen on a thread of the sensor's own, never the main one.
 */
@Composable
private fun LightSensorReadings(key: Any, onReading: (Float) -> Unit, onUnavailable: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnReading by rememberUpdatedState(onReading)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    DisposableEffect(context, lifecycleOwner, key) {
        val thread = HandlerThread("KioskLightSensor").apply { start() }
        val handler = Handler(thread.looper)
        val mainExecutor = ContextCompat.getMainExecutor(context)
        // Main-thread only: cleared on disposal, which also runs there, so a
        // delivery checked against it is never stale.
        var active = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values[0]
                mainExecutor.execute { if (active) currentOnReading(lux) }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // Only touched on the sensor thread.
        var sensorManager: SensorManager? = null
        var registered = false
        var reportedUnavailable = false
        fun register() = handler.post {
            if (registered) return@post
            val manager = sensorManager ?: context.getSystemService(SensorManager::class.java)
            sensorManager = manager
            val sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
            registered = sensor != null &&
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
            if (registered) {
                reportedUnavailable = false
            } else if (!reportedUnavailable) {
                reportedUnavailable = true
                mainExecutor.execute { if (active) currentOnUnavailable() }
            }
        }
        fun unregister() = handler.post {
            if (!registered) return@post
            sensorManager?.unregisterListener(listener)
            registered = false
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        // Adding it replays ON_START when the screen is already started.
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            active = false
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
            // Runs the unregister above before the thread ends.
            thread.quitSafely()
        }
    }
}
