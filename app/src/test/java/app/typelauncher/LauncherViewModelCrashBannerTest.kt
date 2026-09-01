package app.typelauncher

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The ViewModel glue that turns a crashed prior run into the Home banner and
 * back off again. The [DebugFileSink] crash bookkeeping is covered directly in
 * [DebugFileSinkTest]; this drives it through the ViewModel with an injected
 * sink (the Application skips wiring the real one under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class LauncherViewModelCrashBannerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        LauncherDebugLog.resetForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(originalHandler)
        LauncherDebugLog.resetForTest()
    }

    @Test
    fun `a crashed prior run raises the banner`() {
        val sink = sinkAfterPriorRun(crashed = true)
        val viewModel = newViewModel(sink)
        idle()

        assertTrue("a crashed prior run shows the banner", viewModel.uiState.value.isCrashBannerVisible)
    }

    @Test
    fun `a routine prior run leaves the banner hidden`() {
        val sink = sinkAfterPriorRun(crashed = false)
        val viewModel = newViewModel(sink)
        idle()

        assertFalse("an ordinary process death never shows the banner", viewModel.uiState.value.isCrashBannerVisible)
    }

    @Test
    fun `dismiss hides the banner and silences the crash`() {
        val sink = sinkAfterPriorRun(crashed = true)
        val viewModel = newViewModel(sink)
        idle()
        assertTrue(viewModel.uiState.value.isCrashBannerVisible)

        viewModel.dismissCrashBanner()
        idle()

        assertFalse("dismiss hides the banner", viewModel.uiState.value.isCrashBannerVisible)
        assertFalse("dismiss renames the crash log so it can't re-raise", sink.hasUnacknowledgedCrash())
    }

    @Test
    fun `refresh clears the banner once the crash was shared`() {
        val sink = sinkAfterPriorRun(crashed = true)
        val viewModel = newViewModel(sink)
        idle()
        assertTrue(viewModel.uiState.value.isCrashBannerVisible)

        // A successful share consumes the prior run (as BugReport.share does).
        sink.readPreviousRun()
        sink.clearPreviousRun()
        viewModel.refreshCrashBanner()
        idle()

        assertFalse("a shared crash leaves no banner", viewModel.uiState.value.isCrashBannerVisible)
    }

    /**
     * A sink whose directory already holds a rotated prior run — crash-suffixed
     * when [crashed], plain otherwise — mirroring what the Application's sink sees
     * at the next cold start. Its `start()` has run and drained, so the ViewModel
     * created against it observes the rotated files.
     */
    private fun sinkAfterPriorRun(crashed: Boolean): DebugFileSink {
        val dir = folder.newFolder()
        // Swallow the crash so triggering the handler doesn't fail the test JVM.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val priorRun = DebugFileSink(dir)
        priorRun.start()
        LauncherDebugLog.event("home ready, 12 apps")
        if (crashed) {
            Thread.getDefaultUncaughtExceptionHandler()!!
                .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
        } else {
            priorRun.log("x")
            priorRun.awaitIdleForTest()
        }
        // This run's sink: start() rotates the prior run's file aside (crash-
        // suffixed iff the marker is present) before the ViewModel queries it.
        val sink = DebugFileSink(dir)
        sink.start()
        sink.awaitIdleForTest()
        return sink
    }

    private fun newViewModel(sink: DebugFileSink): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
        debugFileSinkProvider = { sink },
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
