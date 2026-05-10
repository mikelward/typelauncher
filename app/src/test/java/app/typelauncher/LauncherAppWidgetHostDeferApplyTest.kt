package app.typelauncher

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.widget.RemoteViews
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the queue/flush behaviour that hides UI-thread `RemoteViews.apply()`
 * from carousel translate frames. Provider background work is left running
 * (the host stays listening); only the apply on our side is parked while
 * the carousel is in motion and replayed when the deferral lifts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostDeferApplyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * Subclass that replaces the real platform inflate with a recording
     * stub so tests can assert call ordering without binding real provider
     * RemoteViews through Robolectric.
     */
    private class RecordingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
    ) : LauncherAppWidgetHostView(context, host) {
        val applied = mutableListOf<RemoteViews?>()

        override fun applyRemoteViewsImmediate(remoteViews: RemoteViews?) {
            applied += remoteViews
        }

        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            // Skip the platform's binding work; tests don't ship a real provider.
        }
    }

    private fun rv(): RemoteViews = RemoteViews(context.packageName, android.R.layout.simple_list_item_1)
    private fun newHost(id: Int) = LauncherAppWidgetHost(context, hostId = id)

    @Test
    fun deferFalse_appliesImmediately() {
        val host = newHost(1)
        val view = RecordingHostView(context, host)

        val first = rv()
        view.updateAppWidget(first)

        assertNull("nothing should be parked when not deferring", view.pendingRemoteViews)
        assertEquals(listOf<RemoteViews?>(first), view.applied)
    }

    @Test
    fun deferTrue_parksRemoteViewsInsteadOfApplying() {
        val host = newHost(2)
        val view = RecordingHostView(context, host)
        host.deferRemoteViewsApply = true

        val parked = rv()
        view.updateAppWidget(parked)

        assertSame("RemoteViews must park while deferring", parked, view.pendingRemoteViews)
        assertEquals("no inflate while deferring", emptyList<RemoteViews?>(), view.applied)
    }

    @Test
    fun deferTrue_keepsLatestRemoteViewsOnly() {
        val host = newHost(3)
        val view = RecordingHostView(context, host)
        host.deferRemoteViewsApply = true

        val first = rv()
        val second = rv()
        view.updateAppWidget(first)
        view.updateAppWidget(second)

        assertSame("intermediate RemoteViews are dropped, only latest queues", second, view.pendingRemoteViews)
    }

    @Test
    fun deferEnd_flushesParkedRemoteViewsExactlyOnce() {
        val host = newHost(4)
        val view = RecordingHostView(context, host)
        host.deferRemoteViewsApply = true
        val parked = rv()
        view.updateAppWidget(parked)

        host.deferRemoteViewsApply = false

        assertNull("flush clears pending", view.pendingRemoteViews)
        assertEquals("flush applied parked RemoteViews exactly once", listOf<RemoteViews?>(parked), view.applied)

        // Cycle the flag again with no new push — must not re-apply.
        host.deferRemoteViewsApply = true
        host.deferRemoteViewsApply = false
        assertEquals("flushing an empty queue is a no-op", listOf<RemoteViews?>(parked), view.applied)
    }

    @Test
    fun deferEndWithNoQueue_isNoop() {
        val host = newHost(5)
        val view = RecordingHostView(context, host)

        host.deferRemoteViewsApply = true
        host.deferRemoteViewsApply = false

        assertNull(view.pendingRemoteViews)
        assertEquals(emptyList<RemoteViews?>(), view.applied)
    }

    @Test
    fun multipleViews_allFlushOnDeferEnd() {
        val host = newHost(6)
        val a = RecordingHostView(context, host)
        val b = RecordingHostView(context, host)
        host.deferRemoteViewsApply = true

        val rvA = rv()
        val rvB = rv()
        a.updateAppWidget(rvA)
        b.updateAppWidget(rvB)
        assertSame(rvA, a.pendingRemoteViews)
        assertSame(rvB, b.pendingRemoteViews)

        host.deferRemoteViewsApply = false

        assertEquals(listOf<RemoteViews?>(rvA), a.applied)
        assertEquals(listOf<RemoteViews?>(rvB), b.applied)
    }

    @Test
    fun viewCreatedMidDefer_alsoQueues() {
        val host = newHost(7)
        host.deferRemoteViewsApply = true
        // A widget page that mounts mid-pause (e.g. user committed to a
        // freshly-warmed Widgets page) should also park its first push.
        val late = RecordingHostView(context, host)

        val parked = rv()
        late.updateAppWidget(parked)
        assertSame(parked, late.pendingRemoteViews)

        host.deferRemoteViewsApply = false
        assertEquals(listOf<RemoteViews?>(parked), late.applied)
    }

    @Test
    fun setterIdempotentWhenSameValue() {
        val host = newHost(8)
        val view = RecordingHostView(context, host)
        host.deferRemoteViewsApply = true
        val parked = rv()
        view.updateAppWidget(parked)

        host.deferRemoteViewsApply = true  // same value, must not flush

        assertSame("setting true again did not flush", parked, view.pendingRemoteViews)
        assertEquals(emptyList<RemoteViews?>(), view.applied)

        host.deferRemoteViewsApply = false
        assertEquals(listOf<RemoteViews?>(parked), view.applied)
    }
}
