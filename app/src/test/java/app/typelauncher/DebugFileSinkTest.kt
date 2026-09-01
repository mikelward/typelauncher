package app.typelauncher

import com.mikelward.androidlog.DebugLog
import java.io.File
import com.mikelward.androidlog.safe
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
        LauncherDebugLog.resetForTest()
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        LauncherDebugLog.resetForTest()
    }

    /**
     * A timed-out read must not inherit an *earlier* read's file list.
     *
     * `BugReport.share` clears only when the clipboard write lands, so a share
     * that read the files and then failed to copy leaves them on disk with the
     * sink still holding their list. If the next share's read times out and that
     * list is left standing, its clear deletes runs its own report never
     * contained (Codex, PR #707) — the same loss as the test below, reached from
     * stale state rather than from a late task.
     */
    @Test
    fun `a timed-out read does not inherit the previous read's files`() {
        val dir = folder.newFolder()
        val previous = File(dir, "debug-prev-1.log").apply { writeText("earlier run\n") }
        val worker = java.util.concurrent.ScheduledThreadPoolExecutor(
            1,
        ) { runnable -> Thread(runnable, "test-debug-log").apply { isDaemon = true } }
        val occupied = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(1)
        try {
            val sink = DebugFileSink({ dir }, 0L, { worker }, 200L)

            // A first share reads the run, then fails to reach the clipboard, so
            // nothing is cleared and the files stay on disk.
            assertTrue(
                "the first read must actually surface the run",
                sink.readPreviousRun()?.contains("earlier run") == true,
            )
            assertTrue("and must not have deleted it", previous.exists())

            // The worker then wedges, so the next share's read times out.
            worker.execute {
                running.countDown()
                runCatching { occupied.await(30, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertNull("the second read must give up", sink.readPreviousRun())

            occupied.countDown()
            sink.clearPreviousRun()

            assertTrue(
                "a run absent from the second report must survive its clear",
                previous.exists(),
            )
        } finally {
            occupied.countDown()
            worker.shutdownNow()
        }
    }

    /**
     * A clear that outlives its bound must still delete the files *it* was given.
     *
     * Bounding [DebugFileSink.clearPreviousRun] leaves its task queued on
     * purpose — deleting files the caller did receive is the whole point of it,
     * so it should still happen if the worker recovers. But the test above
     * publishes an empty list from a timed-out read, on the *caller* thread. So
     * a queued clear that read the live slot when it finally ran would find it
     * emptied by an unrelated share and delete nothing: a run the user already
     * shared stays on disk, and its crash banner comes back (Codex, PR #707).
     *
     * The guard is taking the file list at submission rather than at execution.
     * **Load-bearing**: reverting to reading `lastSurfaced` inside the task
     * fails this test.
     */
    @Test
    fun `a clear that outlives its bound deletes the files it was given`() {
        val dir = folder.newFolder()
        val previous = File(dir, "debug-prev-1.log").apply { writeText("earlier run\n") }
        val worker = java.util.concurrent.ScheduledThreadPoolExecutor(
            1,
        ) { runnable -> Thread(runnable, "test-debug-log").apply { isDaemon = true } }
        val occupied = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(1)
        try {
            val sink = DebugFileSink({ dir }, 0L, { worker }, 200L)

            // A share reads the run and copies it, so this clear is entitled to
            // delete exactly that file.
            assertTrue(
                "the read must actually surface the run",
                sink.readPreviousRun()?.contains("earlier run") == true,
            )

            // The worker wedges before the clear can run, so the clear gives up
            // waiting and leaves its task queued.
            worker.execute {
                running.countDown()
                runCatching { occupied.await(30, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS))
            sink.clearPreviousRun()
            assertTrue("the wedged clear cannot have run yet", previous.exists())

            // A second share then times out reading, emptying the slot the
            // queued clear would otherwise consult.
            assertNull("the second read must give up", sink.readPreviousRun())

            occupied.countDown()
            // FIFO: the queued clear runs before this barrier returns.
            worker.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS)

            assertFalse(
                "the recovered clear must delete the run its own share reported",
                previous.exists(),
            )
        } finally {
            occupied.countDown()
            worker.shutdownNow()
        }
    }

    /**
     * A timed-out read must leave nothing for a later clear to delete.
     *
     * `BugReport.share` clears the prior run once the report is on the clipboard,
     * and the sink deletes whatever the last read surfaced. Bounding the read
     * made that reachable: the abandoned task could run later on a recovered
     * worker and publish files this caller never received, so a report built
     * *without* those logs would go on to delete them — destroying the only copy
     * (Codex, PR #707).
     *
     * Two guards prevent it and **either one alone is sufficient**, verified by
     * reverting them separately: the timed-out future is cancelled, so the
     * abandoned task never runs; and the file list is published on *receipt*
     * rather than by the worker, so a task that did run could not publish it
     * anyway. This test fails only with both removed — stated plainly rather
     * than claiming each is load-bearing. They are kept as belt and braces
     * because the cost is nil and the failure they prevent destroys the user's
     * only copy of a log.
     */
    @Test
    fun `a timed-out read leaves the earlier runs on disk for the next attempt`() {
        val dir = folder.newFolder()
        val previous = File(dir, "debug-prev-1.log").apply { writeText("earlier run\n") }
        val occupied = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.ScheduledThreadPoolExecutor(
            1,
        ) { runnable -> Thread(runnable, "test-debug-log").apply { isDaemon = true } }
        try {
            worker.execute {
                running.countDown()
                runCatching { occupied.await(30, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS))

            val sink = DebugFileSink({ dir }, 0L, { worker }, 200L)
            assertNull("the read must give up rather than return the run", sink.readPreviousRun())

            // The worker recovers, so the abandoned read can now run -- and the
            // share path's clear follows it.
            occupied.countDown()
            sink.clearPreviousRun()

            assertTrue(
                "a run the caller never received must survive the clear",
                previous.exists(),
            )
        } finally {
            occupied.countDown()
            worker.shutdownNow()
        }
    }

    /**
     * A wedged worker must not park the bug-report path for the life of the
     * process.
     *
     * `readPreviousRun()` is synchronous and used to wait on the worker with an
     * unbounded `get()`, so a worker that never came back parked its caller
     * forever. androidlog#31 fixed the same defect in the shared library's
     * sink; this one is Type Launcher's own and did not inherit it
     * (Codex, PR #707).
     *
     * The wedge is staged rather than waited for: the injected worker's only
     * thread is occupied by a task released in `finally`, so the timeout is
     * reached deterministically, and the injected bound keeps the case to a
     * fraction of a second.
     */
    @Test
    fun `an earlier-runs read gives up when the worker never gets to it`() {
        val dir = folder.newFolder()
        val occupied = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.ScheduledThreadPoolExecutor(
            1,
        ) { runnable -> Thread(runnable, "test-debug-log").apply { isDaemon = true } }
        try {
            worker.execute {
                running.countDown()
                runCatching { occupied.await(30, java.util.concurrent.TimeUnit.SECONDS) }
            }
            assertTrue(
                "the worker must actually be occupied, or this proves nothing",
                running.await(5, java.util.concurrent.TimeUnit.SECONDS),
            )

            val sink = DebugFileSink(
                { dir },
                0L,
                { worker },
                200L,
            )
            val startedAt = System.nanoTime()
            val read = sink.readPreviousRun()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertNull("a read the worker never reaches must answer null", read)
            assertTrue(
                "it must give up at its own bound, not wait the worker out: $elapsedMs ms",
                elapsedMs < 15_000,
            )
        } finally {
            occupied.countDown()
            worker.shutdownNow()
        }
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
        assertNull(first.readPreviousRun())

        // Next launch (same directory): the prior run's file rotates into the
        // previous-run slot (on the worker — await it before reading).
        val second = DebugFileSink(dir)
        second.start()
        second.awaitIdleForTest()
        val previous = second.readPreviousRun()!!
        assertTrue("carries the prior run's log", previous.contains("home ready, 12 apps"))

        // Surfaced once: clearing removes it.
        second.clearPreviousRun()
        assertNull(second.readPreviousRun())
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
        val previous = next.readPreviousRun()!!
        assertTrue(previous.contains("home ready, 12 apps"))
        assertTrue(previous.contains("Uncaught exception in thread"))
    }

    @Test
    fun `a pinned line survives in the persisted log after the ring evicts it`() {
        // The case pinning exists for, on the path pinning did not originally
        // reach: the startup lines roll out of the ring over a long run, then
        // the process ends, and the next launch reads this file. The in-memory
        // pinned buffer dies with the process, so if it is not written here the
        // previous-run section loses exactly the evidence (Codex on PR #689).
        val dir = folder.newFolder()
        val sink = DebugFileSink(dir)
        sink.start()

        LauncherDebugLog.pinnedEvent("homeStart via=%s", safe("chooser"))
        repeat(DebugLog.DEFAULT_MAX_ENTRIES + 1) { LauncherDebugLog.event("filler %s", it) }
        assertFalse(
            "the ring must have evicted it for this to be testing anything",
            LauncherDebugLog.snapshot().any { it.contains("homeStart via=chooser") },
        )

        sink.log("trigger") // writes the snapshot to disk (async)
        sink.awaitIdleForTest()

        val next = DebugFileSink(dir)
        next.start()
        next.awaitIdleForTest()
        assertTrue(next.readPreviousRun()!!.contains("homeStart via=chooser"))
    }

    @Test
    fun `the crash snapshot carries the icon-cache counters`() {
        // A foreground crash never reaches MainActivity.onStop, the other place
        // these are recorded, so this path is the only way the run that actually
        // crashed contributes any. It has to land *before* the snapshot is
        // written and inside the bounded flush, and a rebase has silently undone
        // that ordering once — hence a test on the persisted bytes rather than
        // on where the call sits (Codex on PR #689).
        val dir = folder.newFolder()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }

        val sink = DebugFileSink(dir)
        sink.start()
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(
            Thread.currentThread(),
            IllegalStateException("layout blew up"),
        )
        sink.awaitIdleForTest()

        val next = DebugFileSink(dir)
        next.start()
        next.awaitIdleForTest()
        assertTrue(next.readPreviousRun()!!.contains("AppIconLoader cache stats"))
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
        LauncherDebugLog.resetForTest()
        LauncherDebugLog.event("boring startup (run B)")
        runB.log("x")
        runB.awaitIdleForTest()

        val runC = DebugFileSink(dir)
        runC.start()
        runC.awaitIdleForTest()
        val previous = runC.readPreviousRun()!!
        assertTrue("A's unread log survives multiple restarts", previous.contains("interesting state (run A)"))
    }

    @Test
    fun `readPreviousRun is null when the last run left nothing`() {
        val sink = DebugFileSink(folder.newFolder())
        sink.start()
        assertNull(sink.readPreviousRun())
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
        assertTrue(runB.readPreviousRun()!!.contains("home ready, 12 apps"))
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
            runB.readPreviousRun()!!.contains("home ready, 12 apps"),
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
        LauncherDebugLog.resetForTest()
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
        // Sharing reads then clears the prior run (as BugReport does).
        runB.readPreviousRun()
        runB.clearPreviousRun()
        assertFalse("a shared crash leaves no banner", runB.hasUnacknowledgedCrash())
    }

    /** Fires the currently-installed default handler, as an OS crash would. */
    private fun triggerCrash(message: String = "boom") {
        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException(message))
    }
}
