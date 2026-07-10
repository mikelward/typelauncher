package app.typelauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executor
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

    // The persisted-cache read runs on an injected executor (a background
    // thread in production, so MainActivity.onCreate never blocks on the
    // prefs file parse). A direct executor makes construction-time cache
    // state deterministic for these tests.
    private fun newHost(hostId: Int) =
        LauncherAppWidgetHost(context, hostId, cacheLoadExecutor = Executor { task -> task.run() })

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
        val host = newHost(hostId = 1)
        val view = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)

        assertEquals(1, view.updateAppWidgetSizeCalls)
        assertEquals(320, view.lastWidthDp)
        assertEquals(240, view.lastHeightDp)
        assertEquals(IntPairDp(320, 240), host.cachedSizeForTest(100))
    }

    @Test
    fun secondApplyWithSameSize_skipsIpc() {
        val host = newHost(hostId = 2)
        val view = CountingHostView(context, host)

        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)
        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 320, heightDp = 240)

        assertEquals("identical size hint must not re-IPC", 1, view.updateAppWidgetSizeCalls)
    }

    @Test
    fun applyDifferentSize_replacesCachedValue() {
        val host = newHost(hostId = 3)
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
        val host = newHost(hostId = 4)
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
        val firstHost = newHost(hostId = 5)
        val firstView = CountingHostView(context, firstHost)
        firstHost.applyAppWidgetSizeIfChanged(firstView, widgetId = 100, widthDp = 320, heightDp = 240)
        assertEquals(1, firstView.updateAppWidgetSizeCalls)

        // Second "session" — fresh host, fresh view, same widget ID.
        val secondHost = newHost(hostId = 5)
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
        val firstHost = newHost(hostId = 6)
        val firstView = CountingHostView(context, firstHost)
        firstHost.applyAppWidgetSizeIfChanged(firstView, widgetId = 100, widthDp = 320, heightDp = 240)
        assertNotNull(firstHost.cachedSizeForTest(100))

        firstHost.forgetWidgetSize(100)
        assertNull("in-memory cache cleared", firstHost.cachedSizeForTest(100))

        // Fresh host should not see the forgotten entry.
        val secondHost = newHost(hostId = 6)
        assertNull("persisted entry cleared", secondHost.cachedSizeForTest(100))
    }

    @Test
    fun forgetUncachedWidget_isNoop() {
        val host = newHost(hostId = 7)
        host.forgetWidgetSize(999)  // never cached
        // No exception, no crash. (And nothing to assert beyond that.)
    }

    @Test
    fun persistedCacheLoadIsDeferredToTheExecutor() {
        seedPersistedSize(widgetId = 100, value = "320x240")

        // Hold the load: nothing may read the prefs file inline on the
        // constructing (main) thread — that blocking parse in
        // MainActivity.onCreate is the cold-start regression this guards.
        val deferred = mutableListOf<Runnable>()
        val host = LauncherAppWidgetHost(context, hostId = 9, cacheLoadExecutor = Executor { deferred += it })

        assertNull("no inline disk read during construction", host.cachedSizeForTest(100))

        deferred.forEach(Runnable::run)
        assertEquals(IntPairDp(320, 240), host.cachedSizeForTest(100))
    }

    @Test
    fun applyBeforeDiskLoadLands_keepsTheNewerValue() {
        seedPersistedSize(widgetId = 100, value = "320x240")
        val deferred = mutableListOf<Runnable>()
        val host = LauncherAppWidgetHost(context, hostId = 10, cacheLoadExecutor = Executor { deferred += it })
        val view = CountingHostView(context, host)

        // The cache is cold in this window, so the apply degrades to the
        // pre-cache behavior: one (possibly redundant) size IPC.
        host.applyAppWidgetSizeIfChanged(view, widgetId = 100, widthDp = 360, heightDp = 240)
        assertEquals(1, view.updateAppWidgetSizeCalls)

        // The stale persisted entry must not clobber the newer in-memory one.
        deferred.forEach(Runnable::run)
        assertEquals(IntPairDp(360, 240), host.cachedSizeForTest(100))
        assertEquals(
            "the racing apply persisted its newer value",
            IntPairDp(360, 240),
            newHost(hostId = 10).cachedSizeForTest(100),
        )
    }

    @Test
    fun forgetBeforeDiskLoadLands_isNotResurrectedByTheMerge() {
        seedPersistedSize(widgetId = 100, value = "320x240")
        val deferred = mutableListOf<Runnable>()
        val host = LauncherAppWidgetHost(context, hostId = 11, cacheLoadExecutor = Executor { deferred += it })

        host.forgetWidgetSize(100)

        deferred.forEach(Runnable::run)
        assertNull("merge must not resurrect a forgotten widget", host.cachedSizeForTest(100))
        assertNull("persisted entry cleared too", newHost(hostId = 11).cachedSizeForTest(100))
    }

    private fun seedPersistedSize(widgetId: Int, value: String) {
        context.applicationContext
            .getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit()
            .putString("size:$widgetId", value)
            .apply()
    }

    @Test
    fun forgetAfterDeviceLanguageChange_stillRemovesThePersistedEntry() {
        // `String.format("%d")` renders the default locale's digit glyphs, so
        // an entry persisted under a Farsi device language used the Eastern
        // Arabic key `size:۱۰۰`. Loading tolerated it (`toIntOrNull` parses
        // Unicode digits), which masked the bug until the user switched the
        // device to a Latin-digit language: `forgetWidgetSize` then removed
        // the ASCII key, the old-locale entry survived forever, and the merge
        // resurrected it on every start. Keys must be locale-independent.
        val defaultLocale = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("fa"))
            val farsiHost = newHost(hostId = 12)
            farsiHost.applyAppWidgetSizeIfChanged(
                CountingHostView(context, farsiHost),
                widgetId = 100,
                widthDp = 320,
                heightDp = 240,
            )

            java.util.Locale.setDefault(java.util.Locale.US)
            val englishHost = newHost(hostId = 12)
            assertEquals(
                "the persisted entry must load across the language change",
                IntPairDp(320, 240),
                englishHost.cachedSizeForTest(100),
            )
            englishHost.forgetWidgetSize(100)

            assertNull(
                "the persisted entry must not survive the forget",
                newHost(hostId = 12).cachedSizeForTest(100),
            )
        } finally {
            java.util.Locale.setDefault(defaultLocale)
        }
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

        val host = newHost(hostId = 8)

        assertNull("malformed pair string skipped", host.cachedSizeForTest(100))
        assertEquals(IntPairDp(320, 240), host.cachedSizeForTest(101))
        assertNull("partially-malformed pair string skipped", host.cachedSizeForTest(102))
    }
}
