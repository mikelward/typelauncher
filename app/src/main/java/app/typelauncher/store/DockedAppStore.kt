package app.typelauncher

import android.content.Context
import java.util.LinkedHashSet

internal class DockedAppStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var dockedIds = sharedPreferences.getString(KEY_DOCKED_APP_IDS, "").orEmpty()
        .split(DOCKED_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())

    val dockedAppIds: List<String>
        get() = dockedIds.toList()

    fun contains(appId: String): Boolean = appId in dockedIds

    fun dock(appId: String, maxDockedApps: Int): Boolean {
        if (appId in dockedIds) {
            return true
        }
        if (dockedIds.size >= maxDockedApps) {
            return false
        }
        dockedIds.add(appId)
        save()
        return true
    }

    fun undock(appId: String) {
        if (dockedIds.remove(appId)) {
            save()
        }
    }

    private fun save() {
        sharedPreferences.edit()
            .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "docked_apps"
        const val KEY_DOCKED_APP_IDS = "docked_app_ids"
        const val DOCKED_APP_ID_SEPARATOR = "\n"
    }
}

internal class DockSettingsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    var isDockEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DOCK_ENABLED, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_DOCK_ENABLED, value)
                .apply()
        }

    var dockIconSizeDp: Int
        get() = sharedPreferences
            .getInt(KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
            .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
        set(value) {
            sharedPreferences.edit()
                .putInt(KEY_DOCK_ICON_SIZE_DP, value.coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP))
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
    }
}
