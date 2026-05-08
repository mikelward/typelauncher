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

    val hasBeenPrefilled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DOCK_PREFILLED, false)

    fun markPrefilled() {
        sharedPreferences.edit().putBoolean(KEY_DOCK_PREFILLED, true).apply()
    }

    private fun save() {
        sharedPreferences.edit()
            .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "docked_apps"
        const val KEY_DOCKED_APP_IDS = "docked_app_ids"
        const val KEY_DOCK_PREFILLED = "dock_prefilled"
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
     * Home pull-down behavior. Defaults to [NotificationPullDownBehavior.BarBelow]
     * for users without an explicit selection, including installs that only have
     * the legacy `notifications_enabled` boolean. The old persisted `Launcher`
     * enum name maps to BarBelow.
     */
    var notificationPullDownBehavior: NotificationPullDownBehavior
        get() = sharedPreferences.getString(KEY_NOTIFICATION_PULL_DOWN_BEHAVIOR, null)
            ?.let(::parseNotificationPullDownBehavior)
            ?: NotificationPullDownBehavior.BarBelow
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_NOTIFICATION_PULL_DOWN_BEHAVIOR, value.name)
                .apply()
        }

    /**
     * Controls whether the soft keyboard is auto-shown when Home is brought to
     * the foreground. When `true` (default) the search field is focused and
     * `keyboard.show()` runs on cold start, matching the original always-typing
     * launcher behavior. When `false` the search field is not auto-focused and
     * `MainActivity` applies `stateAlwaysHidden` so retained focus doesn't undo
     * the preference when the launcher resumes.
     */
    var isKeyboardAutoShown: Boolean
        get() = sharedPreferences.getBoolean(KEY_KEYBOARD_AUTO_SHOWN, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_KEYBOARD_AUTO_SHOWN, value)
                .apply()
        }

    /**
     * Last non-navigation-bar-inclusive IME bottom inset reported while the
     * keyboard was opening. Used to reserve Home's keyboard slot before the IME
     * reports its next animation target, avoiding one full-height pre-keyboard
     * frame on warm starts and carousel returns.
     */
    var keyboardReservationBottomPx: Int
        get() = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, 0)
        set(value) {
            sharedPreferences.edit()
                .putInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, value.coerceAtLeast(0))
                .apply()
        }

    /**
     * Controls whether Agenda participates in the carousel. Defaults to true
     * to preserve the existing three-page launcher for current users.
     */
    var isAgendaEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AGENDA_ENABLED, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_AGENDA_ENABLED, value)
                .apply()
        }

    /**
     * Settings → "Theme" mode. Defaults to [ThemeMode.System] so the launcher
     * follows the device's night-mode configuration; users can pin to
     * [ThemeMode.Light] or [ThemeMode.Dark] to override the system.
     */
    var themeMode: ThemeMode
        get() = sharedPreferences.getString(KEY_THEME_MODE, null)
            ?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            ?: ThemeMode.System
        set(value) {
            sharedPreferences.edit()
                .putString(KEY_THEME_MODE, value.name)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_ICON_COUNT = "dock_icon_count"
        const val KEY_APP_LIST_ICON_ONLY = "app_list_icon_only"
        const val KEY_APP_LIST_SORT_ORDER = "app_list_sort_order"
        const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
        const val KEY_NOTIFICATION_PULL_DOWN_BEHAVIOR = "notification_pull_down_behavior"
        const val KEY_KEYBOARD_AUTO_SHOWN = "keyboard_auto_shown"
        const val KEY_KEYBOARD_RESERVATION_BOTTOM_PX = "keyboard_reservation_bottom_px"
        const val KEY_AGENDA_ENABLED = "agenda_enabled"
        const val KEY_THEME_MODE = "theme_mode"
    }
}

private fun parseNotificationPullDownBehavior(name: String): NotificationPullDownBehavior? =
    when (name) {
        "Launcher" -> NotificationPullDownBehavior.BarBelow
        else -> runCatching { NotificationPullDownBehavior.valueOf(name) }.getOrNull()
    }

private fun android.content.SharedPreferences.deriveDockIconCountFromLegacySize(): Int {
    val legacySize = getInt(LEGACY_KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
    return dockSlotCountForIconSize(DEFAULT_DOCK_SCREEN_WIDTH_DP, legacySize)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
}

private const val LEGACY_KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
