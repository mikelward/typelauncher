package app.typelauncher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

private const val LAUNCHER_DEBUG_TAG = "TypeLauncherDebug"
internal const val LOG_BUFFER_MAX_ENTRIES = 300

/**
 * Cap for a single buffer entry, so one pathological stack trace can't dominate
 * the 300-entry buffer — and, with it, the shareable report. The report's own
 * budget is enforced separately by total characters in [BugReport] (strings
 * parcel as UTF-16, so the Binder limit is about total bytes, not entry count);
 * this is what keeps the count-based buffer bound meaningful.
 */
internal const val LOG_BUFFER_MAX_ENTRY_CHARS = 2_000

/**
 * How many `at …` frames per Throwable in the cause chain an entry keeps. The
 * deep tail of a stack trace is platform plumbing (Looper / Choreographer /
 * ActivityThread, Compose recomposition internals) that tells you nothing, and
 * in a release build every frame is obfuscated anyway — while a single 80-frame
 * dump used to evict a quarter of the buffer the report exists to carry. Keep
 * the throw site and its immediate callers, and say how many were dropped.
 */
private const val COMPACT_STACK_FRAMES = 8

internal object LauncherDebugLog {
    private val buffer = ArrayDeque<String>(LOG_BUFFER_MAX_ENTRIES)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /**
     * A downstream consumer of every recorded line, in addition to the in-memory
     * buffer — e.g. [DebugFileSink], which mirrors the buffer to disk so it
     * survives the process ending. Registered by the [Application] at startup.
     */
    fun interface Sink {
        fun log(line: String)
    }

    // Copy-on-write so [record] can fan out without holding a lock across the
    // sink call (a sink must never be able to deadlock the buffer). Sinks are
    // added once at startup and effectively never removed, so writes are rare.
    private val sinks = CopyOnWriteArrayList<Sink>()

