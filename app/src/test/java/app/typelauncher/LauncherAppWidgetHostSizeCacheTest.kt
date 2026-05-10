package app.typelauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the in-memory + persisted size cache that short-circuits the
 * `updateAppWidgetSize` IPC + provider `onAppWidgetOptionsChanged` wake
 * when the size hint hasn't changed since the last layout pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherAppWidgetHostSizeCacheTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        // Each test exercises persistence; clear the prefs file so tests
        // don't bleed into each other regardless of order.
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /** Counts updateAppWidgetSize calls without depending on a real provider binding. */
    private class CountingHostView(
        context: Context,
        host: LauncherAppWidgetHost,
    ) : LauncherAppWidgetHostView(context, host) {
        var updateAppWidgetSizeCalls = 0
        var lastWidthDp = 0
        var lastHeightDp = 0

        override fun updateAppWidgetSize(
            newOptions: android.os.Bundle?,
            minWidth: Int,
            minHeight: Int,
            maxWidth: Int,
            maxHeight: Int,
        ) {
            updateAppWidgetSizeCalls++
            lastWidthDp = minWidth
            lastHeightDp = minHeight
            // Skip super to avoid the actual AppWidgetService IPC.
        }

        override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
            // Skip the platform's binding work; tests don't ship a real provider.
        }
    }

    @Test
    fun firstApply_callsUpdateAppWidgetSize() {
        val host = LauncherAppWidgetHost(context, hostId = 1)
        val view = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)

        assertEquals(1, view.updateAppWidgetSizeCalls)
        assertEquals(320, view.lastWidthDp)
        assertEquals(240, view.lastHeightDp)
        assertEquals(IntPairDp(320, 240), host.cachedSizeForTest(100))
    }

    @Test
    fun secondApplyWithSameSize_skipsIpc() {
        val host = LauncherAppWidgetHost(context, hostId = 2)
        val view = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)

        assertEquals("identical size hint must not re-IPC", 1, view.updateAppWidgetSizeCalls)
    }

    @Test
    fun applyDifferentSize_replacesCachedValue() {
        val host = LauncherAppWidgetHost(context, hostId = 3)
        val view = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 360, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 360, heightDp = 240)

        assertEquals(2, view.updateAppWidgetSizeCalls)
        assertEquals(360, view.lastWidthDp)
        assertEquals(IntPairDp(360, 240), host.cachedSizeForTest(100))
    }

    @Test
    fun perWidgetCaching_doesNotCollide() {
        val host = LauncherAppWidgetHost(context, hostId = 4)
        val viewA = CountingHostView(context, host)
        val viewB = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(viewA, widgetId = 100, widthDp = 320, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(viewB, widgetId = 200, widthDp = 320, heightDp = 240)
        // Same dimensions for widget 100 again — should skip; for 200 too.
        host.applyAppWidgetSizeIfChanged(viewA, widgetId = 100, widthDp = 320, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(viewB, widgetId = 200, widthDp = 320, heightDp = 240)

        assertEquals(1, viewA.updateAppWidgetSizeCalls)
        assertEquals(1, viewB.updateAppWidgetSizeCalls)
    }

    @Test
    fun cachedSizePersistsAcrossHostInstances() {
        // First "session"
        val firstHost = LauncherAppWidgetHost(context, hostId = 5)
        val firstView = CountingHostView(context, firstHost)
        firstHost.applyAppWidgetSizeIfChanged(firstView, widgetId = 100, widthDp = 320, heightDp = 240)
        assertEquals(1, firstView.updateAppWidgetSizeCalls)

        // Second "session" — fresh host, fresh view, same widget ID.
        val secondHost = LauncherAppWidgetHost(context, hostId = 5)
        val secondView = CountingHostView(context, secondHost)
        secondHost.applyAppWidgetSizeIfChanged(secondView, widgetId = 100, widthDp = 320, heightDp = 240)

        assertEquals(
            "persisted cache must short-circuit the first apply on a fresh host",
            0,
            secondView.updateAppWidgetSizeCalls,
        )
    }

    @Test
    fun forgetWidgetSize_clearsBothMemoryAndPrefs() {
        val firstHost = LauncherAppWidgetHost(context, hostId = 6)
        val firstView = CountingHostView(context, firstHost)
        firstHost.applyAppWidgetSizeIfChanged(firstView, widgetId = 100, widthDp = 320, heightDp = 240)
        assertNotNull(firstHost.cachedSizeForTest(100))

        firstHost.forgetWidgetSize(100)
        assertNull("in-memory cache cleared", firstHost.cachedSizeForTest(100))

        // Fresh host should not see the forgotten entry.
        val secondHost = LauncherAppWidgetHost(context, hostId = 6)
        assertNull("persisted entry cleared", secondHost.cachedSizeForTest(100))
    }

    @Test
    fun forgetUncachedWidget_isNoop() {
        val host = LauncherAppWidgetHost(context, hostId = 7)
        host.forgetWidgetSize(999)  // never cached
        // No exception, no crash. (And nothing to assert beyond that.)
    }

    @Test
    fun corruptPersistedValue_isIgnored() {
        // Pollute the prefs with a malformed value, then construct a host
        // and verify it loads cleanly without that entry.
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("size:100", "not-a-pair")
            .putString("size:101", "320x240")
            .putString("size:102", "320xnope")
            .apply()

        val host = LauncherAppWidgetHost(context, hostId = 8)

        assertNull("malformed pair string skipped", host.cachedSizeForTest(100))
        assertEquals(IntPairDp(320, 240), host.cachedSizeForTest(101))
        assertNull("partially-malformed pair string skipped", host.cachedSizeForTest(102))
    }
}
