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
            "$timestamp $level $LAUNCHER_DEBUG_TAG: $message\n${Log.getStackTraceString(throwable).trimEnd()}"
        }
        synchronized(buffer) {
            if (buffer.size >= LOG_BUFFER_MAX_ENTRIES) buffer.removeFirst()
            buffer.addLast(entry)
        }
        // Fan out to disk (and any other) sinks outside the buffer lock. Each is
        // best-effort: a sink that throws must never lose the log line for the
        // others or crash the caller (this runs on the app's own threads).
        sinks.forEach { runCatching { it.log(entry) } }
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
