package app.typelauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle
import androidx.compose.runtime.Immutable

// Bounds on the derived icon size, which in turn set the "Icons per row"
// slider's range per screen width (see `dockSlotCountRange`). The floor is the
// densest the user can pack a row; 32 dp opens up a 7-per-row stop on a
// standard 411 dp phone (the typical Pixel width — see
// DEFAULT_DOCK_SCREEN_WIDTH_DP), where that stop renders at an unclamped 34 dp
// icon for a ~42 dp tap cell (icon + the 8 dp item padding). That is below
// Material's 48 dp target — the trade for the extra column — but it is strictly
// opt-in: a user only reaches it by dragging the slider to its densest stop. A
// 36 dp floor only reached 7 per row at ~420 dp and up, so the standard Pixel
// topped out at 6. The ceiling caps the largest icon; 72 dp drops the sparsest
// 3-per-row stop on a standard 411 dp phone (where its icons clamped against
// the cap anyway) so the slider runs a clean 4..7 there, with the largest stop
// a row of 4 at 72 dp.
//
// DEFAULT_DOCK_APP_ICON_SIZE_DP is the target icon size a fresh install starts
// at when no dock icon size is persisted yet (see DockSettingsStore.dockIconSizeDp):
// 43 dp maps to 6 icons per row on a 411 dp phone, the default density.
internal const val MIN_DOCK_APP_ICON_SIZE_DP = 32
internal const val DEFAULT_DOCK_APP_ICON_SIZE_DP = 43
internal const val MAX_DOCK_APP_ICON_SIZE_DP = 72
internal const val MIN_DOCK_ICON_COUNT = 1
internal const val DEFAULT_DOCK_ICON_COUNT = 6
internal const val MAX_DOCK_ICON_COUNT = 8
internal const val DEFAULT_DOCK_SCREEN_WIDTH_DP = 411
private const val DOCK_HORIZONTAL_PADDING_DP = 64
private const val DOCK_ITEM_HORIZONTAL_PADDING_DP = 8
internal const val DOCK_ITEM_SPACING_DP = 8

// Per-slot tap-target padding added around every dock icon inside its row
// (`DockedAppButton`, `EmptyDockSlot`, `DockAddButton` each render a
// `dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP` tall box). Exposed so
// callers that need to size a card around an integer number of dock rows
// can derive the chrome height from the same constant the buttons use.
internal const val DOCK_ITEM_VERTICAL_PADDING_DP = 8

// Shared gap, in dp, between adjacent home-screen cards: search↔apps,
// apps↔personal-dock, and personal-dock↔work-dock. Driving every card-to-card
// gap from the same constant keeps the dock-to-app-list margin visually
// identical to the personal-to-work dock margin regardless of which dock
// cards are visible.
internal const val HOME_CARD_SPACING_DP = 8

// Floor on the apps list while the dock is allowed to grow vertically. The
// home screen's custom layout reserves at least this many app-row heights
// (each ≈ dockIconSizeDp + 2 * DOCK_ITEM_SPACING_DP) above the dock so a
// large docked-app collection cannot squeeze the apps list to nothing.
internal const val APP_LIST_MIN_VISIBLE_ROWS = 2

// Hard cap on the number of icon rows the work dock card renders before its
// inner `verticalScroll` kicks in. The work card's height is derived from
// `dockRowCount(workApps, ...).coerceAtMost(MAX_WORK_DOCK_ROWS)`, so a
// dockIconCount-sized work app collection fits in one row, anything larger
// expands to a second row, and a much larger collection scrolls inside the
// two-row card. Two rows is the upper bound that keeps the personal dock
// from feeling crowded on a typical phone viewport.
internal const val MAX_WORK_DOCK_ROWS = 2

// Below this screen height (in dp) the work dock is held to a single row
// regardless of how many work apps the user has docked. On very short
// viewports — old compact phones, fold/unfold transitional sizes — a
// two-row work dock would eat into the personal dock's slot and starve
// its icons (the symmetric flip of the heightIn cap from before). 600dp
// is large enough to comfortably fit a two-row work dock plus a one-row
// personal dock plus the apps-list floor; every supported Android phone
// in normal portrait orientation is taller than that, and the few that
// aren't still get the one-row work dock that previous versions shipped.
internal const val SMALL_SCREEN_TWO_ROW_WORK_DOCK_THRESHOLD_DP = 600

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

