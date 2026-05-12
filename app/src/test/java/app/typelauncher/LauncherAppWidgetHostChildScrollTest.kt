package app.typelauncher

import android.content.Context
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the bridge from a hosted widget's scrollable descendant (via
 * `requestDisallowInterceptTouchEvent`) into the host-level signal the
 * launcher's carousel polls. Without this, vertical scroll inside a widget
 * would page Home → Widgets, or open the system notification shade, because
 * the carousel reads raw pointer deltas via `positionChangeIgnoreConsumed`
 * and cannot see View-layer touch consumption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostChildScrollTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun newHost(id: Int) = LauncherAppWidgetHost(context, hostId = id)

    private fun motionEvent(action: Int): MotionEvent {
        val now = android.os.SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, 0f, 0f, 0)
    }

    @Test
    fun descendantClaimingScrollFlipsHostFlagTrue() {
        val host = newHost(1)
        val view = LauncherAppWidgetHostView(context, host = host)
        val received = mutableListOf<Boolean>()
        host.onChildScrollChange = { received += it }

        view.requestDisallowInterceptTouchEvent(true)

        assertEquals(listOf(true), received)
    }

    @Test
    fun descendantReleasingScrollDoesNotFlipFlag() {
        // requestDisallowInterceptTouchEvent(false) is a routine call any
        // ancestor can make to clear a previously-set disallow. It is not
        // a meaningful "I'm done scrolling" signal on its own — the reset
        // belongs to ACTION_DOWN/UP/CANCEL — so the host channel must not
        // flap to false on it and accidentally drop the latch mid-gesture.
        val host = newHost(2)
        val view = LauncherAppWidgetHostView(context, host = host)
        val received = mutableListOf<Boolean>()
        host.onChildScrollChange = { received += it }

        view.requestDisallowInterceptTouchEvent(false)

        assertEquals(emptyList<Boolean>(), received)
    }

    @Test
    fun actionDownClearsLatchFromPriorGesture() {
        val host = newHost(3)
        val view = LauncherAppWidgetHostView(context, host = host)
        val received = mutableListOf<Boolean>()
        host.onChildScrollChange = { received += it }

        view.requestDisallowInterceptTouchEvent(true)
        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))

        assertEquals(listOf(true, false), received)
    }

    @Test
    fun actionUpClearsLatch() {
        val host = newHost(4)
        val view = LauncherAppWidgetHostView(context, host = host)
        val received = mutableListOf<Boolean>()
        host.onChildScrollChange = { received += it }

        view.requestDisallowInterceptTouchEvent(true)
        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_UP))

        assertEquals(listOf(true, false), received)
    }

    @Test
    fun actionCancelClearsLatch() {
        val host = newHost(5)
        val view = LauncherAppWidgetHostView(context, host = host)
        val received = mutableListOf<Boolean>()
        host.onChildScrollChange = { received += it }

        view.requestDisallowInterceptTouchEvent(true)
        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_CANCEL))

        assertEquals(listOf(true, false), received)
    }

    @Test
    fun nullListenerDoesNotCrash() {
        // The carousel installs the listener inside a DisposableEffect that
        // clears it on dispose. Touches that arrive in the brief window
        // between dispose and a new install must not NPE.
        val host = newHost(6)
        val view = LauncherAppWidgetHostView(context, host = host)
        host.onChildScrollChange = null

        view.requestDisallowInterceptTouchEvent(true)
        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_DOWN))
        view.onInterceptTouchEvent(motionEvent(MotionEvent.ACTION_UP))
    }
}
