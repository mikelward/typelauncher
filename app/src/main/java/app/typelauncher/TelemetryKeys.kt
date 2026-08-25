package app.typelauncher

/**
 * Custom-key names attached to every Crashlytics report. Held as constants so
 * the test that guards the privacy floor can enumerate them, and so a rename
 * can't silently split one key into two in the Firebase console.
 */
internal object TelemetryKey {
    const val FOREGROUND = "launcher_foreground"
    const val LOCAL_BUILD = "local_build"
    const val DEFAULT_LAUNCHER = "default_launcher"
    const val DESTINATION = "destination"
    const val SETTINGS_OPEN = "settings_open"
    const val HOME_READY = "home_ready"
    const val APPS_LOADED = "apps_loaded"
    const val AGENDA_STATE = "agenda_state"
    const val PLAY_UPDATE = "play_update"
    const val APPS_DOCKED = "count_apps_docked"
    const val APPS_HIDDEN = "count_apps_hidden"
    const val APPS_RECENT = "count_apps_recent"
    const val DOCK_FOLDERS = "count_dock_folders"
    const val WIDGETS = "count_widgets"
    const val WIDGET_PAGES = "count_widget_pages"
    const val WIDGETS_STRANDED = "count_widgets_stranded"
    const val DOCK_ENABLED = "dock_enabled"
    const val WORK_DOCK_ENABLED = "work_dock_enabled"
    const val WORK_PROFILE = "work_profile"
    const val APP_LIST_LAYOUT = "app_list_layout"
    const val DOCK_LAYOUT = "dock_layout"
    const val DOCK_ICON_SIZE_DP = "dock_icon_size_dp"
    const val SORT_ORDER = "sort_order"
    const val AGENDA_ENABLED = "agenda_enabled"
    const val CONTACT_SEARCH = "contact_search"
    const val CALENDAR_SEARCH = "calendar_search"
    const val THEME_MODE = "theme_mode"
    const val ICON_SHAPE = "icon_shape"
    const val ICON_THEME = "icon_theme"
    const val WALLPAPER = "wallpaper"
    const val KEYBOARD_AUTO_SHOW = "keyboard_auto_show"
    const val KEYBOARD_RESERVATION = "keyboard_reservation"
}

/**
 * The structured half of a crash report: the launcher's settings, counts, and
 * coarse state at the moment it died. Crashlytics keeps these alongside the
 * stack trace and the breadcrumb log, so a crash is triageable — "which layout,
 * how many widgets, was the launcher even on screen" — **without the user
 * having to notice the post-crash prompt and share a bug report**. It is the
 * same information the bug report's "--- Settings ---" section carries, minus
 * everything that would name something of the user's.
 *
 * **Privacy floor** (`PRIVACY.md`, and the *Privacy* rule in `AGENTS.md`):
 * settings, enum choices, and counts only. Never a package name, an app or
 * contact display name, a search query, a widget's contents, or the dock's
 * membership — a count of docked apps is fine, the list of which apps is not.
 * `LauncherUiStateTelemetryKeysTest` asserts every emitted value is a boolean,
 * a number, or an enum constant, which is what keeps a future field addition
 * from quietly leaking a label.
 *
 * Pure and stateless so it is testable off-device; [LauncherTelemetry] is the
 * only caller that touches Firebase.
 */
// Deliberately absent: a count of the apps matching the current query. It moves
// on every keystroke, so it would defeat the `distinctUntilChanged` that keeps
// typing free and rewrite the whole key set per character on the launcher's
// hottest path — and "how many results did the query match at crash time" is
// close to worthless for triage anyway. Every key below is query-independent;
// keep it that way.
internal fun launcherTelemetryKeys(
    state: LauncherUiState,
    isLocalBuild: Boolean,
): Map<String, String> = linkedMapOf(
    TelemetryKey.LOCAL_BUILD to isLocalBuild.toString(),
    TelemetryKey.DEFAULT_LAUNCHER to state.isDefaultLauncher.toString(),
    // Sealed class, not an enum: the class name is the page, and `Widgets`
    // carries only a page index, so the name alone is the safe half.
    TelemetryKey.DESTINATION to (state.destination::class.simpleName ?: "unknown"),
    TelemetryKey.SETTINGS_OPEN to state.isSettingsOpen.toString(),
    TelemetryKey.HOME_READY to state.isHomeReady.toString(),
    TelemetryKey.APPS_LOADED to state.isFreshAppLoadComplete.toString(),
    // Class name only — the agenda states carry event titles, which never leave
    // the device.
    TelemetryKey.AGENDA_STATE to (state.agenda::class.simpleName ?: "unknown"),
    TelemetryKey.PLAY_UPDATE to (state.playUpdate::class.simpleName ?: "unknown"),
    TelemetryKey.APPS_DOCKED to state.dockedApps.size.toString(),
    TelemetryKey.APPS_HIDDEN to state.hiddenApps.size.toString(),
    TelemetryKey.APPS_RECENT to state.recentApps.size.toString(),
    TelemetryKey.DOCK_FOLDERS to state.dockFolders.size.toString(),
    TelemetryKey.WIDGETS to state.widgetIds.size.toString(),
    TelemetryKey.WIDGET_PAGES to state.widgetPages.size.toString(),
    TelemetryKey.WIDGETS_STRANDED to state.strandedWidgetIds.size.toString(),
    TelemetryKey.DOCK_ENABLED to state.isDockEnabled.toString(),
    TelemetryKey.WORK_DOCK_ENABLED to state.isWorkDockEnabled.toString(),
    TelemetryKey.WORK_PROFILE to state.isWorkProfileConfigured.toString(),
    TelemetryKey.APP_LIST_LAYOUT to state.appListLayout.name,
    TelemetryKey.DOCK_LAYOUT to state.dockLayout.name,
    TelemetryKey.DOCK_ICON_SIZE_DP to state.dockIconSizeDp.toString(),
    TelemetryKey.SORT_ORDER to state.appListSortOrder.name,
    TelemetryKey.AGENDA_ENABLED to state.isAgendaEnabled.toString(),
    TelemetryKey.CONTACT_SEARCH to state.isContactSearchEnabled.toString(),
    TelemetryKey.CALENDAR_SEARCH to state.isCalendarSearchEnabled.toString(),
    TelemetryKey.THEME_MODE to state.themeMode.name,
    TelemetryKey.ICON_SHAPE to state.iconShape.name,
    TelemetryKey.ICON_THEME to state.iconTheme.name,
    TelemetryKey.WALLPAPER to state.isWallpaperShown.toString(),
    TelemetryKey.KEYBOARD_AUTO_SHOW to state.isKeyboardAutoShown.toString(),
    // Height plus which signal produced it: the keyboard-reservation state
    // machine is the launcher's most layout-sensitive mechanism, and a jank or
    // sizing crash is unreadable without knowing which source was in force.
    TelemetryKey.KEYBOARD_RESERVATION to
        "${state.keyboardReservation.bottomPx}/${state.keyboardReservation.source.name}",
)