    fun addSink(sink: Sink) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: Sink) {
        sinks.remove(sink)
    }

    /** Test-only: drops every registered sink so tests don't leak into each other. */
    internal fun clearSinksForTest() {
        sinks.clear()
    }

    fun event(message: String) {
        record('D', message, throwable = null)
        Log.d(LAUNCHER_DEBUG_TAG, message)
        // Mirror into Crashlytics so the most recent lines ride along on any
        // future crash report, giving us the same context the bug-report helper
        // would attach — minus the app identifiers, which stay on the device
        // (see [TelemetryRedaction]).
        LauncherTelemetry.log(TelemetryRedaction.redact(message))
    }

    /**
     * Logcat-only diagnostic for high-frequency, per-icon detail (icon-tile
     * composition, dynamic-calendar resolution). Goes to `adb logcat -s
     * TypeLauncherDebug` like [event], but is deliberately kept *out* of the
     * bug-report ring buffer and Crashlytics breadcrumbs: at one line per app
     * icon per render size it would otherwise overflow the 300-entry buffer on
     * a cold start and evict the lifecycle/state context the bug report exists
     * to capture.
     */
    fun trace(message: String) {
        Log.d(LAUNCHER_DEBUG_TAG, message)
    }

    fun warning(message: String, throwable: Throwable? = null) {
        record('W', message, throwable)
        Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
        LauncherTelemetry.log("WARN ${TelemetryRedaction.redact(message)}")
        // The throwable is redacted too, not just the breadcrumb: Crashlytics
        // uploads an exception's message verbatim, and a platform exception
        // routinely quotes what failed — `ActivityNotFoundException` carries
        // the intent, a `LauncherApps` SecurityException the package.
        if (throwable != null) LauncherTelemetry.recordException(throwable.redactedForTelemetry())
    }

    /**
     * Records an uncaught exception to the buffer (and logcat) for the crash
     * handler, **without** the Crashlytics non-fatal path. Crashlytics' own
     * chained uncaught handler reports the same throwable as a fatal, so routing
     * it through [warning]'s `recordException` too would double-count it as both
     * a non-fatal and a fatal event (Codex on PR #592). The buffer record still
     * fans out to the file sink, so the crash line is persisted for the report.
     */
    internal fun recordUncaught(message: String, throwable: Throwable) {
        record('W', message, throwable)
        Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
    }

    fun activityCallback(activity: Activity, callback: String, intent: Intent? = activity.intent) {
        event(
            "$callback taskId=${activity.taskId} finishing=${activity.isFinishing} " +
                "changingConfig=${activity.isChangingConfigurations} intent=${intent.debugSummary()}",
        )
    }

    /** Returns the captured log lines, oldest first. */
    fun snapshot(): List<String> = synchronized(buffer) { buffer.toList() }

    /** Test-only: empties the in-memory ring buffer so tests start from a known state. */
    internal fun clearForTest() {
        synchronized(buffer) { buffer.clear() }
    }

    private fun record(level: Char, message: String, throwable: Throwable?) {
        val timestamp = timestampFormat.get().format(Date())
        val entry = if (throwable == null) {
            "$timestamp $level $LAUNCHER_DEBUG_TAG: $message"
        } else {
            "$timestamp $level $LAUNCHER_DEBUG_TAG: $message\n${compactStackTrace(throwable).trimEnd()}"
        }
        val bounded = if (entry.length > LOG_BUFFER_MAX_ENTRY_CHARS) {
            entry.take(LOG_BUFFER_MAX_ENTRY_CHARS) + "…(truncated)"
        } else {
            entry
        }
        synchronized(buffer) {
            if (buffer.size >= LOG_BUFFER_MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(bounded)
        }
        // Fan out to disk (and any other) sinks outside the buffer lock. Each is
        // best-effort: a sink that throws must never lose the log line for the
        // others or crash the caller (this runs on the app's own threads).
        sinks.forEach { runCatching { it.log(bounded) } }
    }

    /**
     * Formats [t] like [Throwable.printStackTrace] but keeps only the top
     * [maxFrames] frames per Throwable in the cause chain, summarising the rest
     * as `... N more` so a reader can tell frames were elided rather than
     * absent. Replaces `Log.getStackTraceString`, which dumps every frame — the
     * deep tail is platform plumbing, and one such entry used to crowd real
     * events out of the buffer.
     *
     * Cyclic cause chains (`a.cause = b; b.cause = a`) are guarded via an
     * identity set, as `printStackTrace` does, so a pathological Throwable can't
     * spin the calling thread. Suppressed exceptions surface as a one-line
     * summary per parent so `use { … }` close failures aren't silently lost;
     * their frames are dropped to keep the entry tight. Not backed by
     * `android.util.Log`, so it also works in plain JUnit tests. Visible for
     * tests.
     */
    internal fun compactStackTrace(t: Throwable, maxFrames: Int = COMPACT_STACK_FRAMES): String =
        buildString {
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var current: Throwable? = t
            var depth = 0
            while (current != null) {
                val cur = current
                if (!seen.add(cur)) {
                    append("\n\t[CIRCULAR REFERENCE: ").append(cur.javaClass.name).append(']')
                    break
                }
                if (depth > 0) append("\nCaused by: ")
                append(cur.javaClass.name)
                // A getMessage() override can throw, and this trace is rendered
                // while already logging a failure — keep the type and frames
                // rather than let a second throwable escape the logger.
                runCatching { cur.message }.getOrNull()?.let { append(": ").append(it) }
                val frames = cur.stackTrace
                val keep = minOf(maxFrames, frames.size)
                for (i in 0 until keep) append("\n\tat ").append(frames[i])
                if (frames.size > keep) append("\n\t... ").append(frames.size - keep).append(" more")
                for (suppressed in cur.suppressed) {
                    append("\n\tSuppressed: ").append(suppressed.javaClass.name)
                    runCatching { suppressed.message }.getOrNull()?.let { append(": ").append(it) }
                }
                current = cur.cause
                depth++
            }
        }
}

/**
 * A telemetry-safe stand-in for [this]: the exception types and stack traces of
 * the whole cause chain, and **no messages at all**.
 *
 * Crashlytics uploads an exception's message verbatim, and a platform exception
 * routinely quotes what failed — `ActivityNotFoundException` embeds the intent,
 * so a contact's number or address can ride in it; `LauncherApps` failures name
 * the package. Two rounds of trying to *sanitize* those messages each turned up
 * another payload the sanitizer didn't know about (URIs after packages), which
 * is the signal to stop sanitizing and start omitting: the type and the stack
 * trace are what a non-fatal is read for, and they cannot carry a payload.
 * The full message stays in the on-device log, which the user reviews before
 * sharing.
 *
 * Iterative and identity-guarded rather than recursive, because a cause chain
 * can be cyclic (`a.initCause(b); b.initCause(a)`) — the same guard
 * [compactStackTrace] already keeps. A `StackOverflowError` raised while
 * *preparing* a log line would take out the caller it was logging for.
 */
internal fun Throwable.redactedForTelemetry(): Throwable {
    val chain = mutableListOf<Throwable>()
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        chain += current
        current = current.cause
    }
    var redacted: Throwable? = null
    for (link in chain.asReversed()) {
        redacted = RedactedThrowable(link.javaClass.name, redacted).also { it.stackTrace = link.stackTrace }
    }
    return redacted ?: RedactedThrowable(javaClass.name, null).also { it.stackTrace = stackTrace }
}

/**
 * Stand-in produced by [redactedForTelemetry]. Its message is the original
 * exception's class name, so a Crashlytics report still says what was thrown
 * even though every report of this kind shares one type.
 */
internal class RedactedThrowable(message: String, cause: Throwable?) : RuntimeException(message, cause)

