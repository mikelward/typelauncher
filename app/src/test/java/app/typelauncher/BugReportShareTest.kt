package app.typelauncher

import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [BugReport.share] must never turn a failure while collecting the report into
 * another crash — the report is most useful right after something has already
 * gone wrong — and must say so when neither delivery route landed, instead of
 * leaving the tap looking like it did nothing. The payload build, the screenshot
 * capture, the clipboard write, the chooser launch, and the main-thread hop are
 * all injected, so every outcome is drivable without a real window,
 * `ClipboardManager`, or share target.
 */
@RunWith(RobolectricTestRunner::class)
class BugReportShareTest {

    private lateinit var activity: Activity

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        LauncherDebugLog.clearForTest()
    }

    @After
    fun tearDown() {
        LauncherDebugLog.clearForTest()
        LauncherDebugLog.clearSinksForTest()
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
            clipboardWrite = { _, text -> shared = text; true },
            chooserLaunch = { _, _, _ -> true },
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
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )
    }

    @Test
    fun `neither route landing tells the user`() = runBlocking {
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> BugReport.Payload("report", emptySet()) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _, _ -> false },
        )

        assertNotNull("the user is told the share failed", ShadowToast.getLatestToast())
        assertTrue(
            "with the failure notice",
            ShadowToast.getTextOfLatestToast()
                .contains(activity.getString(R.string.bug_report_share_failed)),
        )
    }

    @Test
    fun `a throwing clipboard or chooser is survivable and still reported`() = runBlocking {
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> BugReport.Payload("report", emptySet()) },
            clipboardWrite = { _, _ -> throw SecurityException("no clipboard access") },
            chooserLaunch = { _, _, _ -> throw IllegalStateException("no share target") },
        )

        assertNotNull("the user is told the share failed", ShadowToast.getLatestToast())
    }

    @Test
    fun `one route landing is not reported as a failure`() = runBlocking {
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> BugReport.Payload("report", emptySet()) },
            clipboardWrite = { _, _ -> false },
            chooserLaunch = { _, _, _ -> true },
        )

        assertNull("the chooser opened, so there is nothing to warn about", ShadowToast.getLatestToast())
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
            payloadCollect = { _, _ -> BugReport.Payload("report", emptySet()) },
            clipboardWrite = { _, _ -> false },
        )

        val started = shadowOf(activity.application).nextStartedActivity
        assertNotNull("the chooser still launched", started)
        assertNull("and nothing was reported as a failed share", ShadowToast.getLatestToast())
    }

    @Test
    fun `a second share while one is running is refused, not run alongside`() = runBlocking {
        // Two taps in a row is the normal way to use a button that gives no
        // feedback, and overlapping shares race the consume: the first deletes
        // the prior runs it carried while the second is still collecting, so the
        // second builds a report with no crash in it and that is the one the user
        // is left holding — with the log already gone.
        val firstIsCollecting = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val collections = AtomicInteger()

        val first = launch(Dispatchers.IO) {
            BugReport.share(
                activity,
                includeScreenshot = false,
                mainDispatcher = Dispatchers.Unconfined,
                payloadCollect = { _, _ ->
                    collections.incrementAndGet()
                    firstIsCollecting.countDown()
                    // Held open by the test, so the overlap is deterministic
                    // rather than a race the test hopes to win.
                    releaseFirst.await()
                    BugReport.Payload("report", emptySet())
                },
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _, _ -> true },
            )
        }
        assertTrue("the first share reached its payload build", firstIsCollecting.await(10, TimeUnit.SECONDS))

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ ->
                collections.incrementAndGet()
                BugReport.Payload("second report", emptySet())
            },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )

        // Read the count, then always release the first share before asserting:
        // asserting first would leave the gate latch closed on failure, and
        // runBlocking would wait on a child that can never finish — a regression
        // has to fail, not hang.
        val builds = collections.get()
        releaseFirst.countDown()
        first.join()

        assertEquals("the repeat tap never built a second report", 1, builds)
    }

    @Test
    fun `the gate reopens once a share finishes, including a failing one`() = runBlocking {
        repeat(2) {
            BugReport.share(
                activity,
                includeScreenshot = false,
                mainDispatcher = Dispatchers.Unconfined,
                payloadCollect = { _, _ -> throw IllegalStateException("settings store unreadable") },
                clipboardWrite = { _, _ -> true },
                chooserLaunch = { _, _, _ -> true },
            )
        }
        var ran = false
        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> ran = true; BugReport.Payload("report", emptySet()) },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )
        assertTrue("a later share is not locked out by an earlier one", ran)
    }

    @Test
    fun `a text-only share never captures a screenshot`() = runBlocking {
        var captured = false

        BugReport.share(
            activity,
            includeScreenshot = false,
            mainDispatcher = Dispatchers.Unconfined,
            payloadCollect = { _, _ -> BugReport.Payload("report", emptySet()) },
            screenshotCapture = { captured = true; null },
            clipboardWrite = { _, _ -> true },
            chooserLaunch = { _, _, _ -> true },
        )

        assertFalse("the post-crash banner's text-only share stays text-only", captured)
    }
}
