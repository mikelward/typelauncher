package app.typelauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class DebugFileSinkTest {

    // A fresh directory per test so the shared cache never leaks state between
    // tests (the crash handler is a process-global, so ordering must not matter).
    @get:Rule
    val folder = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        LauncherDebugLog.clearForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        LauncherDebugLog.clearForTest()
        LauncherDebugLog.clearSinksForTest()
    }

    @Test
    fun `a run's log is persisted and rotated into the previous-run slot next start`() {
        val dir = folder.newFolder()
        val first = DebugFileSink(dir)
        first.start()
        LauncherDebugLog.event("home ready, 12 apps")
        first.log("trigger") // writes the snapshot to disk (async)
        first.awaitIdleForTest()

        // Nothing rotated yet — this is the current run.
        assertNull(first.previousRunText())

        // Next launch (same directory): the prior run's file rotates into the
        // previous-run slot (on the worker — await it before reading).
        val second = DebugFileSink(dir)
        second.start()
        second.awaitIdleForTest()
        val previous = second.previousRunText()!!
        assertTrue("carries the prior run's log", previous.contains("home ready, 12 apps"))

        // Surfaced once: consuming removes it.
        second.consumeAllPreviousRuns()
        assertNull(second.previousRunText())
    }

    @Test
    fun `the crash handler persists a snapshot and chains`() {
        val dir = folder.newFolder()
        var chainedTo = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> chainedTo = true }

        val sink = DebugFileSink(dir)
        sink.start()
        LauncherDebugLog.event("home ready, 12 apps")

        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("layout blew up"),
        )
        assertTrue("chains to the previous handler (Crashlytics)", chainedTo)
        // The crash flush is fire-and-forget on the worker; await it before the
        // next launch rotates the file (production shares much later, so no wait).
        sink.awaitIdleForTest()

        // Next launch (same directory) sees the crash in the previous-run slot.
        val next = DebugFileSink(dir)
        next.start()
        next.awaitIdleForTest()
        val previous = next.previousRunText()!!
        assertTrue(previous.contains("home ready, 12 apps"))
        assertTrue(previous.contains("Uncaught exception in thread"))
    }

    @Test
    fun `an unread previous run survives later boring restarts`() {
        val dir = folder.newFolder()

        // Run A: an interesting log the user hasn't shared yet.
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("interesting state (run A)")
        runA.log("x")
        runA.awaitIdleForTest()

        // Run B: a boring cold start, then Run C — without a share in between.
        val runB = DebugFileSink(dir)
        runB.start()
        LauncherDebugLog.clearForTest()
        LauncherDebugLog.event("boring startup (run B)")
        runB.log("x")
        runB.awaitIdleForTest()

        val runC = DebugFileSink(dir)
        runC.start()
        runC.awaitIdleForTest()
        val previous = runC.previousRunText()!!
        assertTrue("A's unread log survives multiple restarts", previous.contains("interesting state (run A)"))
    }

    @Test
    fun `readPreviousRuns is empty when the last run left nothing`() {
        val sink = DebugFileSink(folder.newFolder())
        sink.start()
        assertNull(sink.previousRunText())
    }

    @Test
    fun `a routine (non-crash) prior run does not raise the banner`() {
        val dir = folder.newFolder()

        // Run A logs and its snapshot persists, but it ends without a crash — a
        // graceful exit, OS reclaim, force-stop, app update, or a silent kill,
        // none of which is an uncaught exception.
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        runA.log("x")
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        assertFalse("an ordinary process death never raises the banner", runB.hasUnacknowledgedCrash())
        // The log still persisted and is shareable in a bug report.
        assertTrue(runB.previousRunText()!!.contains("home ready, 12 apps"))
    }

    @Test
    fun `a crashed run raises the banner, which dismiss then silences`() {
        val dir = folder.newFolder()

        // Run A logs, then crashes (uncaught exception).
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        triggerCrash()

        // Next launch sees the crash — the banner shows.
        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue("a crashed prior run raises the banner", runB.hasUnacknowledgedCrash())

        // Dismiss renames the crash log off the crash suffix; it stays quiet even
        // across a boring restart, and its log is kept (still shareable).
        runB.acknowledgeCrashBanner()
        assertFalse("dismissed crash stays quiet", runB.hasUnacknowledgedCrash())
        assertTrue(
            "the dismissed run's log is kept and shareable",
            runB.previousRunText()!!.contains("home ready, 12 apps"),
        )

        val runC = DebugFileSink(dir)
        runC.start()
        assertFalse("the dismissal survives a boring restart", runC.hasUnacknowledgedCrash())
    }

    @Test
    fun `a later crash re-raises the banner after an earlier dismiss`() {
        val dir = folder.newFolder()

        // Run A crashes; dismiss its banner.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("run A")
        triggerCrash("run A boom")

        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue(runB.hasUnacknowledgedCrash())
        runB.acknowledgeCrashBanner()
        assertFalse(runB.hasUnacknowledgedCrash())

        // Run B then crashes too, leaving a newer crash file; the next start must
        // show the banner again. (runB.start() already installed runB's handler as
        // the default; triggering it records this crash.)
        LauncherDebugLog.clearForTest()
        LauncherDebugLog.event("run B")
        triggerCrash("run B boom")

        val runC = DebugFileSink(dir)
        runC.start()
        assertTrue("a newer crash re-raises the banner", runC.hasUnacknowledgedCrash())
    }

    @Test
    fun `sharing a crashed run clears the banner`() {
        val dir = folder.newFolder()

        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("run A")
        triggerCrash()

        val runB = DebugFileSink(dir)
        runB.start()
        assertTrue(runB.hasUnacknowledgedCrash())
        // Sharing reads then consumes the prior run (as BugReport does).
        runB.previousRunText()
        runB.consumeAllPreviousRuns()
        assertFalse("a shared crash leaves no banner", runB.hasUnacknowledgedCrash())
    }

    @Test
    fun `a crashed run outlives the boring runs that would have evicted it`() {
        val dir = folder.newFolder()

        // Run A crashes and is never shared.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("interesting state (run A)")
        triggerCrash()
        runA.awaitIdleForTest()

        // Then enough boring cold starts to overflow the retained-run window.
        repeat(8) { index ->
            val run = DebugFileSink(dir)
            run.start()
            LauncherDebugLog.clearForTest()
            LauncherDebugLog.event("boring startup $index")
            run.log("x")
            run.awaitIdleForTest()
        }

        val latest = DebugFileSink(dir)
        latest.start()
        latest.awaitIdleForTest()
        val runs = latest.readPreviousRuns()
        assertTrue(
            "the crash the banner is offering is still there",
            runs.any { it.crashed && it.lines.any { line -> line.contains("interesting state (run A)") } },
        )
        assertTrue("the banner still has something to offer", latest.hasUnacknowledgedCrash())
    }

    @Test
    fun `consuming names the runs to delete and leaves the rest alone`() {
        val dir = folder.newFolder()

        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("run A state")
        runA.log("x")
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        LauncherDebugLog.clearForTest()
        LauncherDebugLog.event("run B state")
        runB.log("x")
        runB.awaitIdleForTest()

        val runC = DebugFileSink(dir)
        runC.start()
        runC.awaitIdleForTest()
        val before = runC.readPreviousRuns()
        assertEquals("two unshared prior runs", 2, before.size)

        // A report that carried only the first run consumes only the first run —
        // deleting the other would destroy a log no report has carried yet.
        val carried = before.first { run -> run.lines.any { it.contains("run A state") } }
        runC.consumePreviousRuns(listOf(carried.id))

        val after = runC.readPreviousRuns()
        assertEquals("the uncarried run is kept", 1, after.size)
        assertTrue(
            "and it is the one the report never carried",
            after.single().lines.any { it.contains("run B state") },
        )
    }

    @Test
    fun `standing down a named crash keeps its log but stops the banner`() {
        val dir = folder.newFolder()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        triggerCrash()
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        runB.awaitIdleForTest()
        assertTrue(runB.hasUnacknowledgedCrash())

        // A report carried the crash but could not fit the whole run.
        val crashed = runB.readPreviousRuns().single { it.crashed }
        runB.acknowledgeCrashRuns(listOf(crashed.id))

        assertFalse("a reported crash stops raising the card", runB.hasUnacknowledgedCrash())
        assertTrue(
            "and its log is kept for a later report",
            runB.readPreviousRuns().single().lines.any { it.contains("home ready, 12 apps") },
        )
    }

    @Test
    fun `an unreadable retained run is skipped, kept, and said out loud`() {
        val dir = folder.newFolder()
        // A directory where a rotated log should be: readText() throws, the way a
        // corrupt or concurrently-removed file would. Dropping it silently left a
        // report with no crash section and nothing to explain the hole.
        val unreadable = File(dir, "debug-prev-000000.log")
        assertTrue(unreadable.mkdirs())

        val sink = DebugFileSink(dir)
        sink.start()
        sink.awaitIdleForTest()

        assertTrue("the unreadable run is skipped", sink.readPreviousRuns().isEmpty())
        assertTrue("its file is kept for a retry", unreadable.exists())
        assertTrue(
            "and the reason is in the log",
            LauncherDebugLog.snapshot().any { it.contains("could not read a retained prior run") },
        )
    }

    @Test
    fun `an acknowledged crash is still reported as a crash, not as a kill`() {
        val dir = folder.newFolder()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        triggerCrash()
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        runB.awaitIdleForTest()
        runB.acknowledgeCrashBanner()

        // The suffix is the only persisted record that this run crashed, so
        // standing the card down must not erase it: a later report would then
        // label a run holding an uncaught exception as "no crash recorded".
        assertFalse("the card stays down", runB.hasUnacknowledgedCrash())
        val run = runB.readPreviousRuns().single()
        assertTrue("still classified as a crash", run.crashed)
        assertTrue("and its log is intact", run.lines.any { it.contains("home ready, 12 apps") })
    }

    @Test
    fun `a crash that cannot be stood down keeps its prompt and says why`() {
        val dir = folder.newFolder()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        triggerCrash()
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        runB.awaitIdleForTest()
        val crashed = runB.readPreviousRuns().single { it.crashed }

        // Block the rename: a non-empty directory already sitting at the
        // acknowledged name makes renameTo return false rather than throw, which
        // is the case a bare runCatching swallowed.
        val blocker = File(dir, crashed.id.removeSuffix(".crash.log") + ".crash-seen.log")
        assertTrue(blocker.mkdirs())
        assertTrue(File(blocker, "occupied").createNewFile())

        LauncherDebugLog.clearForTest()
        runB.acknowledgeCrashRuns(listOf(crashed.id))

        assertTrue("the prompt stays up rather than vanishing", runB.hasUnacknowledgedCrash())
        assertTrue(
            "and the reason is in the log",
            LauncherDebugLog.snapshot().any { it.contains("could not stand down a reported crash") },
        )
    }

    @Test
    fun `a run stays consumable after its crash was stood down mid-share`() {
        val dir = folder.newFolder()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        triggerCrash()
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        runB.awaitIdleForTest()
        // A share reads the run, capturing its id...
        val carried = runB.readPreviousRuns().single()

        // ...and the user taps Dismiss before the share finishes, renaming the
        // file. An id tied to the file name would no longer match, so the run
        // the report carried whole would survive and be reported again.
        runB.acknowledgeCrashBanner()

        assertEquals("the run is still found and consumed", setOf(carried.id), runB.consumePreviousRuns(listOf(carried.id)))
        assertTrue("and is gone", runB.readPreviousRuns().isEmpty())
    }

    @Test
    fun `consuming reports what it could not delete`() {
        val dir = folder.newFolder()
        val runA = DebugFileSink(dir)
        runA.start()
        LauncherDebugLog.event("home ready, 12 apps")
        runA.log("x")
        runA.awaitIdleForTest()

        val runB = DebugFileSink(dir)
        runB.start()
        runB.awaitIdleForTest()
        val run = runB.readPreviousRuns().single()

        // A non-empty directory in place of the file: delete() returns false
        // rather than throwing, which a bare runCatching swallowed.
        val file = dir.listFiles()!!.single { it.name.startsWith("debug-prev-") }
        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        assertTrue(File(file, "occupied").createNewFile())

        LauncherDebugLog.clearForTest()
        assertTrue("nothing is claimed as deleted", runB.consumePreviousRuns(listOf(run.id)).isEmpty())
        assertTrue(
            "and the reason is in the log",
            LauncherDebugLog.snapshot().any { it.contains("could not consume a reported prior run") },
        )
    }

    @Test
    fun `an eviction that cannot delete says so`() {
        val dir = folder.newFolder()

        // Nine boring runs, so the retention cap has work to do at the tenth
        // start — with one of the files replaced by a non-empty directory, which
        // delete() refuses by returning false rather than throwing.
        repeat(9) { index ->
            val run = DebugFileSink(dir)
            run.start()
            LauncherDebugLog.clearForTest()
            LauncherDebugLog.event("boring startup $index")
            run.log("x")
            run.awaitIdleForTest()
        }
        val oldest = dir.listFiles()!!.filter { it.name.startsWith("debug-prev-") }.minBy { it.lastModified() }
        assertTrue(oldest.delete())
        assertTrue(oldest.mkdirs())
        assertTrue(File(oldest, "occupied").createNewFile())
        // Creating it just now made it the newest by mtime, which would put it
        // last in the eviction order; eviction is oldest-first.
        assertTrue(oldest.setLastModified(1_000L))

        LauncherDebugLog.clearForTest()
        val latest = DebugFileSink(dir)
        latest.start()
        latest.awaitIdleForTest()

        assertTrue("the undeletable file is still there", oldest.exists())
        assertTrue(
            "and the reason is in the log",
            LauncherDebugLog.snapshot().any { it.contains("could not evict an old prior run") },
        )
    }

    /** Fires the currently-installed default handler, as an OS crash would. */
    private fun triggerCrash(message: String = "boom") {
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException(message))
    }
}

/**
 * The prior runs' lines joined, the way the old single-blob read returned them,
 * or null when there are none — so these tests keep asserting on content rather
 * than on the per-run shape, which [BugReportPayloadTest] covers.
 */
private fun DebugFileSink.previousRunText(): String? =
    readPreviousRuns().flatMap { it.lines }.joinToString("\n").takeIf { it.isNotBlank() }

/** Consumes every prior run the sink holds, as a report that carried them all does. */
private fun DebugFileSink.consumeAllPreviousRuns() =
    consumePreviousRuns(readPreviousRuns().map { it.id })