/**
 * Number of rows the dock card needs to render the given [dockedAppIds] with
 * the given [persistedPositions] at the given [columnCount]. Mirrors the
 * `(maxOccupiedRow + 1).coerceAtLeast(1)` calculation inside `DockCard`, so
 * callers that need to size a layout slot around the dock (e.g. the
 * work-dock height cap on `HomeScreen`) can ask the same question without
 * recomputing the resolved-position table themselves. Returns at least 1
 * even when the dock is empty so the card's chrome still has a row to
 * surround.
 */
internal fun dockRowCount(
    dockedAppIds: List<String>,
    persistedPositions: Map<String, DockPosition>,
    columnCount: Int,
): Int {
    val resolved = resolvedDockPositions(dockedAppIds, persistedPositions, columnCount)
    val maxRow = resolved.values.maxOfOrNull { position -> position.row } ?: 0
    return (maxRow + 1).coerceAtLeast(1)
}

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
    // Collapse fully-empty rows so the occupied rows are contiguous from row 0.
    // Removing an app can strand the survivors at row >= 1 (e.g. clearing the
    // top row leaves everything below it), and the dock renders maxRow + 1 rows
    // — so without this remap a blank leading or interior row stays on screen
    // with no UI to dismiss it. Column gaps within a row are preserved.
    val rowRemap = result.values
        .map { position -> position.row }
        .distinct()
        .sorted()
        .withIndex()
        .associate { (compactRow, originalRow) -> originalRow to compactRow }
    return result.mapValues { (_, position) ->
        DockPosition(rowRemap.getValue(position.row), position.column)
    }
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
        // A reversed sort renders the app list bottom-up (`reverseLayout`), so
        // the highest-rank app (index 0) lands on the bottom row. The dock's
        // bottom row is its row closest to the keyboard, so rank the bottom dock
        // row first under a reversed sort and the top row first otherwise.
        val rowComparison = if (sortOrder.isReversed) {
            rightPosition.row.compareTo(leftPosition.row)
        } else {
            leftPosition.row.compareTo(rightPosition.row)
        }
        if (rowComparison != 0) return@sortedWith rowComparison
        // Always rank left-to-right within a row, in both sort directions. Under
        // a reversed sort `reverseLayout` flips only the vertical axis, leaving
        // index 0 at the bottom-*left*, so ranking the leftmost dock column first
        // puts the dock's bottom-left icon in the app list's bottom-left corner
        // when the dock is hidden — the position the user already has muscle
        // memory for.
        val columnComparison = leftPosition.column.compareTo(rightPosition.column)
        if (columnComparison != 0) return@sortedWith columnComparison
        (indexById[left] ?: 0).compareTo(indexById[right] ?: 0)
    }
}

internal enum class ThemeMode {
    System,
    Light,
    Dark,
}

/**
 * Shape the launcher clips every app icon to. `System` (default) follows the
 * device's adaptive-icon mask (a circle on Pixel, a squircle on Samsung, etc.),
 * `Circle` and `Squircle` force that shape regardless of the device. Resolved
 * to a Compose `Shape` by `IconShape.toComposeShape`.
 */
internal enum class IconShape {
    System,
    Circle,
    Squircle,
}

/**
 * How the launcher renders every app icon's tile. `Default` keeps each app's
 * own icon art; `Monochrome` re-renders icons from their app's monochrome
 * themed-icon glyph in theme colors (API 33+) — apps without a monochrome
 * layer keep their normal icon. Applied at rasterization time by
 * `AppIconLoader` / `IconNormalizer`, not at composition time.
 */
