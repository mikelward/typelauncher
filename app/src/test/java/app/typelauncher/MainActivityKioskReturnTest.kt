package app.typelauncher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
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
        val controller = launchActivity()
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
        val controller = launchActivity()
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
        val controller = launchActivity()
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
        val controller = launchActivity()
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

    @Test
    fun theScreenGoingOffBringsTheDisplayBackOverAnAppInFront() {
        val controller = launchActivity()
        try {
            val activity = controller.get()
            stepOut(activity)
            // An app opened from Home is in front.
            controller.pause().stop()
            shadowOf(activity).clearNextStartedActivities()

            screenOff(activity)

            val started = shadowOf(activity).nextStartedActivity
            assertTrue("the launcher comes back in front", started != null)
            assertTrue(started.hasCategory(Intent.CATEGORY_HOME))
            assertTrue(started.`package` == activity.packageName)
            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
            // Only that it was sent: Android can drop a blocked background start and
            // still report success, so nothing here may claim it arrived (Codex on #813).
            val log = LauncherDebugLog.snapshot()
            assertTrue(log.any { "kiosk screen off: bring forward sent" in it })
            assertTrue(log.none { "brought forward" in it })
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aCallDoesNotKeepTheDisplayBack() {
        // Kiosk mode always wakes to its display, a call included (maintainer, 2026-10-17).
        val controller = launchActivity()
        try {
            val activity = controller.get()
            stepOut(activity)
            controller.pause().stop()
            shadowOf(activity).clearNextStartedActivities()
            activity.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL

            screenOff(activity)

            assertTrue(shadowOf(activity).nextStartedActivity?.hasCategory(Intent.CATEGORY_HOME) == true)
            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun withoutKioskModeTheScreenGoingOffBringsNothingForward() {
        val controller = launchActivity()
        try {
            val activity = controller.get()
            controller.pause().stop()
            shadowOf(activity).clearNextStartedActivities()

            screenOff(activity)

            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }

    /** The launcher, its off-main-thread work run inline so each step has landed before the next assertion. */
    private fun launchActivity(): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        return controller.setup()
    }

    private fun screenOff(activity: MainActivity) {
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF).setPackage(activity.packageName))
        idle()
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
