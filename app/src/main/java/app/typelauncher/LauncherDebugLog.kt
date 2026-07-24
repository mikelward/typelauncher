package app.typelauncher

import android.app.Activity
import android.content.Intent
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
        // Mirror into Crashlytics so the most recent ~64 lines ride along on
        // any future crash report, giving us the same context the bug-report
        // helper would attach.
        LauncherTelemetry.log(message)
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
        LauncherTelemetry.log("WARN $message")
        if (throwable != null) LauncherTelemetry.recordException(throwable)
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

internal fun Intent?.debugSummary(): String {
    if (this == null) return "null"
    val categories = categories?.sorted().orEmpty()
    return buildString {
        append("action=").append(action ?: "null")
        append(" categories=").append(categories)
        append(" flags=0x").append(Integer.toHexString(flags))
        append(" component=").append(component?.flattenToShortString() ?: "null")
        append(" package=").append(`package` ?: "null")
        append(" data=").append(dataString ?: "null")
        append(" extras=").append(extras.debugSummary())
    }
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
