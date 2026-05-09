package app.typelauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.runtime.Immutable

internal const val MIN_DOCK_APP_ICON_SIZE_DP = 40
internal const val DEFAULT_DOCK_APP_ICON_SIZE_DP = 56
internal const val MAX_DOCK_APP_ICON_SIZE_DP = 80
internal const val MIN_DOCK_ICON_COUNT = 1
internal const val DEFAULT_DOCK_ICON_COUNT = 4
internal const val MAX_DOCK_ICON_COUNT = 8
internal const val DEFAULT_DOCK_SCREEN_WIDTH_DP = 411
private const val DOCK_HORIZONTAL_PADDING_DP = 64
private const val DOCK_ITEM_HORIZONTAL_PADDING_DP = 8
internal const val DOCK_ITEM_SPACING_DP = 8

// Floor on the apps list while the dock is allowed to grow vertically. The
// home screen's custom layout reserves at least this many app-row heights
// (each ≈ dockIconSizeDp + 2 * DOCK_ITEM_SPACING_DP) above the dock so a
// large docked-app collection cannot squeeze the apps list to nothing.
internal const val APP_LIST_MIN_VISIBLE_ROWS = 2

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

@Immutable
internal data class DockPosition(
    val row: Int,
    val column: Int,
)

internal fun resolvedDockPositions(
    dockedAppIds: List<String>,
    persistedPositions: Map<String, DockPosition>,
    columnCount: Int,
): Map<String, DockPosition> {
    val columns = columnCount.coerceAtLeast(1)
    val result = linkedMapOf<String, DockPosition>()
    val occupied = mutableSetOf<DockPosition>()
    fun firstOpenPosition(): DockPosition {
        var row = 0
        while (true) {
            for (column in 0 until columns) {
                val position = DockPosition(row, column)
                if (position !in occupied) return position
            }
            row += 1
        }
    }
    dockedAppIds.distinct().forEach { appId ->
        val persisted = persistedPositions[appId]
        if (
            persisted != null &&
            persisted.row >= 0 &&
            persisted.column in 0 until columns &&
            occupied.add(persisted)
        ) {
            result[appId] = persisted
        } else {
            val fallback = firstOpenPosition()
            occupied.add(fallback)
            result[appId] = fallback
        }
    }
    return result
}

internal fun nextAvailableDockPosition(
    dockedAppIds: List<String>,
    persistedPositions: Map<String, DockPosition>,
    columnCount: Int,
): DockPosition {
    val occupied = resolvedDockPositions(dockedAppIds, persistedPositions, columnCount)
        .values
        .toSet()
    val columns = columnCount.coerceAtLeast(1)
    var row = 0
    while (true) {
        for (column in 0 until columns) {
            val position = DockPosition(row, column)
            if (position !in occupied) {
                return position
            }
        }
        row += 1
    }
}

