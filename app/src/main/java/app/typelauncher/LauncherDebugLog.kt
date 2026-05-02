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

private const val LAUNCHER_DEBUG_TAG = "TypeLauncherDebug"
internal const val LOG_BUFFER_MAX_ENTRIES = 300

internal object LauncherDebugLog {
    private val buffer = ArrayDeque<String>(LOG_BUFFER_MAX_ENTRIES)
    private val timestampFormat = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    fun event(message: String) {
        record('D', message, throwable = null)
        if (BuildConfig.DEBUG) {
            Log.d(LAUNCHER_DEBUG_TAG, message)
        }
        // Mirror into Crashlytics so the most recent ~64 lines ride along on
        // any future crash report, giving us the same context the bug-report
        // helper would attach.
        LauncherTelemetry.log(message)
    }

    fun warning(message: String, throwable: Throwable? = null) {
        record('W', message, throwable)
        if (BuildConfig.DEBUG) {
            Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
        }
        LauncherTelemetry.log("WARN $message")
        if (throwable != null) LauncherTelemetry.recordException(throwable)
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
    "screen=$screen settingsOpen=$isSettingsOpen queryLength=${query.length} " +
        "filtered=${filteredApps.size} docked=${dockedApps.size} widgets=${widgetIds.size} " +
        "addingWidget=$isAddingWidget loadingAvailableWidgets=$isLoadingAvailableWidgets " +
        "availableWidgets=${availableWidgets.size} " +
        "agenda=${agenda::class.simpleName} dockEnabled=$isDockEnabled " +
        "appListIconOnly=$isAppListIconOnly dockIconCount=$dockIconCount " +
        "sortOrder=$appListSortOrder loadingApps=$isLoadingApps " +
        "freshAppLoadComplete=$isFreshAppLoadComplete homeReady=$isHomeReady " +
        "recents=${recentApps.size} recentsOpen=$isRecentsOpen " +
        "recentsAlwaysShown=$isRecentsAlwaysShown"
