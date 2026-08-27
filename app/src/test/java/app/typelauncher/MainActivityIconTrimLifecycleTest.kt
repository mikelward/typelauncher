package app.typelauncher

import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins which teardowns may trim the icon cache, by driving `MainActivity`'s real
 * `onStop` rather than the predicate behind it.
 *
 * The trim exists to shrink the launcher's bitmap footprint in the background and
 * cached states, which is what Play measures from February 2027. But `onStop` is not
 * a synonym for "backgrounded": two teardowns run it while the launcher is going
 * straight back on screen, and the icon cache is process-wide, so the replacement
 * activity inherits every warmed bitmap -- unless a trim throws them away first, for
 * no saving at all. Both exclusions were review findings, and both are one deleted
 * line away from returning, which is exactly what these tests are for.
 *
 * Asserting on the debug log rather than on cache contents is deliberate. It records
 * whether `onStop` *called* the trim, which is the branch under test, and it does not
 * depend on the Robolectric environment reporting any installed apps -- the trim's own
 * filtering has its own tests, and mixing the two would let an empty app list make
 * these pass for the wrong reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityIconTrimLifecycleTest {

    @Before
    fun clearLog() = LauncherDebugLog.clearForTest()

    @After
    fun resetLog() = LauncherDebugLog.clearForTest()

    private fun trimWasAttempted(): Boolean =
        LauncherDebugLog.snapshot().any { it.contains("trimIconCacheToPriority") }

    private fun stopReallyRan(): Boolean =
        LauncherDebugLog.snapshot().any { it.contains("persistIconSnapshot") }

    @Test
    fun anOrdinaryStopTrims() {
        // The complement of the two exclusions below: without this, deleting the trim
        // call from onStop entirely would leave both of them passing.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            LauncherDebugLog.clearForTest()

            controller.pause().stop()

            assertTrue(
                "leaving the launcher must reach the trim: ${LauncherDebugLog.snapshot()}",
                trimWasAttempted(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aConfigurationChangeDoesNotTrim() {
        // No configChanges is declared, so a rotation destroys and rebuilds the
        // activity. Trimming here would hand the replacement composition a cache miss
        // per visible row and redraw the user's own screen from placeholders.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            LauncherDebugLog.clearForTest()

            controller.configurationChange(
                Configuration(controller.get().resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                },
            )

            assertFalse(
                "a rotation is not a departure and must not trim: ${LauncherDebugLog.snapshot()}",
                trimWasAttempted(),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theShowWallpaperRestartDoesNotTrim() {
        // This path calls finish() and immediately starts a replacement, so
        // isChangingConfigurations is false even though the launcher is coming right
        // back -- the configuration-change guard alone does not cover it.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            controller.get().restartForWallpaperWindowMode()
            LauncherDebugLog.clearForTest()

            controller.pause().stop()

            assertTrue(
                "the test must drive the real onStop, not just the flag: " +
                    "${LauncherDebugLog.snapshot()}",
                stopReallyRan(),
            )
            assertFalse(
                "the wallpaper restart is a hand-off, not a departure: " +
                    "${LauncherDebugLog.snapshot()}",
                trimWasAttempted(),
            )
        } finally {
            controller.destroy()
        }
    }
}
