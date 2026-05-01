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

    fun dock(appId: String) {
        if (appId in dockedIds) {
            return
        }
        dockedIds.add(appId)
        save()
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

    var dockIconCount: Int
        get() = sharedPreferences
            .takeIf { preferences -> preferences.contains(KEY_DOCK_ICON_COUNT) }
            ?.getInt(KEY_DOCK_ICON_COUNT, DEFAULT_DOCK_ICON_COUNT)
            ?: sharedPreferences.deriveDockIconCountFromLegacySize()
        set(value) {
            sharedPreferences.edit()
                .putInt(KEY_DOCK_ICON_COUNT, value.coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT))
                .apply()
        }

    var isAppListIconOnly: Boolean
        get() = sharedPreferences.getBoolean(KEY_APP_LIST_ICON_ONLY, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_APP_LIST_ICON_ONLY, value)
                .apply()
        }

    var appListSortOrder: AppListSortOrder
        get() = sharedPreferences.getString(KEY_APP_LIST_SORT_ORDER, null)
            ?.let { name -> runCatching { AppListSortOrder.valueOf(name) }.getOrNull() }
            ?: AppListSortOrder.Usage
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_APP_LIST_SORT_ORDER, value.name)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_ICON_COUNT = "dock_icon_count"
        const val KEY_APP_LIST_ICON_ONLY = "app_list_icon_only"
        const val KEY_APP_LIST_SORT_ORDER = "app_list_sort_order"
    }
}

private fun android.content.SharedPreferences.deriveDockIconCountFromLegacySize(): Int {
    val legacySize = getInt(LEGACY_KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
    return dockSlotCountForIconSize(DEFAULT_DOCK_SCREEN_WIDTH_DP, legacySize)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
}

private const val LEGACY_KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
