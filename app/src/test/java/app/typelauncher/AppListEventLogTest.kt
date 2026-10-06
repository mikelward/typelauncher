package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** The bug report's history of app-list changes. */
class AppListEventLogTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun recordsOldestFirstWithATimestamp() {
        var now = 0L
        val log = AppListEventLog(nowMillis = { now })

        log.record("installed com.example.app user=0")
        now = 1_000
        log.record("reloaded for packageAdded com.example.app: 10 apps")

        assertEquals(
            listOf(
                "${formatLogTimestamp(0, utc)} installed com.example.app user=0",
                "${formatLogTimestamp(1_000, utc)} reloaded for packageAdded com.example.app: 10 apps",
            ),
            log.lines(utc),
        )
    }

    @Test
    fun aRunOfTheSameEventCollapsesIntoOneCountedLine() {
        // An app that reports itself changed every minute must not push the
        // install the report is for out of the history.
        var now = 0L
        val log = AppListEventLog(capacity = 3, nowMillis = { now })
        log.record("installed com.example.app user=0")
        repeat(10) {
            now += 60_000
            log.record("reloaded for packageChanged com.example.chatty: 10 apps")
        }

        assertEquals(
            listOf(
                "${formatLogTimestamp(0, utc)} installed com.example.app user=0",
                "${formatLogTimestamp(60_000, utc)} reloaded for packageChanged com.example.chatty: 10 apps " +
                    "(×10, last ${formatLogTimestamp(600_000, utc)})",
            ),
            log.lines(utc),
        )
    }

    @Test
    fun keepsOnlyTheNewestEntries() {
        val log = AppListEventLog(capacity = 2, nowMillis = { 0 })
        log.record("a")
        log.record("b")
        log.record("c")

        assertEquals(listOf("b", "c"), log.lines(utc).map { it.substringAfterLast(' ') })
    }

    @Test
    fun aProfilesWholeAppSetIsSummarizedNotListed() {
        // A work profile turning on reports every app in it at once.
        val names = (1..40).map { "com.example.app$it" }

        assertEquals("com.example.one", describePackageBatch(listOf("com.example.one")))
        assertEquals("2 packages (com.a, com.b)", describePackageBatch(listOf("com.a", "com.b")))
        assertEquals(
            "40 packages (com.example.app1, com.example.app2, com.example.app3, …)",
            describePackageBatch(names),
        )
    }
}
