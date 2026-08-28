package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        viewModel.onLauncherVisible()
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        viewModel.reportSizes(listPx = 64)
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        viewModel.reportSizes(listPx = 40)
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        viewModel.setIconTheme(IconTheme.Monochrome)
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        viewModel.setThemeMode(ThemeMode.Dark)
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

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
        shadowOf(Looper.getMainLooper()).idle()

        viewModel.onLauncherHidden()
        LauncherDebugLog.clearForTest()
        viewModel.onRenderedIconSizes(listPx = 64, dockPx = 132, folderPx = 56)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(
            "nothing may warm while the launcher is off screen: " +
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
        shadowOf(Looper.getMainLooper()).idle()
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
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "restoring an app to search must re-warm: ${LauncherDebugLog.snapshot()}",
            warmUpStarted(),
        )
    }
}
