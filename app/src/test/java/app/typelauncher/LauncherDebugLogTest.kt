package app.typelauncher

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherDebugLogTest {
    private lateinit var previousZone: TimeZone

    @Before
    fun resetBuffer() {
        LauncherDebugLog.clearForTest()
        previousZone = TimeZone.getDefault()
    }

    @After
    fun restoreZone() {
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun snapshotIsEmptyAfterReset() {
        assertEquals(emptyList<String>(), LauncherDebugLog.snapshot())
        assertEquals(emptyList<String>(), LauncherDebugLog.pinnedSnapshot())
    }

    @Test
    fun iconCacheStatsAreRecordedAsOneBufferedLine() {
        // Buffered, not logcat-only: this is the copy that reaches the file
        // behind the next report's "Previous run" section, which is the only
        // place a crashed run's cache behavior can still be read (Codex on PR
        // #689). The per-lookup counters that used to carry it are logcat-only
        // now, so if this line stopped being an `event` the fact would go with
        // it, silently.
        AppIconLoader.logCacheStats()

        val line = LauncherDebugLog.snapshot().single { it.contains("AppIconLoader cache stats") }
        assertTrue(line, line.contains("entries="))
        assertTrue(line, line.contains("bytes="))
        assertTrue(line, line.contains("hits="))
        assertTrue(line, line.contains("misses="))
    }

    @Test
    fun pinnedEventOutlivesTheRingBufferThatEvictsIt() {
        // The whole reason pinning exists. Every real report has been captured
        // hours into a run, by which time the ring has turned over several
        // times and the lines explaining how the run started are gone — so the
        // test that matters is that a pinned line is still there *after* the
        // ring has evicted its own copy, not merely that it was recorded.
        LauncherDebugLog.pinnedEvent("process started")
        repeat(LOG_BUFFER_MAX_ENTRIES) { index -> LauncherDebugLog.event("filler %s", index) }

        assertTrue(
            "the ring evicted it",
            LauncherDebugLog.snapshot().none { it.contains("process started") },
        )
        assertTrue(
            "the pinned copy survived",
            LauncherDebugLog.pinnedSnapshot().any { it.contains("process started") },
        )
    }

    @Test
    fun pinnedEventIsAlsoAnOrdinaryLogLine() {
        // Pinning adds a second reference; it must not divert the line out of
        // the chronology, or the log would read as though startup logged
        // nothing at all.
        LauncherDebugLog.event("before")
        LauncherDebugLog.pinnedEvent("pinned line")
        LauncherDebugLog.event("after")

        val messages = LauncherDebugLog.snapshot().map { it.substringAfter("TypeLauncherDebug: ") }
        assertEquals(listOf("before", "pinned line", "after"), messages)
    }

    @Test
    fun pinnedBufferEvictsItsOwnOldestAndKeepsTheNewest() {
        repeat(PINNED_BUFFER_MAX_ENTRIES + 1) { index -> LauncherDebugLog.pinnedEvent("pinned %s", index) }

        val pinned = LauncherDebugLog.pinnedSnapshot()
        assertEquals(PINNED_BUFFER_MAX_ENTRIES, pinned.size)
        assertTrue("dropped the oldest", pinned.none { it.endsWith("pinned 0") })
        assertTrue("kept the newest", pinned.last().endsWith("pinned $PINNED_BUFFER_MAX_ENTRIES"))
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

        assertTrue(summary.full.contains("data=smsto:\u2026"))
        assertTrue("the dialed number must never reach the log", !summary.full.contains("5550100"))
        assertTrue("nor the mirrored copy", !summary.mirrored.contains("5550100"))
    }


    // The component and package name the user's installed apps, so they stay on
    // the device; the action, flags and the data URI's scheme are what a failed
    // launch is diagnosed from and ride along.
    @Test
    fun intentSummaryWithholdsAppIdentityFromTheMirrorButKeepsTheAction() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", "+15550100", null)).apply {
            `package` = "com.example.messages"
        }

        val summary = intent.debugSummary()

        assertTrue(summary.full.contains("package=com.example.messages"))
        assertTrue("app identity must not reach Crashlytics", !summary.mirrored.contains("com.example.messages"))
        assertTrue("the action is the diagnostic value", summary.mirrored.contains("action=android.intent.action.SENDTO"))
        assertTrue(summary.mirrored.contains("data=smsto:\u2026"))
    }

    // MainActivity is exported, so a third-party app picks the action,
    // categories, extra keys and scheme on any intent it sends. Those are fixed
    // vocabulary only on intents the launcher builds itself.
    @Test
    fun aCallerChosenActionIsWithheldWhileAFrameworkOneIsKept() {
        val hostile = Intent("com.example.mail.ACTION_SECRET").apply {
            addCategory("com.example.mail.CATEGORY_SECRET")
            putExtra("com.example.mail.EXTRA_SECRET", 1)
        }

        val summary = hostile.debugSummary()

        assertTrue("the on-device log keeps it", summary.full.contains("com.example.mail.ACTION_SECRET"))
        assertTrue("a caller-chosen action can name a package", !summary.mirrored.contains("com.example.mail"))
        // The shape survives even though the names do not.
        assertTrue(summary.mirrored.contains("<redacted>=Integer"))

        val framework = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }

        assertTrue(framework.debugSummary().mirrored.contains("android.intent.action.MAIN"))
        assertTrue(framework.debugSummary().mirrored.contains("android.intent.category.HOME"))
    }

    // A namespace-prefix test would wave this through: nothing stops a caller
    // naming its action under `android.`, so membership has to be exact.
    @Test
    fun aSpoofedFrameworkNamespaceIsStillWithheld() {
        val spoofed = Intent("android.alice@example.com").apply {
            addCategory("androidx.bob@example.com")
            putExtra("app.typelauncher.carol@example.com", 1)
        }

        val mirrored = spoofed.debugSummary().mirrored

        assertTrue("a prefix is not a namespace claim", !mirrored.contains("alice@example.com"))
        assertTrue(!mirrored.contains("bob@example.com"))
        assertTrue(!mirrored.contains("carol@example.com"))
    }

    @Test
    fun aCustomSchemeIsWithheldWhileAStandardOneIsKept() {
        val custom = Intent(Intent.ACTION_VIEW, Uri.parse("com-example-mail://open"))
        assertTrue(!custom.debugSummary().mirrored.contains("com-example-mail"))

        val standard = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/x"))
        assertTrue(standard.debugSummary().mirrored.contains("data=https:"))
    }

    // This is a type-to-search launcher: keys pressed on the home screen are
    // the query, so a run of key codes in a crash report reconstructs what
    // someone typed.
    @Test
    fun keyEventSummaryWithholdsWhichKeyFromTheMirror() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)

        val summary = event.debugSummary()

        assertTrue(summary.full.contains("keyCode=${KeyEvent.KEYCODE_A}"))
        assertTrue("keystrokes are search history", !summary.mirrored.contains("keyCode=${KeyEvent.KEYCODE_A}"))
        assertTrue("timing still rides along", summary.mirrored.contains("repeat=0"))
    }

    // The trap this exists for: the key handlers log the key code *twice* —
    // once as a top-level argument and once inside the summary. Redacting only
    // the summary leaves the Int argument to sail through on the type rule, so
    // the breadcrumb sequence still reconstructs the query. Both have to be
    // withheld, and a future edit that unwraps either fails here.
    @Test
    fun theKeyCodeIsWithheldFromEveryArgumentNotJustTheSummary() {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A)

        val line = formatLogMessage(
            "MainActivity.onKeyDown keyCode=%s event=%s",
            arrayOf(sensitive(KeyEvent.KEYCODE_A), event.debugSummary()),
            redactSensitive = true,
        )

        assertTrue(
            "a run of key codes reconstructs the user's query",
            !line.contains(KeyEvent.KEYCODE_A.toString()),
        )
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

    @Test
    fun timestampsAreIsoWithTheOffset() {
        // A fixed instant and an explicit zone: the format itself, with nothing
        // depending on when or where the test runs.
        assertEquals(
            "2023-11-15T09:13:20.000+11:00",
            formatLogTimestamp(1_700_000_000_000L, ZoneId.of("Australia/Sydney")),
        )
        // Rendered in the zone asked for, not in one baked in anywhere.
        assertEquals(
            "2023-11-14T17:13:20.000-05:00",
            formatLogTimestamp(1_700_000_000_000L, ZoneId.of("America/New_York")),
        )
    }

    // The bug this format exists to fix: the log outlives the process and is
    // read days later, so a line stamped in a zone the device has since left
    // has to say so. A formatter that captured the default zone when it was
    // constructed kept stamping the old one — silently, with nothing in the
    // file to reveal it.
    @Test
    fun aZoneChangeIsReflectedInLaterLines() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+10:00"))
        LauncherDebugLog.event("before the flight")

        TimeZone.setDefault(TimeZone.getTimeZone("GMT-05:00"))
        LauncherDebugLog.event("after the flight")

        val snapshot = LauncherDebugLog.snapshot()
        assertEquals(2, snapshot.size)
        assertTrue("first line carries the departure offset", snapshot[0].contains("+10:00 D "))
        assertTrue("second line carries the arrival offset", snapshot[1].contains("-05:00 D "))
    }
}
