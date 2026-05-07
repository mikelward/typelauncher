package app.typelauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

internal const val MIN_DOCK_APP_ICON_SIZE_DP = 40
internal const val DEFAULT_DOCK_APP_ICON_SIZE_DP = 56
internal const val MAX_DOCK_APP_ICON_SIZE_DP = 80
internal const val MIN_DOCK_ICON_COUNT = 1
internal const val DEFAULT_DOCK_ICON_COUNT = 4
internal const val MAX_DOCK_ICON_COUNT = 8
internal const val DEFAULT_DOCK_SCREEN_WIDTH_DP = 411
private const val DOCK_HORIZONTAL_PADDING_DP = 64
private const val DOCK_ITEM_HORIZONTAL_PADDING_DP = 8
private const val DOCK_ITEM_SPACING_DP = 8

internal enum class AppListSortOrder {
    Usage,
    UsageReversed,
    Alphabetical,
    AlphabeticalReversed,
}

/**
 * The reversed variants render the apps list with `reverseLayout = true`, which
 * draws item 0 at the visual bottom of the card and grows the list upwards.
 * The persisted launch-count / alphabetical ordering is the same as the forward
 * variant — only the visual presentation is flipped — so the "best" entry
 * (most-used or alphabetically first) sits closest to the keyboard / typing
 * area, and the list is naturally scrolled to that bottom edge on first paint.
 */
internal val AppListSortOrder.isReversed: Boolean
    get() = this == AppListSortOrder.UsageReversed ||
        this == AppListSortOrder.AlphabeticalReversed

internal val AppListSortOrder.dataOrdering: AppListSortOrder
    get() = when (this) {
        AppListSortOrder.Usage, AppListSortOrder.UsageReversed -> AppListSortOrder.Usage
        AppListSortOrder.Alphabetical, AppListSortOrder.AlphabeticalReversed -> AppListSortOrder.Alphabetical
    }

internal enum class NotificationPullDownBehavior {
    None,
    System,
    BarBelow,
    BarAbove,
}

internal val NotificationPullDownBehavior.showsLauncherNotificationBar: Boolean
    get() = this == NotificationPullDownBehavior.BarBelow || this == NotificationPullDownBehavior.BarAbove

internal enum class ThemeMode {
    System,
    Light,
    Dark,
}

