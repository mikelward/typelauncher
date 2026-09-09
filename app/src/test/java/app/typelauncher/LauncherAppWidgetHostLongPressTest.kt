package app.typelauncher

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the widget long-press timer's lifecycle across the
 * `requestDisallowInterceptTouchEvent` boundary. The timer is armed in
 * `onInterceptTouchEvent` on ACTION_DOWN — but once a scrollable descendant
 * (a widget's ListView/GridView) claims the gesture via
 * `requestDisallowInterceptTouchEvent(true)`, the framework stops calling
 * `onInterceptTouchEvent` for the rest of that gesture, so its MOVE-past-slop
 * and UP/CANCEL cancellation branches never run. The timer must therefore
 * disarm the moment the child claims the gesture (otherwise it fires while
 * the finger is still down on the child, or after a quick lift) with
 * `dispatchTouchEvent`'s UP/CANCEL removal as the gesture-end backstop —
 * before those, the widget actions menu opened on its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostLongPressTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val longPressTimeout = Duration.ofMillis(
        ViewConfiguration.getLongPressTimeout().toLong() + 100,
    )

    // The long-press runnable only fires with a non-null parent and only
    // schedules once the view has a handler, so host the view in a real
    // (Robolectric) activity window.
    private fun attachedView(): LauncherAppWidgetHostView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val host = LauncherAppWidgetHost(activity, hostId = 1)
        val view = LauncherAppWidgetHostView(activity, host = host)
        activity.setContentView(view)
        shadowOf(Looper.getMainLooper()).idle()
        return view
    }

    private fun motionEvent(action: Int, x: Float = 0f, y: Float = 0f): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    @Test
    fun stationaryPressFiresLongPress() {
        // Positive control so the regression test below can't pass vacuously:
        // an uninterrupted stationary press must still open the menu.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals(1, longPresses)
    }

    @Test
    fun childClaimingGestureDisarmsLongPressTimerWhileFingerStaysDown() {
        // Regression: catch a fling and keep the finger down (hold, or drag
        // the caught list). The child claims the gesture at DOWN and every
        // further MOVE bypasses onInterceptTouchEvent, so nothing that used to
        // cancel the timer runs while the press outlives the long-press
        // timeout — before the disallow-time disarm, the widget actions menu
        // popped open mid-scroll.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.requestDisallowInterceptTouchEvent(true)
        // No UP: the finger is still down on the child past the timeout.
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals("long press must not fire while a child owns the gesture", 0, longPresses)
    }

    @Test
    fun liftingFingerAfterChildClaimsGestureCancelsLongPressTimer() {
        // Regression: tap a widget's list to catch a fling. AbsListView calls
        // requestDisallowInterceptTouchEvent(true) at DOWN, so the UP never
        // reaches onInterceptTouchEvent — before the dispatchTouchEvent
        // cancellation, the timer armed at DOWN fired ~400ms after the finger
        // lifted and popped the widget actions menu with a haptic.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.requestDisallowInterceptTouchEvent(true)
        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals("long press must not fire after the finger lifted", 0, longPresses)
    }

    @Test
    fun unconsumedDownIsClaimedSoTheGestureStreamKeepsFlowing() {
        // Regression for the dead-area flavor of the spurious menu: when the
        // DOWN lands on a non-interactive region of the widget (no descendant
        // consumes it), the host view must claim the gesture itself.
        // Returning false here put Compose's pointer interop into its
        // NotDispatching state, after which the view received no MOVE, no UP,
        // and no synthesized CANCEL — so the timer armed at DOWN fired blind
        // ~400ms after a plain tap or mid-swipe.
        val view = attachedView()

        assertTrue(
            "a DOWN no child consumed must be claimed by the host view",
            view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN)),
        )
    }

    @Test
    fun dragPastSlopOnDeadAreaCancelsLongPressTimer() {
        // Regression: a gesture the view consumed itself (dead area — no
        // descendant touch target) routes MOVEs straight to onTouchEvent
        // without consulting onInterceptTouchEvent, so intercept's
        // MOVE-past-slop cancellation never ran and a slow drag popped the
        // menu mid-drag once it outlived the long-press timeout.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }
        val pastSlop = ViewConfiguration.get(context).scaledTouchSlop + 10f

        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_MOVE, x = pastSlop))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals("long press must not fire after the drag crossed slop", 0, longPresses)
    }

    @Test
    fun stationaryPressOnDeadAreaStillFiresLongPress() {
        // Positive control for the dead-area claim: long-pressing anywhere on
        // the widget — including non-interactive regions — must still open
        // the menu.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals(1, longPresses)
    }

    @Test
    fun tapOnDeadAreaDoesNotFireLongPress() {
        // The release of a short dead-area tap must disarm the timer via the
        // dispatchTouchEvent backstop — reachable only because the DOWN claim
        // above keeps the event stream flowing to this view.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_UP))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals("long press must not fire after the finger lifted", 0, longPresses)
    }

    @Test
    fun cancelAfterChildClaimsGestureCancelsLongPressTimer() {
        // Same boundary, ACTION_CANCEL flavor: the system stealing the gesture
        // must also disarm the timer.
        val view = attachedView()
        var longPresses = 0
        view.setOnWidgetLongPressListener { longPresses++ }

        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.requestDisallowInterceptTouchEvent(true)
        view.dispatchTouchEvent(motionEvent(MotionEvent.ACTION_CANCEL))
        shadowOf(Looper.getMainLooper()).idleFor(longPressTimeout)

        assertEquals("long press must not fire after the gesture was canceled", 0, longPresses)
    }
}
