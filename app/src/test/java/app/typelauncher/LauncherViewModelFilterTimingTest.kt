package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the per-keystroke filter timing.
 *
 * It exists to settle one question with a number: `refreshFilteredApps` runs on the
 * main thread for every keystroke, and whether that is worth moving off Main depends
 * entirely on what it costs. Moving it would not make the sort faster -- it would free
 * Main to draw and let a fast typist's superseded filters be cancelled -- which is a
 * good trade at 5 ms a keystroke and a bad one at 1 ms, where it only adds a frame of
 * latency.
 *
 * These pin the *reporting*, not the durations, which are a property of the device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelFilterTimingTest {
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

    private fun timingLines(): List<String> =
        LauncherDebugLog.snapshot().filter { it.contains("refreshFilteredApps timing") }

    @Test
    fun theReportItselfNeverRunsOnTheKeystrokePath() {
        // Batching is only half the protection. `LauncherDebugLog.event` formats twice,
        // takes the buffer lock and fans out to sinks -- one of which writes to disk --
        // so emitting it inline would put a disk write on one keystroke in 25, which is
        // indistinguishable from the hitch this instrumentation exists to find.
        //
        // A dispatcher that parks its blocks stands in for "not the main thread": with
        // the emit dispatched, nothing reaches the log until that dispatcher runs it.
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = ParkingDispatcher(),
        )
        shadowOf(Looper.getMainLooper()).idle()
        LauncherDebugLog.clearForTest()

        repeat(FILTER_TIMING_SAMPLE_INTERVAL) { viewModel.setQuery("typed") }
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the emit must be dispatched off the keystroke path, not run inline: " +
                "${timingLines()}",
            timingLines().isEmpty(),
        )
    }

    /** Parks every block, standing in for work that does not run on the caller. */
    private class ParkingDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = Unit
    }

    @Test
    fun typedFiltersReportOncePerSampleInterval() {
        // Accumulated, not logged per keystroke: formatting a line, writing logcat and
        // mirroring to telemetry on every keystroke would put the measurement onto the
        // path it is measuring.
        val viewModel = newViewModel()
        LauncherDebugLog.clearForTest()

        repeat(FILTER_TIMING_SAMPLE_INTERVAL - 1) { index -> viewModel.setQuery("q".repeat(index % 4 + 1)) }
        assertTrue(
            "nothing may be logged before the interval is reached: ${timingLines()}",
            timingLines().isEmpty(),
        )

        viewModel.setQuery("done")

        assertEquals("the interval must emit exactly one line: ${timingLines()}", 1, timingLines().size)
        val line = timingLines().single()
        assertTrue("the line must name the sample count: $line", line.contains("samples=$FILTER_TIMING_SAMPLE_INTERVAL"))
        assertTrue("the line must carry an average: $line", line.contains("avgMicros="))
        assertTrue("the line must carry a max: $line", line.contains("maxMicros="))
    }

    @Test
    fun blankAndTypedQueriesAreCountedSeparately() {
        // Different code paths in `filterByName` -- blank sorts the whole inventory,
        // typed matches and ranks -- so averaging them together would hide whichever
        // is the slow one, which is the only thing this measurement is for.
        val viewModel = newViewModel()
        LauncherDebugLog.clearForTest()

        repeat(FILTER_TIMING_SAMPLE_INTERVAL) { viewModel.setQuery("typed") }

        assertEquals("only the typed bucket should have reported", 1, timingLines().size)
        assertTrue(
            "the reported bucket must be the typed one: ${timingLines()}",
            timingLines().single().contains("typed=true"),
        )

        repeat(FILTER_TIMING_SAMPLE_INTERVAL) { viewModel.setQuery("") }

        assertTrue(
            "the blank bucket must report on its own count: ${timingLines()}",
            timingLines().any { it.contains("typed=false") },
        )
    }

    @Test
    fun theQueryItselfNeverReachesTheLog() {
        // Search history is user data and stays off every artifact that leaves the
        // device, logs included. `typed` is the only thing about the query worth
        // knowing here, and not even its length is recorded.
        val viewModel = newViewModel()
        LauncherDebugLog.clearForTest()

        repeat(FILTER_TIMING_SAMPLE_INTERVAL) { viewModel.setQuery("secretquery") }

        val line = timingLines().single()
        assertFalse("the query text must never be logged: $line", line.contains("secretquery"))
        assertFalse("not even a redacted fragment: $line", line.contains("secret"))
    }
}
