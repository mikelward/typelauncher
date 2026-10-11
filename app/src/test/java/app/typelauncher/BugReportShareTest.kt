package app.typelauncher

import android.app.Activity
import com.mikelward.androidlog.android.CollectedReport
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ShareOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

/**
 * [BugReport.share] must never turn a failure while collecting the report into
 * another crash — the report is most useful right after something has already
 * gone wrong — and must say so when neither delivery route landed, instead of
 * leaving the tap looking like it did nothing. The payload build, the screenshot
 * capture, the delivery, and the main-thread hop are all injected, so every
 * outcome is drivable without a real window, `ClipboardManager`, or share
 * target.
 */
@RunWith(RobolectricTestRunner::class)
class BugReportShareTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        LauncherDebugLog.resetForTest()
    }

    @After
    fun tearDown() {
        LauncherDebugLog.resetForTest()
        ShadowToast.reset()
    }

    @Test
    fun `a failed payload collection still shares a report carrying the recent log`() = runBlocking {
        LauncherDebugLog.event("home ready")
        var shared: String? = null

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> throw IllegalStateException("settings store unreadable") },
            deliverReport = { _, report, _ -> shared = report.text; ShareOutcome.SHARED },
        )

        assertNotNull("a report is still shared", shared)
        val text = shared!!
        assertTrue("names the collection failure", text.contains("Report collection failed"))
        assertTrue("keeps the recent log", text.contains("home ready"))
        assertTrue("records the failure in the log too", text.contains("payload collection failed"))
    }

    @Test
    fun `a collection failure does not escape into the caller's scope`() = runBlocking {
        // The share runs from a UI tap; an escaping throwable would take the
        // launcher down — the one thing a bug-report path must never do.
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> throw OutOfMemoryError("simulated") },
            deliverReport = { _, _, _ -> ShareOutcome.SHARED },
        )
    }

    @Test
    fun `neither route landing tells the user`() = runBlocking {
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> plainReport() },
            deliverReport = { _, _, _ -> ShareOutcome.FAILED },
        )

        assertNotNull("the user is told the share failed", ShadowToast.getLatestToast())
        assertTrue(
            "with the failure notice",
            ShadowToast.getTextOfLatestToast()
                .contains(activity.getString(R.string.bug_report_share_failed)),
        )
    }

    @Test
    fun `a throwing delivery is survivable and still reported`() = runBlocking {
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> plainReport() },
            deliverReport = { _, _, _ -> throw SecurityException("no clipboard access") },
        )

        assertNotNull("the user is told the share failed", ShadowToast.getLatestToast())
    }

    @Test
    fun `one route landing is not reported as a failure`() = runBlocking {
        for (outcome in listOf(ShareOutcome.SHARED, ShareOutcome.COPIED_ONLY)) {
            BugReport.share(
                activity,
                includeScreenshot = false,
                mainDispatcher = Dispatchers.Unconfined,
                payloadCollect = { _, _ -> plainReport() },
                deliverReport = { _, _, _ -> outcome },
            )

            assertNull("$outcome landed somewhere, so there is nothing to warn about", ShadowToast.getLatestToast())
        }
    }

    @Test
    fun `a share that outlives its screen still opens the chooser`() = runBlocking {
        // The share runs on the application scope, so the Activity that started
        // it can be gone by the time the chooser launches. Starting from a
        // torn-down Activity targets a dead token, so it falls back to the
        // application context — the report still reaches the sheet.
        activity.finish()

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> plainReport() },
        )

        val started = shadowOf(activity.application).nextStartedActivity
        assertNotNull("the chooser still launched", started)
        assertNull("and nothing was reported as a failed share", ShadowToast.getLatestToast())
    }

    @Test
    fun `a text-only share never captures a screenshot`() = runBlocking {
        var captured = false

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> plainReport() },
            screenshotCapture = { captured = true; null },
            deliverReport = { _, _, _ -> ShareOutcome.SHARED },
        )

        assertFalse("the post-crash banner's text-only share stays text-only", captured)
    }

    /** A report with no earlier runs: a null sink reads none. */
    private fun plainReport(): CollectedReport = DebugReport.collect(LauncherDebugLog, sink = null) { "report" }
}
