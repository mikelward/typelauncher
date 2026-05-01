package app.typelauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Window

private const val LAUNCHER_DEBUG_TAG = "TypeLauncherDebug"

internal object LauncherDebugLog {
    fun event(message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(LAUNCHER_DEBUG_TAG, message)
        }
    }

    fun warning(message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            Log.w(LAUNCHER_DEBUG_TAG, message, throwable)
        }
    }

    fun activityCallback(activity: Activity, callback: String, intent: Intent? = activity.intent) {
        event(
            "$callback taskId=${activity.taskId} finishing=${activity.isFinishing} " +
                "changingConfig=${activity.isChangingConfigurations} intent=${intent.debugSummary()}",
        )
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
        "addingWidget=$isAddingWidget availableWidgets=${availableWidgets.size} " +
        "agenda=${agenda::class.simpleName} dockEnabled=$isDockEnabled dockIconCount=$dockIconCount"