internal enum class IconTheme {
    Default,
    Monochrome,
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

/**
 * Decides what (if anything) to persist as the cached [KeyboardReservation]
 * given a keyboard height the visible IME has actually settled at.
 *
 * The caller feeds this the *visible* IME bottom inset (`WindowInsets.ime`)
 * after a debounce, never the animation target: a multi-stage open — the
 * suggestion strip animating in then collapsing — makes the animation target
 * momentarily aim above the height the keyboard ultimately rests at, and the
 * visible inset only passes through that peak for a frame or two. Debouncing
 * the visible inset means [settledImeBottomPx] is always a height the keyboard
 * held still at, so the transient peak can never latch into the reservation
 * and float the dock above the keyboard.
 *
 * Returns the reservation to persist, or `null` to leave the current value
 * untouched. Both growth and shrink are emitted here; the caller gates them on
 * different debounce windows (shrinks wait longer) because growth is far more
 * disruptive than a one-off missed shrink. Every emitted reservation is
 * [KeyboardReservationSource.VisibleIme]: it has been ground-truthed against a
 * laid-out keyboard, so it is authoritative enough to shrink a cached value.
 */
internal fun resolveKeyboardReservation(
    settledImeBottomPx: Int,
    config: KeyboardReservationConfig,
    current: KeyboardReservation,
): KeyboardReservation? {
    if (settledImeBottomPx <= config.navBottomPx) return null
    val candidate = KeyboardReservation(
        bottomPx = settledImeBottomPx,
        configFingerprint = config,
        source = KeyboardReservationSource.VisibleIme,
    )
    // A configuration change always overwrites so a stale fingerprint cannot
    // survive; otherwise a different settled height (taller or shorter) is
    // authoritative, and an equal height only rewrites to upgrade a previously
    // animation-target-only reservation to a ground-truthed one.
    if (current.configFingerprint != config) return candidate
    if (settledImeBottomPx != current.bottomPx) return candidate
    if (current.source == KeyboardReservationSource.AnimationTarget) return candidate
    return null
}

/**
 * True when the soft keyboard is on screen *or* animating into view, so the
 * secondary bars (recents) that live in the reserved keyboard tray must not
 * render.
 *
 * `WindowInsets.isImeVisible` on its own is not enough to gate the tray:
 * while the keyboard is growing, its visibility flag can momentarily still
 * read `false` — most noticeably under system gesture navigation, where the
 * insets-dispatch order leaves a frame or two in which `imeVisible == false`
 * but the keyboard is already animating up. The tray would then render for
 * those frames and vanish once the flag caught up, flickering the bars in as
 * the launcher opens. `WindowInsets.imeAnimationTarget` jumps to the
 * keyboard's full height on the first frame of the show animation, so it
 * leads the visibility flag and closes that gap.
 *
 * The animation target is compared against the navigation-bar inset rather
 * than zero because a hidden keyboard's target bottom collapses to (at most)
 * the nav bar; only a target taller than the nav bar means a real keyboard is
 * coming. On the way *out* the target drops back below the nav bar on the
 * first frame of the hide animation, so the tray is free to fill the space
 * the keyboard is vacating.
 */
internal fun isKeyboardShowingOrAnimatingIn(
    imeVisible: Boolean,
    imeTargetBottomPx: Int,
    navBottomPx: Int,
): Boolean = imeVisible || imeTargetBottomPx > navBottomPx

// Compose can't infer stability through the transitive Drawable / Intent /
// UserHandle references carried by `WidgetProvider` and `InstalledApp`,
// so without this annotation `HomeScreen(state = …)` (and every child
// composable that takes a `LauncherUiState` parameter) is treated as
// unstable and never skipped. We only ever construct new instances via
// `state.copy(...)`, so the immutability contract holds.
@Immutable
internal data class LauncherUiState(
    val destination: LauncherDestination = LauncherDestination.Home,
    // Widget-page index to restore when the user returns to Widgets via
    // `showWidgets()` with no argument. Updated whenever destination becomes
    // Widgets(N) and clamped against `widgetPages` when the page list
    // changes; never read by carousel-sync logic, only by the restore path.
    val lastWidgetPage: Int = 0,
    val query: String = "",
    val filteredApps: List<InstalledApp> = emptyList(),
    val dockedApps: List<InstalledApp> = emptyList(),
    // Apps the user has hidden via the long-press menu. They are excluded from
    // every launcher surface (search results, dock, recents) and only resurface
    // in the Settings "Manage hidden apps" dialog so the user can unhide them.
    val hiddenApps: List<InstalledApp> = emptyList(),
    // Drag-up-from-the-dock "recents" list, in display order: oldest first,
    // most recently launched last (filterRecent reverses the stored
    // newest-first ids so the newest entry renders at the row's pinned end).
    // Only includes apps the user launched from Type Launcher — third-party
    // launchers can't read the system task switcher, so this is a best-effort
    // substitute.
    val recentApps: List<InstalledApp> = emptyList(),
    // True once the user has pulled up on the home screen to reveal the recents
    // bar at the bottom (where the dock was; everything else shifts up).
    // A pull-down closes it; a second pull-up re-shows the search keyboard.
    // Reset on app launch / screen change / settings open / resume to Home.
    val isRecentsOpen: Boolean = false,
    val widgetIds: List<Int> = emptyList(),
    val widgetPages: List<List<Int>> = listOf(emptyList()),
    val widgetHeights: Map<Int, Int> = emptyMap(),
    val availableWidgets: List<WidgetProvider> = emptyList(),
    val isAddingWidget: Boolean = false,
    val isLoadingAvailableWidgets: Boolean = false,
    val agenda: AgendaUiState = AgendaUiState.PermissionRequired,
    val isSettingsOpen: Boolean = false,
    val isDockEnabled: Boolean = true,
    val isAppListIconOnly: Boolean = false,
    // See `IconTheme`: `Monochrome` renders icons from their app's monochrome
    // themed-icon glyph in theme colors (API 33+); apps without a monochrome
    // layer keep their normal icon. Applied at rasterization time by
    // `AppIconLoader` / `IconNormalizer`, not at composition time.
    val iconTheme: IconTheme = IconTheme.Default,
    // When true, docked apps stay visible in the typed-search app list in
    // addition to the dock row. When false (the default), the dock dedupes
    // itself out of the main list to free vertical space — the launcher's
    // pre-toggle behavior.
    val isShowDockedAppsInList: Boolean = false,
    val dockIconSizeDp: Int = DEFAULT_DOCK_APP_ICON_SIZE_DP,
    val dockPositions: Map<String, DockPosition> = emptyMap(),
    // True when the dock was prefilled on first run and the user has not yet
    // docked anything; renders the "+" add-button onboarding hint in the first
    // empty slot of the personal dock's first row. Cleared permanently on the
    // first user dock to the personal dock.
    val shouldShowDockAddHint: Boolean = false,
    // Secondary "work apps" dock rendered below the personal dock when a
    // managed (work) profile is provisioned and unpaused. Independent
    // persistence and enable toggle, but it intentionally shares
    // [dockIconSizeDp] with the personal dock so the two rows stay
    // visually aligned and the user has a single slider to tune. The card
    // is hidden entirely when the profile is in quiet mode
    // (`isWorkProfileActive = false`).
    val workDockedApps: List<InstalledApp> = emptyList(),
    val workDockPositions: Map<String, DockPosition> = emptyMap(),
    // Per-dock equivalent of [shouldShowDockAddHint] for the work dock.
    val shouldShowWorkDockAddHint: Boolean = false,
    val isWorkDockEnabled: Boolean = false,
    // True when at least one work-profile `InstalledApp` is present in the
    // raw `installedApps` list, regardless of quiet mode. Drives whether the
    // "Show work dock" settings row is visible at all.
    val isWorkProfileConfigured: Boolean = false,
    // True when at least one work-profile `InstalledApp` exists with
    // `isQuietMode = false`. Drives whether the work dock renders on Home
    // and whether the settings switch is interactable.
    val isWorkProfileActive: Boolean = false,
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
    // How much of Home the current (landscape) viewport can fit. Derived from
    // the live configuration + persisted [keyboardReservation] by the Compose
    // layer and pushed back into state so `MainActivity` (window soft-input
    // mode, resume re-show) and the cold-start home-ready IME wait read one
    // value. Always [HomeLandscapeTier.Full] in portrait.
    val homeLandscapeTier: HomeLandscapeTier = HomeLandscapeTier.Full,
    // Settings → "Show agenda". When false, Agenda is removed from the
    // horizontal carousel and calendar loading is deferred until re-enabled.
    val isAgendaEnabled: Boolean = true,
    // User-selected appearance mode. `System` (default) follows the device's
    // night-mode setting; `Light` and `Dark` force the corresponding scheme
    // regardless of the system. Applied by `TypeLauncherTheme`.
    val themeMode: ThemeMode = ThemeMode.System,
    // Shape every app icon is clipped to. `System` (default) follows the
    // device's adaptive-icon mask. Applied by `AppIcon` via `LocalAppIconShape`.
    val iconShape: IconShape = IconShape.System,
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

/**
 * The sort order the app list actually renders under, given the persisted
 * "Sort apps by" [persisted] choice and the current landscape [tier].
 *
 * The cramped-landscape [HomeLandscapeTier.Compact] state always sorts by usage
 * with the most-used app at the visual bottom (`UsageReversed`) regardless of
 * the persisted choice: the app list is the only launch surface there (the docks
 * are dropped and the search box is hidden), so the most-launched apps belong
 * under the thumb, closest to where a pull-up brings the keyboard. Every other
 * state — all of portrait, and the Full landscape tier — honors [persisted].
 */
internal fun effectiveAppListSortOrder(
    persisted: AppListSortOrder,
    tier: HomeLandscapeTier,
): AppListSortOrder =
    if (tier == HomeLandscapeTier.Compact) AppListSortOrder.UsageReversed else persisted

/**
 * Whether the app list renders as an icon grid rather than text rows, given the
 * persisted "App list" layout [persistedIconOnly] choice and the current
 * landscape [tier].
 *
 * The cramped-landscape [HomeLandscapeTier.Compact] state always uses icons: the
 * short viewport fits far more apps as a grid than as full-width text rows.
 * Every other state honors [persistedIconOnly].
 */
internal fun effectiveAppListIconOnly(
    persistedIconOnly: Boolean,
    tier: HomeLandscapeTier,
): Boolean = persistedIconOnly || tier == HomeLandscapeTier.Compact

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
    // Both ends are clamped to [MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT]: that
    // is the hard cap `setDockVisibleIconCount` / `DockedAppStore` coerce every
    // selection to, so the slider must not advertise a stop it can't persist.
    // Without the cap a wide window (e.g. 524dp, where the 36dp floor admits 9
    // icons) would offer a 9-per-row stop that snaps back to 8 on release.
    val minSlotCount = dockSlotCountForIconSize(screenWidthDp, MAX_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
    val maxSlotCount = dockSlotCountForIconSize(screenWidthDp, MIN_DOCK_APP_ICON_SIZE_DP)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
    return minSlotCount..maxSlotCount
}

// The dock icon size and the per-row slot count for an available width and a
// target icon size (dp).
//
// [slotCount] is how many [targetIconSizeDp]-sized icons fit the available
// width; [iconSizeDp] is that target grown to fill the row at that count (so it
// is >= the target on real widths). The system "Display size" setting is
// honored implicitly: a larger Display size inflates the device density, which
// shrinks the available width in dp, so fewer icons fit and each fills bigger
// — the dock scales with Display size and is immune to resolution changes
// because dp absorbs both for free.
internal data class DockIconSizing(
    // grown-to-fill size for the rendered count (>= the target on real widths)
    val iconSizeDp: Int,
    // rendered columns
    val slotCount: Int,
)

// Rendered dock geometry for an available width and a target icon size (dp).
// count = how many target-sized icons fit; iconSizeDp = grown to fill the row
// at that count. Honors the system Display size implicitly: a larger Display
// size shrinks `availableWidthDp` (dp), so fewer, bigger icons render.
internal fun dockIconSizing(availableWidthDp: Int, targetIconSizeDp: Int): DockIconSizing {
    val target = targetIconSizeDp.coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
    val slotCount = dockSlotCountForIconSize(availableWidthDp, target)
        .coerceIn(MIN_DOCK_ICON_COUNT, MAX_DOCK_ICON_COUNT)
    val iconSizeDp = dockIconSizeForSlotCount(availableWidthDp, slotCount)
    return DockIconSizing(iconSizeDp = iconSizeDp, slotCount = slotCount)
}

// The width, in dp, that a single dock row of [slotCount] icons occupies at
// [iconSizeDp]: the icons plus their per-item padding and the inter-item
// spacing — the same geometry `dockSlotCountForIconSize` packs into the
// available width. The dock card is narrowed to this (plus its own padding)
// and centered in wider-than-portrait windows so it sits as an island with
// the screen background in the margins rather than stretching edge-to-edge.
internal fun dockRowContentWidthDp(slotCount: Int, iconSizeDp: Int): Int {
    val columns = slotCount.coerceAtLeast(1)
    return columns * (iconSizeDp + DOCK_ITEM_HORIZONTAL_PADDING_DP) +
        (columns - 1) * DOCK_ITEM_SPACING_DP
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
