package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import java.time.Duration
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the foreground icon warm-up: filling the cache ahead of demand so typing a
 * search finds its results already rasterized.
 *
 * The warm-up exists because the background trim made the app's primary interaction
 * colder than it was before. The trim keeps the dock and the most-launched apps --
 * what Home paints -- and drops the long tail, which is exactly what search goes
 * looking for. Warming is a *foreground* cost by design: Play measures bitmap memory
 * in the background and cached states, not while the UI is visible.
 *
 * These drive the real entry points (`onHomeReady`, `onLauncherVisible`,
 * `cancelIconWarmUp`) rather than the private worker, so deleting a call site fails
 * the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelIconWarmUpTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clearLog() = LauncherDebugLog.clearForTest()

    @After
    fun tearDown() {
        LauncherDebugLog.clearForTest()
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    private fun newViewModel() = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    ).also { shadowOf(Looper.getMainLooper()).idle() }

    /** Seeds one rendered size, which is what tells the warm-up what to warm at. */
    /** Puts a bitmap straight into the cache, standing in for a rendered icon. */
    private fun seedAt(id: String, sizePx: Int) {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .asImageBitmap()
        AppIconLoader.put(id, sizePx, bitmap)
    }

    /** Stands in for the UI reporting what it renders; without it nothing warms. */
    private fun LauncherViewModel.reportSizes(
        listPx: Int = 40,
        dockPx: Int = 132,
        folderPx: Int = 56,
    ) = onRenderedIconSizes(listPx = listPx, dockPx = dockPx, folderPx = folderPx)

    /**
     * Puts the view model in the on-screen state `MainActivity.onStart` establishes.
     * Nothing warms while hidden, so every warming test has to go through this the
     * same way production does.
     */
    private fun LauncherViewModel.becomeVisible() = onLauncherVisible()

    /**
     * Idles past the warm-up's trailing debounce.
     *
     * Every trigger now goes through `scheduleIconWarmUp`, so a bare `idle()` runs the
     * looper's pending work and stops short of the delayed post -- the sweep has not
     * been asked for yet. Driving the clock explicitly (rather than sleeping) keeps
     * these deterministic.
     */
    private fun settle() {
        val looper = shadowOf(Looper.getMainLooper())
        looper.idle()
        looper.idleFor(Duration.ofMillis(ICON_WARM_UP_DEBOUNCE_MILLIS + 50))
        looper.idle()
    }

    /**
     * Asserts on the *start* of the warm-up, not its completion. The loads it drives
     * run on `AppIconLoader`'s own dispatchers, so waiting for the finish line would
     * make these tests depend on real IO landing inside an `idle()` -- passing or
     * failing on timing rather than on behavior.
     */
    private fun warmUpStarted(): Boolean =
        LauncherDebugLog.snapshot().any { it.contains("warmIconCache starting") }

    @Test
    fun homeReadyStartsTheWarmUp() {
        // Deferred to home-ready rather than run in init: filling the cache ahead of
        // demand is cold-start-adjacent IO and must not compete with the app list.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        LauncherDebugLog.clearForTest()

        viewModel.onHomeReady()
        settle()

        assertTrue(
            "home-ready must start the warm-up: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun returningToTheForegroundWarmsAgain() {
        // The one that matters most: the trim drops the long tail every time the
        // launcher leaves, so a warm-up that only ran once per process would leave
        // search cold for the rest of the session.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.onLauncherVisible()
        settle()

        assertTrue(
            "every return to the foreground must re-warm: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun theWarmUpDoesNotStartBeforeHomeIsReady() {
        // onLauncherVisible fires on every onStart, including the cold-start one that
        // lands before the first frame. Warming there would race the app list for
        // Dispatchers.IO, which is the whole reason the work is deferred.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        LauncherDebugLog.clearForTest()

        viewModel.onLauncherVisible()
        settle()

        assertFalse(
            "warming must wait for the first frame: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun warmingIsSkippedUntilSomethingHasBeenRendered() {
        // With nothing in the cache there is no size to warm at: the list, dock and
        // icon-only grid sizes all differ and two of the three are settings, so the
        // warm-up reads them from what has actually been rendered rather than
        // guessing a constant.
        AppIconLoader.evictAll()
        val viewModel = newViewModel()
        LauncherDebugLog.clearForTest()

        viewModel.onHomeReady()
        settle()

        assertTrue(
            "with no rendered size the warm-up must skip, not guess: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("warmIconCache skipped") },
        )
    }

    @Test
    fun eachSurfaceIsWarmedAtItsOwnSize() {
        // The defect this pins: warming every app at every size multiplies the
        // footprint for icons that are never asked for at that size. A docked app is
        // drawn at the dock size, a folder member in a mini-cell, and every app in
        // the list -- so the plan pairs each set with one size, and only the list set
        // is every app. Warming all of them at all sizes reached the byte ceiling
        // well before the tail that search actually needs.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes(listPx = 40, dockPx = 132, folderPx = 56)
        LauncherDebugLog.clearForTest()

        viewModel.onHomeReady()
        settle()

        val start = LauncherDebugLog.snapshot().first { it.contains("warmIconCache starting") }
        assertTrue("the list size must be carried: $start", start.contains("list=40"))
        assertTrue("the dock size must be carried: $start", start.contains("dock=132"))
        assertTrue("the folder size must be carried: $start", start.contains("folder=56"))
    }

    @Test
    fun changingTheRenderedSizesReWarms() {
        // A layout or icon-size setting change makes the old keys dead and the new
        // ones cold, so it has to re-warm rather than leave the cache full of sizes
        // nothing will read.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes(listPx = 40)
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.reportSizes(listPx = 64)
        settle()

        assertTrue(
            "a size change must re-warm: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("warmIconCache starting") },
        )
    }

    @Test
    fun reportingTheSameSizesAgainDoesNotReWarm() {
        // Recomposition reports the same values repeatedly; re-warming on each would
        // restart the sweep endlessly and never finish one.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes(listPx = 40)
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.reportSizes(listPx = 40)
        settle()

        assertFalse(
            "an unchanged report must be ignored: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("warmIconCache starting") },
        )
    }

    @Test
    fun aSizeChangeRetiresTheOrphanedSizeSoTheNewSweepHasRoom() {
        // Without this the replacement sweep is a no-op: the cache is still full at
        // the old size, `cacheBytes()` is already past the ceiling on the first
        // iteration, and the tail stays cold at the size now actually on screen.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes(listPx = 40, dockPx = 132, folderPx = 56)
        seedAt("0:com.example.warmup.old/Main", sizePx = 40)
        assertNotNull("precondition: seeded at the old size", AppIconLoader.cached("0:com.example.warmup.old/Main", 40))

        viewModel.reportSizes(listPx = 64, dockPx = 132, folderPx = 56)

        assertNull(
            "the orphaned size must be retired, not left counting toward the ceiling",
            AppIconLoader.cached("0:com.example.warmup.old/Main", 40),
        )
    }

    @Test
    fun aSizeStillInUseIsNotRetired() {
        // Only sizes this tuple orphaned are dropped. The dock size is unchanged
        // here, and its icons are on screen -- evicting them would trade a
        // stale-byte problem for a visible one.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes(listPx = 40, dockPx = 132, folderPx = 56)
        seedAt("0:com.example.warmup.dock/Main", sizePx = 132)

        viewModel.reportSizes(listPx = 64, dockPx = 132, folderPx = 56)

        assertNotNull(
            "an unchanged size is still rendered and must survive",
            AppIconLoader.cached("0:com.example.warmup.dock/Main", 132),
        )
    }

    @Test
    fun anIconThemeChangeReWarmsEvenThoughTheSizesAreUnchanged() {
        // Changing the theme empties the cache at unchanged sizes, so the
        // same-sizes early return would otherwise swallow the re-warm and leave
        // search cold until some later foreground transition.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.setIconTheme(IconTheme.Monochrome)
        settle()

        assertTrue(
            "a whole-cache invalidation must re-warm: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun aDisabledDockIsStillWarmed() {
        // Deliberate: the plan does not ask whether the dock is on screen. Chasing
        // that question was wrong five times over, each answer living in the
        // composable and going stale here. Warming a dock's worth of icons that a
        // hidden dock would not have drawn is a fraction of the budget; the
        // visibility question cost a review round every time.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.setDockEnabled(false)
        viewModel.onHomeReady()
        settle()

        assertTrue(
            "the sweep still runs for the list: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
        assertTrue(
            "warming proceeds regardless of dock visibility: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().none { it.contains("warmIconCache stopped at ceiling") },
        )
    }

    @Test
    fun aPaletteChangeReWarmsEvenThoughTheSizesAreUnchanged() {
        // The second of the two ways the cache empties without a size moving: under
        // the Monochrome theme, flipping the launcher between light and dark
        // re-resolves the palette and evicts everything. Fixing only the icon-theme
        // path would have left this one silently cold.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.setThemeMode(ThemeMode.Dark)
        settle()

        assertTrue(
            "a palette flip must re-warm: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun warmingIsIndependentOfDockSettings() {
        // Companion to the above: no dock setting suppresses the sweep. The list pass
        // is what search reads, and it must run whatever the dock is doing.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.setDockEnabled(false)
        viewModel.onHomeReady()
        settle()

        assertTrue(
            "the sweep still runs for the list: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun aReloadLandingAfterTheUserLeavesDoesNotWarm() {
        // The reload trigger added for newly-installed apps could fire after the
        // user had already gone: onStop cancels the sweep and trims, then the
        // surviving reload job starts a fresh one and refills the cache to its
        // ceiling while backgrounded -- undoing the trim in exactly the states it
        // exists to shrink.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()

        viewModel.onLauncherHidden()
        LauncherDebugLog.clearForTest()
        viewModel.onRenderedIconSizes(listPx = 64, dockPx = 132, folderPx = 56)
        settle()

        assertFalse(
            "nothing may warm while the launcher is off screen: " +
                "${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun aListChangeWithNoNamedTriggerOfItsOwnStillWarms() {
        // The point of the funnel. Toggling the dock changes the searchable set --
        // docked apps leave the list when the dock draws them and come back when it
        // does not -- and it never had a warm-up trigger, because the trigger list was
        // written by enumerating events rather than by finding where they converge.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.setDockEnabled(false)
        settle()

        assertTrue(
            "a change to the searchable set must warm, named trigger or not: " +
                "${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    /**
     * Parks every IO block, so a started sweep stays suspended inside its
     * `withContext(ioDispatcher)` instead of finishing instantly against Robolectric's
     * empty app inventory. That is what makes "a sweep is in flight" a state the test
     * can actually be in, rather than a race it hopes to win.
     */
    private class ParkingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
    }

    @Test
    fun aListChangeCancelsTheSweepAlreadyRunning() {
        // The defect this pins, and one this PR introduced: the named triggers used to
        // call `warmIconCache` directly, which cancelled the running sweep at once.
        // Debouncing without this cancel leaves the pre-change sweep resolving and
        // rasterizing for the whole burst plus the debounce -- competing with the very
        // drag that raised the signal, which is the jank the debounce exists to stop.
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = ParkingDispatcher(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        assertTrue(
            "precondition: a sweep must be in flight for the cancel to mean anything: " +
                "${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
        LauncherDebugLog.clearForTest()

        viewModel.setDockEnabled(false)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the first signal of a burst must stop the stale sweep, not just the timer: " +
                "${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("canceled the in-flight sweep") },
        )
    }

    @Test
    fun aBurstOfListChangesCollapsesToOneSweep() {
        // The reason the funnel needed a debounce before it could be hooked at all.
        // A drag-reorder calls `refreshLists` once per move and each warm-up cancels
        // the one before it, so an undebounced hook would restart the sweep every
        // frame of the drag and never finish one -- strictly worse than the eight
        // named triggers it replaces.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        repeat(8) { index -> viewModel.setDockEnabled(index % 2 == 0) }
        settle()

        assertEquals(
            "a burst must collapse to a single sweep: ${LauncherDebugLog.snapshot()}",
            1,
            LauncherDebugLog.snapshot().count { it.contains("warmIconCache starting") },
        )
    }

    @Test
    fun aBurstStillInFlightHasNotWarmedYet() {
        // Trailing, not leading. Firing at the front of a burst would warm the
        // pre-change plan -- the drop has not landed, the reload has not published --
        // and then need doing again once it settles.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        repeat(4) { index -> viewModel.setDockEnabled(index % 2 == 0) }
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "nothing may sweep while the burst is still arriving: " +
                "${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }

    @Test
    fun unhidingAnAppReWarms() {
        // The warm-up skips hidden apps, so restoring one puts an app into search
        // that was never in a plan. The sizes have not moved, so nothing else asks.
        val viewModel = newViewModel()
        viewModel.becomeVisible()
        viewModel.reportSizes()
        viewModel.onHomeReady()
        settle()
        LauncherDebugLog.clearForTest()

        viewModel.unhideApp(
            InstalledApp(
                name = "App One",
                packageName = "com.example.unhidden",
                launchIntent = android.content.Intent.makeMainActivity(
                    android.content.ComponentName("com.example.unhidden", "com.example.unhidden.Main"),
                ),
                user = android.os.Process.myUserHandle(),
                isWorkApp = false,
                launchWithLauncherApps = true,
            ),
        )
        settle()

        assertTrue(
            "restoring an app to search must re-warm: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }
}
