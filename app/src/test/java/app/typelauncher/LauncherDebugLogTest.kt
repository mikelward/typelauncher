package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherDebugLogTest {
    @Before
    fun resetBuffer() {
        LauncherDebugLog.clearForTest()
    }

    @Test
    fun snapshotIsEmptyAfterReset() {
        assertEquals(emptyList<String>(), LauncherDebugLog.snapshot())
    }

    @Test
    fun eventsAndWarningsAreCapturedInOrder() {
        LauncherDebugLog.event("first event")
        LauncherDebugLog.warning("a warning", IllegalStateException("boom"))
        LauncherDebugLog.event("third event")

        val snapshot = LauncherDebugLog.snapshot()
        assertEquals(3, snapshot.size)
        assertTrue(snapshot[0].contains(" D TypeLauncherDebug: first event"))
        assertTrue(snapshot[1].contains(" W TypeLauncherDebug: a warning"))
        assertTrue(snapshot[1].contains("IllegalStateException: boom"))
        assertTrue(snapshot[2].contains(" D TypeLauncherDebug: third event"))
    }

    @Test
    fun traceLinesAreNotCapturedInTheBugReportBuffer() {
        LauncherDebugLog.event("kept event")
        LauncherDebugLog.trace("iconTile com.example sizePx=89 adaptive")
        LauncherDebugLog.trace("dynamicCalendarIcon: com.example resolved=true")

        // trace() is logcat-only: it must never reach the ring buffer the
        // bug report dumps, so the per-icon icon firehose can't evict the
        // lifecycle/state context that buffer exists to capture.
        val snapshot = LauncherDebugLog.snapshot()
        assertEquals(1, snapshot.size)
        assertTrue(snapshot[0].contains(" D TypeLauncherDebug: kept event"))
    }

    @Test
    fun ringBufferEvictsOldestEntriesWhenAtCapacity() {
        repeat(LOG_BUFFER_MAX_ENTRIES + 50) { index ->
            LauncherDebugLog.event("event $index")
        }

        val snapshot = LauncherDebugLog.snapshot()
        assertEquals(LOG_BUFFER_MAX_ENTRIES, snapshot.size)
        assertTrue("oldest retained entry is event 50", snapshot.first().endsWith("event 50"))
        assertTrue(
            "newest entry is the last logged event",
            snapshot.last().endsWith("event ${LOG_BUFFER_MAX_ENTRIES + 49}"),
        )
    }

    @Test
    fun oneHugeEntryCannotDominateTheBuffer() {
        LauncherDebugLog.warning("a warning " + "x".repeat(50_000))

        val entry = LauncherDebugLog.snapshot().single()
        assertTrue(
            "entry is capped",
            entry.length <= LOG_BUFFER_MAX_ENTRY_CHARS + "…(truncated)".length,
        )
        assertTrue("and says it was cut", entry.endsWith("…(truncated)"))
    }

    @Test
    fun stackTracesKeepTheirCauseChainAndDropTheDeepTail() {
        val cause = IllegalArgumentException("the root cause")
        LauncherDebugLog.warning("a warning", IllegalStateException("boom", cause))

        val entry = LauncherDebugLog.snapshot().single()
        assertTrue("keeps the thrown type", entry.contains("IllegalStateException: boom"))
        assertTrue("keeps the cause chain", entry.contains("Caused by: java.lang.IllegalArgumentException: the root cause"))
        assertTrue("keeps the throw site", entry.contains("\tat "))
        assertTrue("says frames were elided", entry.contains(" more"))
    }

    @Test
    fun aCyclicCauseChainTerminates() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        val trace = LauncherDebugLog.compactStackTrace(second)
        assertTrue("stops at the cycle", trace.contains("CIRCULAR REFERENCE"))
    }
}
