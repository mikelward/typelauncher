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
     * keyboard was opening, paired with the configuration it was measured
     * under and the source that produced it. Used to reserve Home's
     * keyboard slot before the IME reports its next animation target,
     * avoiding one full-height pre-keyboard frame on warm starts and
     * carousel returns. The configuration fingerprint makes the cache
     * shrink-safe: on rotation, density change, navigation-mode switch,
     * or any other property that changes the IME's pixel height, the
     * persisted value is ignored on the next cold start so a stale
     * too-large reservation cannot survive the configuration change.
     *
     * Legacy installs that wrote only [KEY_KEYBOARD_RESERVATION_BOTTOM_PX]
     * (without the fingerprint keys) are loaded with a null fingerprint
     * and treated as a wildcard until the next IME observation overwrites
     * them, so upgrades still benefit from the cached value.
     */
    var keyboardReservation: KeyboardReservation
        get() {
            val bottomPx = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, 0)
                .coerceAtLeast(0)
            val hasConfig = sharedPreferences.contains(KEY_KEYBOARD_RESERVATION_ORIENTATION)
            val configFingerprint = if (hasConfig) {
                KeyboardReservationConfig(
                    orientation = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_ORIENTATION, 0),
                    screenWidthDp = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP, 0),
                    screenHeightDp = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP, 0),
                    densityDpi = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_DENSITY_DPI, 0),
                    navBottomPx = sharedPreferences.getInt(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX, 0),
                )
            } else {
                null
            }
            val source = sharedPreferences.getString(KEY_KEYBOARD_RESERVATION_SOURCE, null)
                ?.let { name -> runCatching { KeyboardReservationSource.valueOf(name) }.getOrNull() }
                ?: KeyboardReservationSource.AnimationTarget
            return KeyboardReservation(
                bottomPx = bottomPx,
                configFingerprint = configFingerprint,
                source = source,
            )
        }
        set(value) {
            val editor = sharedPreferences.edit()
                .putInt(KEY_KEYBOARD_RESERVATION_BOTTOM_PX, value.bottomPx.coerceAtLeast(0))
                .putString(KEY_KEYBOARD_RESERVATION_SOURCE, value.source.name)
            val fingerprint = value.configFingerprint
            if (fingerprint == null) {
                editor
                    .remove(KEY_KEYBOARD_RESERVATION_ORIENTATION)
                    .remove(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP)
                    .remove(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP)
                    .remove(KEY_KEYBOARD_RESERVATION_DENSITY_DPI)
                    .remove(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX)
            } else {
                editor
                    .putInt(KEY_KEYBOARD_RESERVATION_ORIENTATION, fingerprint.orientation)
                    .putInt(KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP, fingerprint.screenWidthDp)
                    .putInt(KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP, fingerprint.screenHeightDp)
                    .putInt(KEY_KEYBOARD_RESERVATION_DENSITY_DPI, fingerprint.densityDpi)
                    .putInt(KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX, fingerprint.navBottomPx)
            }
            editor.apply()
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
        const val KEY_KEYBOARD_RESERVATION_ORIENTATION = "keyboard_reservation_orientation"
        const val KEY_KEYBOARD_RESERVATION_SCREEN_WIDTH_DP = "keyboard_reservation_screen_width_dp"
        const val KEY_KEYBOARD_RESERVATION_SCREEN_HEIGHT_DP = "keyboard_reservation_screen_height_dp"
        const val KEY_KEYBOARD_RESERVATION_DENSITY_DPI = "keyboard_reservation_density_dpi"
        const val KEY_KEYBOARD_RESERVATION_NAV_BOTTOM_PX = "keyboard_reservation_nav_bottom_px"
        const val KEY_KEYBOARD_RESERVATION_SOURCE = "keyboard_reservation_source"
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