internal fun Intent?.debugSummary(): String {
    if (this == null) return "null"
    val categories = categories?.sorted().orEmpty()
    return buildString {
        append("action=").append(action ?: "null")
        append(" categories=").append(categories)
        append(" flags=0x").append(Integer.toHexString(flags))
        append(" component=").append(component?.flattenToShortString() ?: "null")
        append(" package=").append(`package` ?: "null")
        append(" data=").append(data.redactedSummary())
        append(" extras=").append(extras.debugSummary())
    }
}

/**
 * A URI reduced to the one part that cannot name anything of the user's: its
 * scheme. Everything after that is payload, and for this launcher the payload
 * is routinely somebody's data — `smsto:` / `mailto:` carry a contact's phone
 * number or email address verbatim ([ContactActions]), `content://` carries a
 * contacts or calendar row id, `market://details?id=` carries a package name.
 *
 * The stakes are higher here than for the on-device log alone: every
 * [LauncherDebugLog.event] mirrors into Crashlytics as a breadcrumb, so an
 * un-redacted `dataString` uploads those values off the device on the next
 * crash — which both the *Privacy* rule in `AGENTS.md` and the published
 * `PRIVACY.md` ("these breadcrumbs do not include ... contact names") forbid.
 * Redacting at the one place that renders a URI for the log fixes every call
 * site at once, including ones added later.
 *
 * The authority goes too, though it reads like pure component identity. A
 * hierarchical URI's authority carries any userinfo — `https://alice:secret@
 * example.com/...` — and a `content://` authority names an installed app,
 * which is the inventory this redaction exists to keep off the device. An
 * allowlist of known-safe hosts would buy back "contacts or calendar" at the
 * cost of a list to maintain and get wrong.
 *
 * The scheme is the whole diagnostic value anyway: "the SENDTO for smsto:
 * found no handler" is the failure; which number it was addressed to is not.
 */
internal fun Uri?.redactedSummary(): String {
    if (this == null) return "null"
    val scheme = scheme ?: return "\u2026"
    val hasRedactedRemainder = if (isOpaque) {
        !schemeSpecificPart.isNullOrEmpty()
    } else {
        !authority.isNullOrEmpty() || !path.isNullOrEmpty() ||
            !query.isNullOrEmpty() || !fragment.isNullOrEmpty()
    }
    return scheme + ":" + if (hasRedactedRemainder) "\u2026" else ""
}

// Dumps each extra as `key=ValueClassName` for debug logs. The type-safe
// Bundle getters can't stand in here — the value type is unknown, which is
// exactly what the untyped `get` reports — so the deprecation is suppressed.
@Suppress("DEPRECATION")
internal fun Bundle?.debugSummary(): String =
    try {
        this?.keySet()
            ?.sorted()
            ?.joinToString(prefix = "[", postfix = "]") { key ->
                "$key=${get(key)?.javaClass?.simpleName ?: "null"}"
            }
            ?: "null"
    } catch (_: RuntimeException) {
        "[unreadable]"
    }

internal fun KeyEvent?.debugSummary(): String {
    if (this == null) return "null"
    return "action=$action keyCode=$keyCode repeat=$repeatCount downTime=$downTime eventTime=$eventTime"
}

internal fun Window.debugSummary(): String =
    "attributes={type=${attributes.type}, flags=0x${attributes.flags.toString(16)}, " +
        "softInputMode=0x${attributes.softInputMode.toString(16)}} " +
        "decor=${decorView.width}x${decorView.height} visibility=${decorView.visibility}"

internal fun LauncherUiState.debugSummary(): String =
    "destination=$destination lastWidgetPage=$lastWidgetPage " +
        "settingsOpen=$isSettingsOpen queryLength=${query.length} " +
        "filtered=${filteredApps.size} docked=${dockedApps.size} widgets=${widgetIds.size} " +
        "widgetPages=${widgetPages.size} " +
        "addingWidget=$isAddingWidget loadingAvailableWidgets=$isLoadingAvailableWidgets " +
        "availableWidgets=${availableWidgets.size} " +
        "agenda=${agenda::class.simpleName} agendaEnabled=$isAgendaEnabled dockEnabled=$isDockEnabled " +
        "appListLayout=$appListLayout dockIconSizeDp=$dockIconSizeDp " +
        "sortOrder=$appListSortOrder keyboardReservation=${keyboardReservation.bottomPx}/${keyboardReservation.source} " +
        "loadingApps=$isLoadingApps " +
        "freshAppLoadComplete=$isFreshAppLoadComplete homeReady=$isHomeReady " +
        "recents=${recentApps.size} recentsOpen=$isRecentsOpen " +
        "hidden=${hiddenApps.size} " +
        "themeMode=$themeMode playUpdate=${playUpdate::class.simpleName}"
