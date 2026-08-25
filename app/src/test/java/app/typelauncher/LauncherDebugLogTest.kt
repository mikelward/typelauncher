package app.typelauncher

import android.content.Intent
import android.net.Uri
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

    // A launcher builds SENDTO intents straight from a contact's phone number
    // and email address, and every event() line is mirrored to Crashlytics as a
    // breadcrumb — so an un-redacted `data=` uploads somebody's contacts off the
    // device on the next crash. The scheme is the whole diagnostic value.
    @Test
    fun opaqueUriKeepsOnlyItsScheme() {
        assertEquals("smsto:\u2026", Uri.fromParts("smsto", "+15550100", null).redactedSummary())
        assertEquals("mailto:\u2026", Uri.fromParts("mailto", "alice@example.com", null).redactedSummary())
        assertEquals("tel:\u2026", Uri.fromParts("tel", "+15550100", null).redactedSummary())
    }

    // The authority goes too: it carries any userinfo, and a content authority
    // names an installed app.
    @Test
    fun hierarchicalUriKeepsOnlyItsScheme() {
        assertEquals(
            "content:\u2026",
            Uri.parse("content://com.android.contacts/contacts/lookup/1234").redactedSummary(),
        )
        assertEquals(
            "market:\u2026",
            Uri.parse("market://details?id=com.example.app").redactedSummary(),
        )
        val summary = Uri.parse("https://alice:secret@example.com/inbox").redactedSummary()
        assertEquals("https:\u2026", summary)
        assertTrue("credentials must never reach the log", !summary.contains("alice"))
    }

    @Test
    fun uriWithNothingToRedactIsReportedWhole() {
        assertEquals("null", (null as Uri?).redactedSummary())
        assertEquals("content:", Uri.parse("content:").redactedSummary())
    }

    @Test
    fun intentSummaryRedactsItsData() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", "+15550100", null))

        val summary = intent.debugSummary()

        assertTrue(summary.contains("data=smsto:\u2026"))
        assertTrue("the dialed number must never reach the log", !summary.contains("5550100"))
    }


    // Crashlytics uploads an exception's message verbatim, and a platform
    // exception routinely quotes the intent or package that failed — so the
    // telemetry copy carries no message at all.
    @Test
    fun throwableMessagesAreDroppedForTelemetry() {
        val redacted = IllegalStateException("could not launch smsto:+15550100").redactedForTelemetry()

        assertEquals("java.lang.IllegalStateException", redacted.message)
        assertTrue("no payload survives", !redacted.message.orEmpty().contains("5550100"))
    }

    @Test
    fun throwableRedactionKeepsTheCauseChainAndStackTraces() {
        val cause = IllegalArgumentException("bad package com.example.mail")
        val original = IllegalStateException("launch failed", cause)

        val redacted = original.redactedForTelemetry()

        assertEquals("java.lang.IllegalArgumentException", redacted.cause?.message)
        assertEquals(original.stackTrace.first(), redacted.stackTrace.first())
        assertEquals(cause.stackTrace.first(), redacted.cause?.stackTrace?.first())
    }

    // A cyclic cause chain must not send the redactor into infinite recursion:
    // a StackOverflowError raised while preparing a log line would take out the
    // caller it was logging for.
    @Test
    fun throwableRedactionTerminatesOnACyclicCauseChain() {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        first.initCause(second)
        second.initCause(first)

        val redacted = first.redactedForTelemetry()

        assertEquals("java.lang.IllegalStateException", redacted.message)
        assertEquals("java.lang.IllegalArgumentException", redacted.cause?.message)
        assertEquals("stops at the cycle", null, redacted.cause?.cause)
    }

}
