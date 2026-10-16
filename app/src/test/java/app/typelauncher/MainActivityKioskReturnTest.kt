package app.typelauncher

import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The kiosk display comes back by itself from the Home its hold stepped out
 * to: after a quiet minute there, and when the screen goes off. Drives the
 * real activity, since what starts and stops the minute is its lifecycle and
 * its touches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityKioskReturnTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun aQuietMinuteOnTheSteppedOutHomeBringsTheDisplayBack() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            stepOut(activity)

            idleFor(KIOSK_RETURN_AFTER_MS - 10_000)
            // A touch here starts the minute over.
            activity.onUserInteraction()
            idleFor(KIOSK_RETURN_AFTER_MS - 10_000)
            assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)

            idleFor(10_001)
            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun timeWithAnAppInFrontIsNotQuiet() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            stepOut(activity)

            controller.pause().stop()
            idleFor(KIOSK_RETURN_AFTER_MS * 5)
            assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)

            // Back on screen, the minute starts afresh.
            controller.start().resume()
            idleFor(KIOSK_RETURN_AFTER_MS - 1_000)
            assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)
            idleFor(2_000)
            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun timeUnderATranslucentActivityIsNotQuiet() {
        // A translucent activity or a dialog over Home pauses it without stopping it.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            stepOut(activity)

            controller.pause()
            idleFor(KIOSK_RETURN_AFTER_MS * 5)
            assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)

            controller.resume()
            idleFor(KIOSK_RETURN_AFTER_MS + 1_000)
            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theScreenGoingOffBringsTheDisplayBackOverTheLockScreen() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            stepOut(activity)
            assertFalse(shadowOf(activity).showWhenLocked)

            // The screen going off stops the activity, composition with it.
            controller.pause().stop()
            activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF).setPackage(activity.packageName))
            idle()

            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertTrue("the device wakes to the display", shadowOf(activity).showWhenLocked)
        } finally {
            controller.destroy()
        }
    }

    private fun stepOut(activity: MainActivity) {
        activity.viewModel.setHomeWidgetsShown(true)
        activity.viewModel.setKioskMode(true)
        activity.viewModel.stepOutOfKiosk()
        idle()
        assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