internal data class LauncherUiState(
    val screen: LauncherScreen = LauncherScreen.Home,
    val query: String = "",
    val filteredApps: List<InstalledApp> = emptyList(),
    val dockedApps: List<InstalledApp> = emptyList(),
    // Apps the user has hidden via the long-press menu. They are excluded from
    // every launcher surface (search results, dock, recents) and only resurface
    // in the Settings "Manage hidden apps" dialog so the user can unhide them.
    val hiddenApps: List<InstalledApp> = emptyList(),
    // Drag-up-from-the-dock "recents" list. Most-recently-launched first. Only
    // includes apps the user launched from Type Launcher — third-party launchers
    // can't read the system task switcher, so this is a best-effort substitute.
    val recentApps: List<InstalledApp> = emptyList(),
    val isRecentsOpen: Boolean = false,
    // Apps with at least one active user-visible notification, sourced from the
    // bound NotificationListenerService. Empty unless the user has granted
    // notification access in Android settings. Rendered in the notification bar,
    // when [isNotificationBarOpen] is true.
    val notifyingApps: List<InstalledApp> = emptyList(),
    // True once the user has pulled down on the home screen to reveal the
    // notification bar. A second pull-down expands the system shade. Reset on
    // app launch / screen change / settings open.
    val isNotificationBarOpen: Boolean = false,
    // Whether the user has granted Type Launcher notification listener access.
    // Refreshed in `refreshPermissionDrivenUi` so flipping the toggle in
    // Android settings is picked up on the next resume.
    val hasNotificationAccess: Boolean = false,
    // Settings → "Pull down" action. BarBelow / BarAbove insert the in-app
    // notification bar as a first stage; System opens Android's shade; None
    // disables pull-down handling.
    val notificationPullDownBehavior: NotificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
    // When true, the recents card is permanently visible above the keyboard
    // (orthogonal to `isDockEnabled`), independent of the drag-up gesture. The
    // visible-recents predicate is `isRecentsAlwaysShown || isRecentsOpen`, so
    // the gesture still works when the setting is off.
    val isRecentsAlwaysShown: Boolean = false,
    // When true (default), apps that appear in the persistently-visible recents
    // card are excluded from the main app list — same convention as docked
    // apps when the dock is enabled, so the same icon never renders in two
    // places at once. Only takes effect while `isRecentsAlwaysShown` is on.
    val isHideRecentsFromAppList: Boolean = true,
    val widgetIds: List<Int> = emptyList(),
    val widgetHeights: Map<Int, Int> = emptyMap(),
    val availableWidgets: List<WidgetProvider> = emptyList(),
    val isAddingWidget: Boolean = false,
    val isLoadingAvailableWidgets: Boolean = false,
    val agenda: AgendaUiState = AgendaUiState.PermissionRequired,
    val isSettingsOpen: Boolean = false,
    val isDockEnabled: Boolean = true,
    val isAppListIconOnly: Boolean = false,
    val dockIconCount: Int = DEFAULT_DOCK_ICON_COUNT,
    val appListSortOrder: AppListSortOrder = AppListSortOrder.Usage,
    // When true (default), the search field auto-focuses on launch and
    // `SearchCard` calls `keyboard.show()` so the user can start typing
    // immediately. When false, the search field renders unfocused and
    // `MainActivity` overrides the manifest's `stateAlwaysVisible` softInputMode
    // so the keyboard stays down until the user taps the field.
    val isKeyboardAutoShown: Boolean = true,
    // User-selected appearance mode. `System` (default) follows the device's
    // night-mode setting; `Light` and `Dark` force the corresponding scheme
    // regardless of the system. Applied by `TypeLauncherTheme`.
    val themeMode: ThemeMode = ThemeMode.System,
    val isLoadingApps: Boolean = false,
    // Distinct from `isLoadingApps`, which only gates the loading spinner: on a
    // warm start `isLoadingApps` is `false` from process start because cached
    // metadata is rendered immediately, but the fresh `LauncherApps` query is
    // still running on `Dispatchers.IO` in the background. Other startup IO
    // (the deferred agenda load) waits on this flag instead of `isLoadingApps`
    // so it doesn't race the fresh app load.
    val isFreshAppLoadComplete: Boolean = false,
    // Latched true the first time the UI signals "home ready" via
    // `LauncherViewModel.onHomeReady`. Gates cold-start IO that the agenda load
    // and `AppWidgetHost.startListening` would otherwise contend with.
    val isHomeReady: Boolean = false,
    val isDefaultLauncher: Boolean = false,
    val playUpdate: PlayUpdateState = PlayUpdateState.NotAvailable,
)

internal fun dockSlotCountForIconSize(screenWidthDp: Int, iconSizeDp: Int): Int {
    val availableWidthDp = (screenWidthDp - DOCK_HORIZONTAL_PADDING_DP).coerceAtLeast(0)
    return ((availableWidthDp + DOCK_ITEM_SPACING_DP) /
        (iconSizeDp + DOCK_ITEM_HORIZONTAL_PADDING_DP + DOCK_ITEM_SPACING_DP)).coerceAtLeast(1)
}

internal fun dockIconSizeForSlotCount(screenWidthDp: Int, slotCount: Int): Int {
    val availableWidthDp = (screenWidthDp - DOCK_HORIZONTAL_PADDING_DP).coerceAtLeast(0)
    val clampedSlotCount = slotCount.coerceAtLeast(1)
    val itemChromeWidthDp = DOCK_ITEM_SPACING_DP * (clampedSlotCount - 1) +
        DOCK_ITEM_HORIZONTAL_PADDING_DP * clampedSlotCount
    return ((availableWidthDp - itemChromeWidthDp) / clampedSlotCount)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
}

internal fun dockSlotCountRange(screenWidthDp: Int): IntRange {
    val minSlotCount = dockSlotCountForIconSize(screenWidthDp, MAX_DOCK_APP_ICON_SIZE_DP)
    val maxSlotCount = dockSlotCountForIconSize(screenWidthDp, MIN_DOCK_APP_ICON_SIZE_DP)
    return minSlotCount..maxSlotCount
}

internal data class WidgetProvider(
    val appName: String,
    val label: String,
    val componentName: ComponentName,
    val profile: UserHandle,
    val icon: Drawable?,
    val appIcon: Drawable?,
    val minWidth: Int,
    val minHeight: Int,
    val targetCellWidth: Int,
    val targetCellHeight: Int,
    val previewImage: Drawable?,
    // True when the provider lives in a managed (work) profile. The picker
    // groups providers by `(appName, isWorkProvider)` so the personal and
    // work copies of the same app render as separate sections.
    val isWorkProvider: Boolean = false,
) {
    val id: String = "${profile.hashCode()}:${componentName.flattenToShortString()}"
}
