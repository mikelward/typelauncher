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

    fun reorder(fromIndex: Int, toIndex: Int) {
        val current = dockedIds.toList()
        if (fromIndex !in current.indices) return
        val clampedTo = toIndex.coerceIn(0, current.lastIndex)
        if (fromIndex == clampedTo) return
        val moved = current[fromIndex]
        val withoutMoved = current.subList(0, fromIndex) + current.subList(fromIndex + 1, current.size)
        val rebuilt = withoutMoved.subList(0, clampedTo) + moved + withoutMoved.subList(clampedTo, withoutMoved.size)
        dockedIds = LinkedHashSet(rebuilt)
        save()
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

    /**
     * When true, the recents row is permanently visible above the keyboard
     * (as its own card below the dock) without requiring the drag-up gesture.
     * Orthogonal to [isDockEnabled] — recents can be on while the dock is off
     * and vice versa.
     */
    var isRecentsAlwaysShown: Boolean
        get() = sharedPreferences.getBoolean(KEY_RECENTS_ALWAYS_SHOWN, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_RECENTS_ALWAYS_SHOWN, value)
                .apply()
        }

    /**
     * Opt-in toggle for the in-app notification bar (Settings → "Show
     * notifications"). Off by default. Distinct from the system notification
     * listener grant tracked by `ActiveNotifications.hasListenerAccess`: this
     * is the user's UI-level preference for the bar; the listener grant is
     * what makes the data flow work.
     */
    var isNotificationsEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_NOTIFICATIONS_ENABLED, value)
                .apply()
        }

    /**
     * Controls whether the soft keyboard is auto-shown when Home is brought to
     * the foreground. When `true` (default) the search field is focused and
     * `keyboard.show()` runs on cold start, matching the original always-typing
     * launcher behavior. When `false` the search field is not auto-focused and
     * `MainActivity` overrides the manifest's `stateAlwaysVisible` softInputMode
     * with `stateHidden` so the activity-level "always show" doesn't undo the
     * preference.
     */
    var isKeyboardAutoShown: Boolean
        get() = sharedPreferences.getBoolean(KEY_KEYBOARD_AUTO_SHOWN, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_KEYBOARD_AUTO_SHOWN, value)
                .apply()
        }

    /**
     * Opt-in toggle for the auto-generated category folders (Settings → "Show
     * folders"). Off by default. When on and the search box is empty, the home
     * screen renders folder tiles instead of the flat app list; typing into the
     * search box reverts to the flat list and closes any open folder.
     */
    var areFoldersEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_FOLDERS_ENABLED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_FOLDERS_ENABLED, value)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_ICON_COUNT = "dock_icon_count"
        const val KEY_APP_LIST_ICON_ONLY = "app_list_icon_only"
        const val KEY_APP_LIST_SORT_ORDER = "app_list_sort_order"
        const val KEY_RECENTS_ALWAYS_SHOWN = "recents_always_shown"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_KEYBOARD_AUTO_SHOWN = "keyboard_auto_shown"
        const val KEY_FOLDERS_ENABLED = "folders_enabled"
    }
}

private fun android.content.SharedPreferences.deriveDockIconCountFromLegacySize(): Int {
    val legacySize = getInt(LEGACY_KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
    return dockSlotCountForIconSize(DEFAULT_DOCK_SCREEN_WIDTH_DP, legacySize)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
}

private const val LEGACY_KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