internal fun dockedAppIdsInGridRankOrder(
    dockedAppIds: List<String>,
    persistedPositions: Map<String, DockPosition>,
    columnCount: Int,
    sortOrder: AppListSortOrder,
): List<String> {
    val uniqueIds = dockedAppIds.distinct()
    val indexById = uniqueIds.withIndex().associate { (index, id) -> id to index }
    val positions = resolvedDockPositions(uniqueIds, persistedPositions, columnCount)
    return uniqueIds.sortedWith { left, right ->
        val leftPosition = positions.getValue(left)
        val rightPosition = positions.getValue(right)
        val rowComparison = if (sortOrder.isReversed) {
            rightPosition.row.compareTo(leftPosition.row)
        } else {
            leftPosition.row.compareTo(rightPosition.row)
        }
        if (rowComparison != 0) return@sortedWith rowComparison
        val columnComparison = if (sortOrder.isReversed) {
            rightPosition.column.compareTo(leftPosition.column)
        } else {
            leftPosition.column.compareTo(rightPosition.column)
        }
        if (columnComparison != 0) return@sortedWith columnComparison
        (indexById[left] ?: 0).compareTo(indexById[right] ?: 0)
    }
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

/**
 * Distinguishes a keyboard reservation that has been confirmed by a real
 * visible IME from one that only ever came in via `WindowInsets.imeAnimationTarget`.
 *
 * Animation-target-only readings can briefly land above the keyboard's settled
 * height during multi-stage opens; they are good enough to seed Home's first
 * frame with keyboard-height geometry, but not authoritative enough to shrink
 * an already-cached entry value. A [VisibleIme] reading has been observed
 * with `WindowInsets.isImeVisible == true`, so it represents an actually
 * laid-out keyboard.
 */
internal enum class KeyboardReservationSource {
    AnimationTarget,
    VisibleIme,
}

/**
 * Configuration context a [KeyboardReservation] was measured under.
 *
 * The persisted reservation is only safe to apply when these properties
 * match the current configuration: orientation, screen size, and density
 * change the keyboard's pixel height, and the navigation-bar inset is part
 * of the reservation arithmetic itself (Home subtracts it before using the
 * cached value). When any of them differ — rotation, fold/unfold,
 * gesture/3-button nav switch, density change — the reservation is treated
 * as 0 so a stale, too-large value cannot survive the configuration change.
 *
 * `null` represents a legacy persisted reservation written before the
 * configuration fingerprint was recorded; it is treated as a wildcard so
 * upgrade installs still benefit from the cached value on the first cold
 * start, and is replaced on the next IME observation.
 */
@Immutable
internal data class KeyboardReservationConfig(
    val orientation: Int,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val densityDpi: Int,
    val navBottomPx: Int,
)

/**
 * Persisted keyboard reservation. See [LauncherUiState.keyboardReservation].
 */
@Immutable
internal data class KeyboardReservation(
    val bottomPx: Int = 0,
    val configFingerprint: KeyboardReservationConfig? = null,
    val source: KeyboardReservationSource = KeyboardReservationSource.AnimationTarget,
) {
    /**
     * Returns true when this reservation is safe to apply under [current].
     *
     * A null persisted [configFingerprint] is treated as a wildcard so
     * upgrade installs aren't penalised on the first cold start after
     * adopting the fingerprint. Once the next IME observation lands a
     * non-null fingerprint, subsequent matches are strict.
     */
    fun appliesUnder(current: KeyboardReservationConfig): Boolean {
        val fingerprint = configFingerprint ?: return true
        return fingerprint == current
    }
}

// Compose can't infer stability through the transitive Drawable / Intent /
// UserHandle references carried by `WidgetProvider` and `InstalledApp`,
// so without this annotation `HomeScreen(state = …)` (and every child
// composable that takes a `LauncherUiState` parameter) is treated as
// unstable and never skipped. We only ever construct new instances via
// `state.copy(...)`, so the immutability contract holds.
@Immutable
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
    // Legacy persisted pull-down action. The setting UI has been removed and
    // runtime Home gestures always use the launcher notification bar first.
    val notificationPullDownBehavior: NotificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
    val widgetIds: List<Int> = emptyList(),
    val widgetPages: List<List<Int>> = listOf(emptyList()),
    val currentWidgetPage: Int = 0,
    val widgetHeights: Map<Int, Int> = emptyMap(),
    val availableWidgets: List<WidgetProvider> = emptyList(),
    val isAddingWidget: Boolean = false,
    val isLoadingAvailableWidgets: Boolean = false,
    val agenda: AgendaUiState = AgendaUiState.PermissionRequired,
    val isSettingsOpen: Boolean = false,
    val isDockEnabled: Boolean = true,
    val isAppListIconOnly: Boolean = false,
    // When true (the default), docked apps stay visible in the typed-search
    // app list in addition to the dock row. When false, the dock dedupes
    // itself out of the main list to free vertical space — the launcher's
    // pre-toggle behavior.
    val isShowDockedAppsInList: Boolean = true,
    val dockIconCount: Int = DEFAULT_DOCK_ICON_COUNT,
    val dockPositions: Map<String, DockPosition> = emptyMap(),
    val appListSortOrder: AppListSortOrder = AppListSortOrder.Usage,
    // When true (default), the search field auto-focuses on launch and
    // `SearchCard` calls `keyboard.show()` so the user can start typing
    // immediately. When false, the search field renders unfocused and
    // `MainActivity` applies `stateAlwaysHidden` so the keyboard stays down
    // until the user taps the field.
    val isKeyboardAutoShown: Boolean = true,
    // Last keyboard bottom inset observed while the IME was opening, paired
    // with the configuration it was measured under and the source that
    // produced it. Home uses [KeyboardReservation.bottomPx] as a pre-show
    // reservation so the first frame can use keyboard-height geometry before
    // Android reports the next IME animation target. The config fingerprint
    // makes the reservation shrink-safe across rotations / nav-mode / IME
    // changes — Home only seeds from the cached value when the persisted
    // configuration matches the current one. The source distinguishes a
    // height confirmed by a real visible IME from one that only ever came in
    // as an animation target, so within-entry shrinks are gated on a
    // visible-IME confirmation.
    val keyboardReservation: KeyboardReservation = KeyboardReservation(),
    // Settings → "Show agenda". When false, Agenda is removed from the
    // horizontal carousel and calendar loading is deferred until re-enabled.
    val isAgendaEnabled: Boolean = true,
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

// `Drawable` and `ComponentName` are unstable to Compose; the picker passes
// `WidgetProvider` instances to composables and we never mutate the wrapped
// drawables after the provider list is built, so the data class is safe to
// treat as immutable.
@Immutable
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

internal data class WidgetAddRequest(
    val pageIndex: Int,
    val isCurrentPageScrollable: Boolean,
)
