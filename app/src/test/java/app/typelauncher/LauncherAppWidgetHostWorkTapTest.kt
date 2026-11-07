package app.typelauncher

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.RemoteViews
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * A tap on a work widget rebuilt after its profile came back can hit dead
 * tap actions in the content saved before the profile stopped. The host
 * says the work apps are still starting when such a tap opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostWorkTapTest {
    private class FakeWidgetView(
        context: Context,
        host: LauncherAppWidgetHost,
        private val work: Boolean,
    ) : LauncherAppWidgetHostView(context, host) {
        override val isWorkProfileWidget: Boolean get() = work

        override fun applyRemoteViewsImmediate(remoteViews: RemoteViews?) = Unit
    }

    private lateinit var host: LauncherAppWidgetHost

    /** A widget view bound the way `createView` binds it: id, then the saved content. */
    private fun boundView(work: Boolean = true): FakeWidgetView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        host = LauncherAppWidgetHost(activity, hostId = 1, cacheLoadExecutor = { it.run() })
        val view = FakeWidgetView(activity, host, work)
        activity.setContentView(view)
        view.setAppWidget(33, null)
        view.updateAppWidget(null)
        // The card's factory binds the same id again.
        view.setAppWidget(33, null)
        idle(Duration.ZERO)
        return view
    }

    private fun tap(view: FakeWidgetView) {
        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, 10f, 10f, 0))
    }

    private fun idle(duration: Duration) = shadowOf(Looper.getMainLooper()).idleFor(duration)

    private fun afterCheck() = idle(Duration.ofMillis(WorkWidgetTapCheck.CHECK_DELAY_MILLIS + 50))

    @Test
    fun deadTapSoonAfterProfileReturnsSaysWorkAppsAreStarting() {
        val view = boundView()
        host.onWorkProfileAvailable()

        tap(view)
        afterCheck()

        assertEquals("Work apps are still starting. Try again in a moment.", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun tapThatPausesTheLauncherSaysNothing() {
        val view = boundView()
        host.onWorkProfileAvailable()

        tap(view)
        host.onLauncherPaused()
        afterCheck()

        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun tapAfterTheProviderSentContentSaysNothing() {
        val view = boundView()
        host.onWorkProfileAvailable()
        view.updateAppWidget(null)

        tap(view)
        afterCheck()

        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun tapLongAfterProfileReturnsSaysNothing() {
        val view = boundView()
        host.onWorkProfileAvailable()
        idle(Duration.ofMillis(WorkWidgetTapCheck.STALE_WINDOW_MILLIS + 1_000))

        tap(view)
        afterCheck()

        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun tapOnPersonalWidgetSaysNothing() {
        val view = boundView(work = false)
        host.onWorkProfileAvailable()

        tap(view)
        afterCheck()

        assertNull(ShadowToast.getLatestToast())
    }

    @Test
    fun dragSaysNothing() {
        val view = boundView()
        host.onWorkProfileAvailable()

        val now = SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_MOVE, 300f, 10f, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 300f, 10f, 0))
        afterCheck()

        assertNull(ShadowToast.getLatestToast())
    }
}
