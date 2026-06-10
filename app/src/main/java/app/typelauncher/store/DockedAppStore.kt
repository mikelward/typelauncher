package app.typelauncher

import android.content.Context
import java.util.LinkedHashSet

internal class DockedAppStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME,
) {
    private val sharedPreferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    // Guards every read and write of the in-memory dock state below. `dock`,
    // `undock`, and `move` each read the current `dockedIds` /
    // `dockPositions`, compute a new value, then write it back — a classic
    // read-modify-write. Without a lock, two of these running concurrently
    // (a user drag on the main thread racing a background reader, or any
    // future off-main-thread caller) can lose one update, and a reader can
    // observe `dockedIds` and `dockPositions` mid-mutation — half-applied
    // state where the two maps disagree, or a `ConcurrentModificationException`
    // thrown out of `toList()` / `joinToString` while another thread mutates
    // the `LinkedHashSet`. Holding `lock` across each whole operation makes the
    // mutations atomic and the snapshots returned by the getters internally
    // consistent. Contention is effectively nil (dock edits are rare and
    // user-driven), so the uncontended monitor acquisition adds no measurable
    // cost to the main thread.
    private val lock = Any()

    private var dockedIds = sharedPreferences.getString(KEY_DOCKED_APP_IDS, "").orEmpty()
        .split(DOCKED_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())
    private var dockPositions = sharedPreferences.getString(KEY_DOCKED_APP_POSITIONS, "").orEmpty()
        .parseDockPositions()

    val dockedAppIds: List<String>
        get() = synchronized(lock) { dockedIds.toList() }

    val dockedAppPositions: Map<String, DockPosition>
        get() = synchronized(lock) { dockPositions.toMap() }

    fun dockedAppIdsFor(sortOrder: AppListSortOrder, columnCount: Int): List<String> =
        synchronized(lock) {
            dockedAppIdsInGridRankOrder(dockedIds.toList(), dockPositions, columnCount, sortOrder)
        }

    fun contains(appId: String): Boolean = synchronized(lock) { appId in dockedIds }

    fun dock(appId: String, columnCount: Int = DEFAULT_DOCK_ICON_COUNT) = synchronized(lock) {
        if (appId in dockedIds) {
            return@synchronized
        }
        // Re-resolve the persisted coordinates into their compacted form before
        // picking the new slot. resolvedDockPositions collapses empty rows for
        // rendering, so an app deleted from the top row leaves the survivors
        // persisted at row >= 1 even though they render at row 0. Choosing the
        // next slot against the raw sparse map would hand the new app a
        // coordinate that collides with a survivor, displacing it on the next
        // resolve. Compacting first keeps the persisted map and the new slot in
        // the same coordinate space, matching what `move` already does.
        dockPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columnCount).toMutableMap()
        dockPositions[appId] = nextAvailableDockPosition(dockedIds.toList(), dockPositions, columnCount)
        dockedIds.add(appId)
        // The "+" add-button hint is a one-shot onboarding affordance set after
        // prefill (LauncherViewModel.setShowAddButtonHint). Any successful dock
        // — prefill's own seeds *or* a user-initiated `toggleDock` — clears it.
        // Prefill calls this before the ViewModel turns the flag on, so it
        // can't clear what was never set; the first user dock after prefill
        // is the only path that actually flips it off.
        save(clearShowAddButtonHint = true)
    }

    fun undock(appId: String) = synchronized(lock) {
        if (dockedIds.remove(appId)) {
            dockPositions.remove(appId)
            save()
        }
    }

    fun move(appId: String, row: Int, column: Int, columnCount: Int, sortOrder: AppListSortOrder) =
        synchronized(lock) {
            if (appId !in dockedIds) return@synchronized
            val columns = columnCount.coerceAtLeast(1)
            val target = DockPosition(row.coerceAtLeast(0), column.coerceIn(0, columns - 1))
            val currentPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columns)
            val previous = currentPositions[appId]
            val occupant = currentPositions.entries.firstOrNull { (id, position) ->
                id != appId && position == target
            }?.key
            // Rebase the persisted map onto the compacted view before mutating.
            // `previous`, `occupant`, and `target` are all coordinates in the
            // compacted space the user sees, while the persisted map can still
            // hold sparse rows left behind by `undock` (it only removes the
            // entry). Writing compacted coordinates into the sparse map mixes
            // the two spaces: a bystander still persisted at a stale row keeps
            // that row, and because the moved/swapped apps now occupy the rows
            // in between, the row-collapse pass in `resolvedDockPositions`
            // can no longer repair it — the bystander is stranded on its own
            // row and the corruption is saved. Compact-first matches `dock`.
            dockPositions = currentPositions.toMutableMap()
            dockPositions[appId] = target
            if (occupant != null && previous != null) {
                dockPositions[occupant] = previous
            }
            dockPositions = resolvedDockPositions(dockedIds.toList(), dockPositions, columns).toMutableMap()
            dockedIds = LinkedHashSet(dockedAppIdsInGridRankOrder(dockedIds.toList(), dockPositions, columns, sortOrder))
            save()
        }

    val hasBeenPrefilled: Boolean
        get() = sharedPreferences.getBoolean(KEY_DOCK_PREFILLED, false)

    fun markPrefilled() {
        sharedPreferences.edit().putBoolean(KEY_DOCK_PREFILLED, true).apply()
    }

    /**
     * One-shot onboarding flag for the dock's "+" add-button hint. Defaults to
     * `false`, so upgrade installs (and any state never reached via prefill)
     * never advertise the hint. Set true by `LauncherViewModel` right after a
     * fresh-install prefill that left an empty slot in the first row; cleared
     * inside [dock] the first time the user adds an app to this dock.
     */
    val shouldShowAddButtonHint: Boolean
        get() = sharedPreferences.getBoolean(KEY_SHOW_ADD_BUTTON_HINT, false)

    fun setShowAddButtonHint(show: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_SHOW_ADD_BUTTON_HINT, show).apply()
    }

    private fun save(clearShowAddButtonHint: Boolean = false) {
        val editor = sharedPreferences.edit()
            .putString(KEY_DOCKED_APP_IDS, dockedIds.joinToString(DOCKED_APP_ID_SEPARATOR))
            .putString(
                KEY_DOCKED_APP_POSITIONS,
                dockPositions.filterKeys { appId -> appId in dockedIds }.toPreferencesString(),
            )
        if (clearShowAddButtonHint) {
            editor.putBoolean(KEY_SHOW_ADD_BUTTON_HINT, false)
        }
        editor.apply()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "docked_apps"
        internal const val WORK_PREFERENCES_NAME = "work_docked_apps"
        private const val KEY_DOCKED_APP_IDS = "docked_app_ids"
        private const val KEY_DOCKED_APP_POSITIONS = "docked_app_positions"
        private const val KEY_DOCK_PREFILLED = "dock_prefilled"
        private const val KEY_SHOW_ADD_BUTTON_HINT = "show_add_button_hint"
        private const val DOCKED_APP_ID_SEPARATOR = "\n"
        private const val DOCK_POSITION_FIELD_SEPARATOR = "\t"
    }

    private fun String.parseDockPositions(): MutableMap<String, DockPosition> =
        lineSequence()
            .mapNotNull { line ->
                val fields = line.split(DOCK_POSITION_FIELD_SEPARATOR)
                if (fields.size != 3) return@mapNotNull null
                val row = fields[1].toIntOrNull() ?: return@mapNotNull null
                val column = fields[2].toIntOrNull() ?: return@mapNotNull null
                fields[0].takeIf { it.isNotBlank() }?.let { appId -> appId to DockPosition(row, column) }
            }
            .toMap()
            .toMutableMap()

    private fun Map<String, DockPosition>.toPreferencesString(): String =
        entries.joinToString(DOCKED_APP_ID_SEPARATOR) { (appId, position) ->
            listOf(appId, position.row.toString(), position.column.toString())
                .joinToString(DOCK_POSITION_FIELD_SEPARATOR)
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

    var isWorkDockEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_WORK_DOCK_ENABLED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_WORK_DOCK_ENABLED, value)
                .apply()
        }

    var isAppListIconOnly: Boolean
        get() = sharedPreferences.getBoolean(KEY_APP_LIST_ICON_ONLY, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_APP_LIST_ICON_ONLY, value)
                .apply()
        }

    /**
     * When true (the default), docked apps remain visible in the typed-search
     * app list in addition to the dock row. When false, the dock acts as a
     * deduplicating shortcut surface and docked apps are hidden from the main
     * list to free vertical space — the launcher's pre-toggle behavior.
     */
    var isShowDockedAppsInList: Boolean
        get() = sharedPreferences.getBoolean(KEY_SHOW_DOCKED_APPS_IN_LIST, true)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_SHOW_DOCKED_APPS_IN_LIST, value)
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

    /**
     * When true, the bug-report consent dialog is suppressed and Settings →
     * "Report bug" shares immediately. Set by the "Don't show this again"
     * checkbox on the consent dialog itself.
     */
    var isBugReportConsentSuppressed: Boolean
        get() = sharedPreferences.getBoolean(KEY_BUG_REPORT_CONSENT_SUPPRESSED, false)
        set(value) {
            sharedPreferences.edit()
                .putBoolean(KEY_BUG_REPORT_CONSENT_SUPPRESSED, value)
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "dock_settings"
        const val KEY_DOCK_ENABLED = "dock_enabled"
        const val KEY_DOCK_ICON_COUNT = "dock_icon_count"
        const val KEY_WORK_DOCK_ENABLED = "work_dock_enabled"
        const val KEY_APP_LIST_ICON_ONLY = "app_list_icon_only"
        const val KEY_SHOW_DOCKED_APPS_IN_LIST = "show_docked_apps_in_list"
        const val KEY_APP_LIST_SORT_ORDER = "app_list_sort_order"
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
        const val KEY_BUG_REPORT_CONSENT_SUPPRESSED = "bug_report_consent_suppressed"
    }
}

private fun android.content.SharedPreferences.deriveDockIconCountFromLegacySize(): Int {
    val legacySize = getInt(LEGACY_KEY_DOCK_ICON_SIZE_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
    return dockSlotCountForIconSize(DEFAULT_DOCK_SCREEN_WIDTH_DP, legacySize)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
}

private const val LEGACY_KEY_DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
