package app.typelauncher

import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.SensorEventBuilder
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSensor

/**
 * The light sensor behind "Wake up using light level" is read only while the
 * screen showing it is started: an app opened from a widget, or the screen
 * turning off, stops it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskLightSensorTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val sensorManager get() = composeRule.activity.getSystemService(SensorManager::class.java)

    @Before
    fun addLightSensor() {
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_LIGHT))
    }

    // Robolectric keeps this switch in a static, so it would outlive the test.
    @After
    fun letListenersRegisterAgain() {
        shadowOf(sensorManager).setForceListenersToFail(false)
    }

    /** Lets the sensor thread finish what it was posted, so the count below is settled. */
    private fun listeners(): Int {
        composeRule.waitForIdle()
        ShadowLooper.getAllLoopers()
            .filter { it != Looper.getMainLooper() && it.thread.name == "KioskLightSensor" }
            .forEach { shadowOf(it).idle() }
        return shadowOf(sensorManager).listeners.size
    }

    @Test
    fun theSensorIsReleasedWhileTheScreenIsStopped() {
        composeRule.setContent { KioskRoomLight(thresholdLux = 20f, onLitChanged = {}) }
        assertEquals(1, listeners())

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0, listeners())

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(1, listeners())
    }

    @Test
    fun aReadingInFlightWhenTheDisplayGoesAwayIsDropped() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        val litChanges = mutableListOf<Boolean>()
        composeRule.setContent {
            if (shown) KioskRoomLight(thresholdLux = 20f, onLitChanged = { litChanges += it })
        }
        assertEquals(1, listeners())
        val listener = shadowOf(sensorManager).listeners.single()
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        shown = false
        assertEquals(0, listeners())
        // A reading the sensor thread was already handling when the setting
        // went off: it must not hold a display that nothing would release.
        listener.onSensorChanged(SensorEventBuilder.newBuilder(sensor, floatArrayOf(150f)).build())
        composeRule.waitForIdle()

        assertEquals(emptyList<Boolean>(), litChanges)
    }

    @Test
    fun readingsReachTheDisplayWhileItShows() {
        val litChanges = mutableListOf<Boolean>()
        composeRule.setContent { KioskRoomLight(thresholdLux = 20f, onLitChanged = { litChanges += it }) }
        assertEquals(1, listeners())
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)

        shadowOf(sensorManager).sendSensorEventToListeners(SensorEventBuilder.newBuilder(sensor, floatArrayOf(150f)).build())
        composeRule.waitForIdle()

        assertEquals(listOf(true), litChanges)
    }

    @Test
    fun aSensorThatWontStartAgainReleasesALitRoom() {
        val litChanges = mutableListOf<Boolean>()
        composeRule.setContent { KioskRoomLight(thresholdLux = 20f, onLitChanged = { litChanges += it }) }
        assertEquals(1, listeners())
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        shadowOf(sensorManager).sendSensorEventToListeners(SensorEventBuilder.newBuilder(sensor, floatArrayOf(150f)).build())
        composeRule.waitForIdle()
        assertEquals(listOf(true), litChanges)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertEquals(0, listeners())
        shadowOf(sensorManager).setForceListenersToFail(true)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        assertEquals(0, listeners())
        composeRule.waitForIdle()

        // Nothing is reporting the room lit any more, so it no longer holds
        // the display awake; the usual quiet period applies.
        assertEquals(listOf(true, false), litChanges)
    }

    @Test
    fun theSensorIsReleasedWhenTheDisplayGoesAway() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        composeRule.setContent {
            if (shown) KioskRoomLight(thresholdLux = 20f, onLitChanged = {})
        }
        assertEquals(1, listeners())

        shown = false
        assertEquals(0, listeners())
    }
}
