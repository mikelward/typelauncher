package app.typelauncher

import android.os.Looper
import java.time.Duration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The activity that turns the screen back on after kiosk mode's own relock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskWakeActivityTest {
    @Test
    fun itShowsOverTheLockTurnsTheScreenOnAndGoesOnceItsWindowIsLaidOut() {
        val controller = Robolectric.buildActivity(KioskWakeActivity::class.java).create().start().resume()
        val activity = controller.get()

        assertTrue(shadowOf(activity).getShowWhenLocked())
        assertTrue(shadowOf(activity).getTurnScreenOn())
        // Not while resuming: Android never adds the window of an activity
        // that finished there, and the window is what wakes the screen.
        assertFalse(activity.isFinishing)

        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertTrue(LauncherDebugLog.snapshot().none { "never drew" in it })
        controller.destroy()
    }

    @Test
    fun aWindowThatNeverDrawsStillGoesAfterTheCap() {
        val controller = Robolectric.buildActivity(KioskWakeActivity::class.java).create().start().resume()
        val activity = controller.get()

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KIOSK_WAKE_FINISH_CAP_MS - 1))
        assertFalse(activity.isFinishing)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

        assertTrue("a transparent activity can't sit over the display", activity.isFinishing)
        assertTrue(LauncherDebugLog.snapshot().any { "kiosk lock: wake activity never drew" in it })
        controller.destroy()
    }
}
