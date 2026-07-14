package app.typelauncher

import kotlin.math.min

/**
 * How much of Home to show given the available landscape viewport.
 *
 * In landscape the screen is short, and an auto-shown keyboard can leave too
 * little room for the search box, dock(s), and app list. Rather than squeeze
 * the app list to nothing or clip the dock off the bottom of the screen, Home
 * resolves to one of three states:
 *
 *  - [Full]: the search box, the auto-shown keyboard, at least one app row, and
 *    the dock(s) all fit. This is the only state ever used in portrait; in
 *    landscape it is reached only on tall viewports (tablets), since a landscape
 *    keyboard occupies roughly half the short edge.
 *  - [DockNoKeyboard]: the keyboard does not fit, but the search box, the
 *    app list's minimum rows, and a *single-row* dock do. The dock renders
 *    with the keyboard down (auto-show suppressed); the user brings the
 *    keyboard on demand by tapping the search box, which then yields the dock
 *    its height. In landscape the dock always flattens to reading order (see
 *    `landscapeDockPositions`), so this is the common phone-landscape state
 *    whenever the dock is enabled and its occupants fit one flattened row.
 *    The box itself is additionally gated on typing headroom
 *    ([HomeLandscapeMetrics.searchBoxFitsWithKeyboard]): where the box, the
 *    raised keyboard, and one result row can't coexist, the box is hidden and
 *    the window shows just the dock and the app list.
 *  - [Compact]: everything else (not even the search box, the app-list floor,
 *    and a one-row dock fit). The app list fills the viewport on its own — the
 *    static search box is hidden by default, the auto-shown keyboard is
 *    suppressed, and both docks are dropped entirely (not clipped, not
 *    reflowed). A pull-up reveals the search box and keyboard on demand.
 */
internal enum class HomeLandscapeTier {
    Full,
    DockNoKeyboard,
    Compact,
}

/**
 * The landscape decisions the Compose layer feeds `HomeScreen`: the [tier]
 * plus whether the search box has typing headroom
 * ([HomeLandscapeMetrics.searchBoxFitsWithKeyboard]). The two are separate
 * axes — a window can fit the keyboard-down dock (DockNoKeyboard) while still
 * being too short to fit the box, the raised keyboard, and one result row, in
 * which case the box is hidden entirely and the window shows just the dock
 * and the app list.
 */
internal data class HomeLandscapeUi(
    val tier: HomeLandscapeTier,
    val searchBoxFitsWithKeyboard: Boolean,
)

// Estimated height of the search card (the `SectionCard` wrapping the filter
// field) used only by the fit decision below — never to lay the card out. The
// filter field is the dense 48dp search field (see `LauncherFilterField`)
// inside the search card's 8dp of vertical chrome on each side (8 * 2 = 16).
// Keep this in step with that field height and the search card's vertical
// padding in `SearchCard`, or the landscape fit gate mis-hides the box.
internal const val SEARCH_CARD_ESTIMATED_HEIGHT_DP = 64

// Height of one text-mode app row: the 40dp `AppIcon` plus the row's 8dp
// vertical padding on each side (see `AppRow`). Text rows don't scale with the
// dock icon-size slider, so both min-visible-rows floors — HomeScreen's
// `appListMinVisibleRowsHeightDp` and the DockNoKeyboard tier fit below —
// reserve the larger of this and the icon-grid row, or a dense dock (whose
// icon-grid row is shorter than a text row) would under-reserve the user's
// text-row layout.
internal const val APP_LIST_TEXT_ROW_HEIGHT_DP = 56

// Height of the label strip a NameBelow grid tile adds under its icon: the 4dp
// top padding plus the single `labelSmall` line (see `IconOnlyAppButton`).
// Same 20dp strip — and the same font-scale treatment — as the dock's
// TitleBelow strip in `dockSlotHeightDp`, since both draw one `labelSmall`
// line under an icon.
internal const val APP_LIST_NAME_BELOW_LABEL_HEIGHT_DP = 20

/**
 * Height of one app-list row at the layout's reserve floor, for the persisted
 * [appListLayout]: the icon-grid row (`dockIconSizeDp` + spacing, plus the
 * label strip when the grid draws names below its icons), never smaller than
 * the fixed-height text row. Shared by `appListMinVisibleRowsHeightDp` (the
 * hard minimum HomeScreen's layout enforces against the dock and the recents
 * bar) and the DockNoKeyboard tier fit in [resolveHomeLandscapeTier], so the
 * tier decision always reserves exactly what the layout will.
 *
 * Callers with a live density pass `fontScale`; configuration-only callers may
 * leave it at 1.0 and accept a slight under-reserve at large font scales.
 */
internal fun appListFloorRowHeightDp(
    dockIconSizeDp: Int,
    appListLayout: AppListLayout,
    fontScale: Float = 1f,
): Int {
    val labelStripDp = if (appListLayout == AppListLayout.NameBelow) {
        (APP_LIST_NAME_BELOW_LABEL_HEIGHT_DP * fontScale.coerceAtLeast(1f)).toInt()
    } else {
        0
    }
    val gridRowDp = dockIconSizeDp + DOCK_ITEM_SPACING_DP * 2 + labelStripDp
    return maxOf(gridRowDp, APP_LIST_TEXT_ROW_HEIGHT_DP)
}

// Outer padding applied around the whole Home layout (see `HomeScreen`'s root
// `Modifier.padding(... 8.dp ...)`). Subtracted from the screen height when
// estimating how much vertical space the Home cards actually get.
internal const val HOME_OUTER_PADDING_DP = 8

// Fallback keyboard height, as a percentage of the (short) landscape screen
// height net of the system bars, used only before a real keyboard height has
// been measured and persisted for the current configuration. Landscape IMEs
// on a phone occupy roughly half the short edge; 55% biases slightly toward
// suppressing the keyboard, which is the safer default when we cannot yet
// measure.
internal const val LANDSCAPE_KEYBOARD_FALLBACK_PERCENT = 55

/**
 * The estimated dp heights the [resolveHomeLandscapeTier] decision is made
 * against. Derived purely from the configuration, dock settings, and the
 * persisted keyboard reservation, so the same inputs produce the same tier
 * whether computed from Compose's `LocalConfiguration` or the activity's
 * `resources.configuration`.
 */
internal data class HomeLandscapeMetrics(
    val isWiderThanPortrait: Boolean,
    val availableHeightDp: Int,
    val predictedKeyboardHeightDp: Int,
    val searchBoxHeightDp: Int,
    val dockHeightDp: Int,
    val appRowHeightDp: Int,
    // One app-list row at the layout's reserve floor (see
    // appListFloorRowHeightDp): what the DockNoKeyboard fit multiplies by
    // APP_LIST_MIN_VISIBLE_ROWS, matching HomeScreen's own hard minimum.
    val appListFloorRowHeightDp: Int = APP_LIST_TEXT_ROW_HEIGHT_DP,
    // Whether every visible dock renders as a single row in this window — the
    // gate for the keyboard-down DockNoKeyboard tier (see resolveHomeLandscapeTier).
    val dockFitsAsSingleRow: Boolean = true,
    // Whether the search box, the raised keyboard, and at least one app-list
    // result row fit together (the dock yields while the keyboard is up, so it
    // is not counted). When false, the launcher never shows the search box in
    // this window — tapping it would raise a keyboard that squeezes the result
    // list to a clipped sliver, so a box it can't honor is hidden instead and
    // search stays a portrait affordance. Always true in portrait.
    val searchBoxFitsWithKeyboard: Boolean = true,
)

/**
 * Computes the [HomeLandscapeMetrics] for a configuration. Mirrors the height
 * arithmetic `HomeScreen`'s custom layout uses (dock icon size from the short
 * edge, one app row ≈ icon + 2 * spacing, one row per present dock card plus
 * card chrome) so the fit decision tracks what the layout will actually produce.
 */
internal fun homeLandscapeMetrics(
    screenWidthDp: Int,
    screenHeightDp: Int,
    densityDpi: Int,
    targetDockIconSizeDp: Int,
    isPersonalDockEnabled: Boolean,
    // Occupant ids (loose apps + folders), matching what the dock renders.
    // Only the count matters: the landscape flatten is position-independent
    // (reading order compacts gaps), and the portrait personal card is always
    // estimated at its one-row minimum.
    personalDockOccupantIds: List<String>,
    isWorkDockVisible: Boolean,
    workDockedAppIds: List<String>,
    workDockPositions: Map<String, DockPosition>,
    keyboardReservation: KeyboardReservation,
    reservationFingerprint: KeyboardReservationConfig,
    dockLayout: DockLayout = DockLayout.IconOnly,
    // The persisted "App list" layout: a NameBelow grid's rows are taller (the
    // label strip), and the DockNoKeyboard floor must reserve that too.
    appListLayout: AppListLayout = AppListLayout.NameBeside,
    fontScale: Float = 1f,
    // The vertical system-bar space (status-bar top + navigation-bar bottom
    // insets, in px) that [screenHeightDp] includes but the window chrome
    // consumes before Home's cards get any height. On Android 15+ (with this
    // app's targetSdk), `Configuration.screenHeightDp` spans the full window
    // *including* the system bars, while the Scaffold hands Home only the
    // space between them — so every fit estimate here must subtract the bars
    // or it overestimates the viewport by ~48dp on a phone and keeps the
    // search box on a window that then clips the one result row it promised.
    // Callers on older platforms (where the Configuration already excludes
    // the bars) pass 0.
    verticalSystemBarsPx: Int = 0,
): HomeLandscapeMetrics {
    // Dock geometry is derived from the short screen edge and the target icon
    // size, matching `HomeScreen`'s `dockIconSizing` so the estimate is stable
    // across rotation and tracks the system Display size (the dp-width shrinks as
    // Display size grows). The rendered per-row count (`slotCount`) is what the
    // work card reflows against.
    val dockReferenceWidthDp = min(screenWidthDp, screenHeightDp)
    val dockSizing = dockIconSizing(dockReferenceWidthDp, targetDockIconSizeDp)
    val coercedDockIconCount = dockSizing.slotCount
    val dockIconSizeDp = dockSizing.iconSizeDp

    val appRowHeightDp = dockIconSizeDp + DOCK_ITEM_SPACING_DP * 2
    // `HomeScreen` stacks the personal and work dock cards in a Column separated
    // by HOME_CARD_SPACING_DP, so the dock area is the sum of the present cards
    // plus the gap — not a single card. Mirror the renderer's per-card heights:
    // the personal card at one row (its minimum in the slot; it scrolls beyond
    // that), and the work card at its actual occupied row count, clamped exactly
    // like `HomeScreen` (one row below the small-screen threshold, otherwise up
    // to MAX_WORK_DOCK_ROWS). Under-counting here would leave the box visible on
    // a viewport that can't fit it; this keeps the Hidden threshold honest when
    // a two-row work dock is present.
    //
    // The work card is counted by *visibility*, not app count: `HomeScreen`
    // renders it whenever the work dock is enabled and the profile is active —
    // even with no apps pinned (it shows the add hint) — and `dockRowCount`
    // already floors at one row, so an empty-but-visible work dock still
    // occupies a one-row card here.
    val workRowHeightDp = dockSlotHeightDp(dockIconSizeDp, dockLayout, fontScale)
    val maxWorkRows = if (screenHeightDp >= SMALL_SCREEN_TWO_ROW_WORK_DOCK_THRESHOLD_DP) {
        MAX_WORK_DOCK_ROWS
    } else {
        1
    }
    // In landscape the docks flatten into a single wider reading-order row —
    // wrapping only when the occupants exceed what the landscape width fits —
    // so count the work dock at its flattened height, mirroring how
    // `HomeScreen` reflows it through `landscapeDockPositions`. Portrait keeps
    // the portrait row count. Without this, the fit decision would drop a dock
    // that actually fits on a viewport that holds one flattened work row but
    // not two portrait rows.
    val isWiderThanPortrait = screenWidthDp > screenHeightDp
    val landscapeColumnCount = landscapeDockColumnCount(
        isPersonalDockEnabled = isPersonalDockEnabled,
        personalDockOccupantCount = personalDockOccupantIds.size,
        isWorkDockVisible = isWorkDockVisible,
        workDockOccupantCount = workDockedAppIds.size,
        dockIconCount = coercedDockIconCount,
        landscapeFitColumns = dockSlotCountForIconSize(screenWidthDp, dockIconSizeDp),
    ).coerceAtLeast(1)
    val workRows = when {
        !isWorkDockVisible -> 0
        isWiderThanPortrait ->
            ((workDockedAppIds.size + landscapeColumnCount - 1) / landscapeColumnCount)
                .coerceIn(1, maxWorkRows)
        else -> dockRowCount(workDockedAppIds, workDockPositions, coercedDockIconCount)
            .coerceAtMost(maxWorkRows)
    }
    val personalCardHeightDp = if (isPersonalDockEnabled) {
        workRowHeightDp + SECTION_CARD_PADDING_DP * 2
    } else {
        0
    }
    val workCardHeightDp = if (workRows > 0) {
        workRows * workRowHeightDp + (workRows - 1) * DOCK_ITEM_SPACING_DP + SECTION_CARD_PADDING_DP * 2
    } else {
        0
    }
    val bothCardsPresent = personalCardHeightDp > 0 && workCardHeightDp > 0
    val dockHeightDp = personalCardHeightDp + workCardHeightDp +
        (if (bothCardsPresent) HOME_CARD_SPACING_DP else 0)

    // The bar-adjusted viewport every height estimate below is taken against:
    // the screen height net of the system bars the window chrome consumes (see
    // the verticalSystemBarsPx parameter doc). Both the available-height
    // budget and the pre-measurement keyboard fallback derive from this same
    // value, so a bar-inclusive Configuration cannot inflate the fallback
    // estimate against a budget that excludes the bars.
    val barAdjustedScreenHeightDp =
        (screenHeightDp - pxToDp(verticalSystemBarsPx, densityDpi)).coerceAtLeast(0)

    val keyboardHeightIsMeasured =
        keyboardReservation.appliesUnder(reservationFingerprint) && keyboardReservation.bottomPx > 0
    val predictedKeyboardHeightDp = if (keyboardHeightIsMeasured) {
        val navBottomPx = keyboardReservation.configFingerprint?.navBottomPx
            ?: reservationFingerprint.navBottomPx
        val heightPx = (keyboardReservation.bottomPx - navBottomPx).coerceAtLeast(0)
        pxToDp(heightPx, densityDpi)
    } else {
        barAdjustedScreenHeightDp * LANDSCAPE_KEYBOARD_FALLBACK_PERCENT / 100
    }
    // The estimate the search-box gate runs against, in preference order:
    //  1. This configuration's measured keyboard, when the persisted slot
    //     holds one confirmed by an actually-visible IME.
    //  2. The 55% fallback, *raised* to any other-configuration VisibleIme
    //     measurement in the slot when that is taller. The single persisted
    //     slot is overwritten by every keyboard settle, and portrait typing
    //     dominates a launcher, so it usually holds the portrait keyboard — a
    //     real measurement of this device's IME that runs a little taller than
    //     the landscape one. It is only allowed to make the estimate *taller*
    //     (hide more): a non-applicable row can be stale in ways this code
    //     cannot validate (density, nav-mode, display-size changes — not just
    //     rotation), so it never overrules the fallback into showing the box.
    // Legacy/AnimationTarget rows may over-read (pre-source rows load as
    // AnimationTarget, often wildcard-fingerprinted), so only VisibleIme rows
    // are used for (1)/(2); anything else uses the fallback alone.
    val fallbackKeyboardDp = barAdjustedScreenHeightDp * LANDSCAPE_KEYBOARD_FALLBACK_PERCENT / 100
    val gateKeyboardHeightDp = when {
        keyboardHeightIsMeasured &&
            keyboardReservation.source == KeyboardReservationSource.VisibleIme ->
            predictedKeyboardHeightDp
        keyboardReservation.bottomPx > 0 &&
            keyboardReservation.source == KeyboardReservationSource.VisibleIme -> {
            val navBottomPx = keyboardReservation.configFingerprint?.navBottomPx ?: 0
            maxOf(
                pxToDp((keyboardReservation.bottomPx - navBottomPx).coerceAtLeast(0), densityDpi),
                fallbackKeyboardDp,
            )
        }
        else -> fallbackKeyboardDp
    }

    val dockFitsAsSingleRow = landscapeDockFitsAsSingleRow(
        isWiderThanPortrait = isWiderThanPortrait,
        landscapeColumnCount = landscapeColumnCount,
        isPersonalDockEnabled = isPersonalDockEnabled,
        personalDockOccupantIds = personalDockOccupantIds,
        isWorkDockVisible = isWorkDockVisible,
        workDockOccupantIds = workDockedAppIds,
    )

    val availableHeightDp = (barAdjustedScreenHeightDp - HOME_OUTER_PADDING_DP * 2).coerceAtLeast(0)
    val floorRowDp = appListFloorRowHeightDp(dockIconSizeDp, appListLayout, fontScale)
    // The box is only worth showing where tapping it still leaves at least one
    // result row under the raised keyboard. The dock is not counted — it
    // yields while the keyboard is up. Mirrors the Full test's arithmetic with
    // the dock term dropped and the floor row (which knows about text rows and
    // NameBelow labels) in place of the bare icon row.
    //
    // The gate always engages — the default is no search box in landscape
    // unless typing is known to fit against the best estimate above. The
    // asymmetry is deliberate: a wrongly hidden box degrades to the designed
    // dock + list landscape (search stays a portrait affordance), while a
    // wrongly shown box reproduces the clipped-results layout the gate exists
    // to prevent, and — because the single reservation slot is rewritten by
    // every portrait typing session — an optimistic "until measured" phase
    // keeps returning instead of converging.
    val searchBoxFitsWithKeyboard = !isWiderThanPortrait ||
        SEARCH_CARD_ESTIMATED_HEIGHT_DP + HOME_CARD_SPACING_DP + floorRowDp +
        HOME_CARD_SPACING_DP + gateKeyboardHeightDp <= availableHeightDp

    return HomeLandscapeMetrics(
        isWiderThanPortrait = isWiderThanPortrait,
        availableHeightDp = availableHeightDp,
        predictedKeyboardHeightDp = predictedKeyboardHeightDp,
        searchBoxHeightDp = SEARCH_CARD_ESTIMATED_HEIGHT_DP,
        dockHeightDp = dockHeightDp,
        appRowHeightDp = appRowHeightDp,
        appListFloorRowHeightDp = floorRowDp,
        dockFitsAsSingleRow = dockFitsAsSingleRow,
        searchBoxFitsWithKeyboard = searchBoxFitsWithKeyboard,
    )
}

/**
 * Whether every visible dock would render as a single row in this landscape
 * window — the gate for [HomeLandscapeTier.DockNoKeyboard]. Landscape always
 * flattens each dock into a reading-order row, which wraps only when its
 * occupants exceed [landscapeColumnCount]; a dock whose card is not rendered
 * contributes no rows, and a visible-but-empty dock still occupies one row
 * (the add-hint card). Portrait always returns true (the tier is forced to
 * [HomeLandscapeTier.Full] there).
 */
internal fun landscapeDockFitsAsSingleRow(
    isWiderThanPortrait: Boolean,
    landscapeColumnCount: Int,
    isPersonalDockEnabled: Boolean,
    personalDockOccupantIds: List<String>,
    isWorkDockVisible: Boolean,
    workDockOccupantIds: List<String>,
): Boolean {
    if (!isWiderThanPortrait) return true
    val columns = landscapeColumnCount.coerceAtLeast(1)
    fun rowsFor(isVisible: Boolean, occupantIds: List<String>): Int = when {
        !isVisible -> 0
        occupantIds.isEmpty() -> 1
        else -> (occupantIds.size + columns - 1) / columns
    }
    return rowsFor(isPersonalDockEnabled, personalDockOccupantIds) <= 1 &&
        rowsFor(isWorkDockVisible, workDockOccupantIds) <= 1
}

/**
 * Resolves the [HomeLandscapeTier] from pre-computed [HomeLandscapeMetrics].
 * Portrait is always [HomeLandscapeTier.Full]. In landscape:
 *  - [Full] when the search box, the keyboard, one floor-height app row
 *    ([HomeLandscapeMetrics.appListFloorRowHeightDp]), and the dock(s) fit —
 *    and the search-box gate ([HomeLandscapeMetrics.searchBoxFitsWithKeyboard])
 *    passes, so Full can never auto-show a keyboard over a hidden search card.
 *  - [HomeLandscapeTier.DockNoKeyboard] when the keyboard does not fit but the
 *    search box, the dock, and the app list's `APP_LIST_MIN_VISIBLE_ROWS` floor
 *    do — and [HomeLandscapeMetrics.dockFitsAsSingleRow] is true (a multi-row
 *    dock is not worth the height-constrained app list, so it falls through to
 *    [Compact] instead).
 *  - [Compact] otherwise — hides the box, suppresses the keyboard, drops the
 *    dock(s).
 *
 * The keyboard height is reserved only in the [Full] test; [DockNoKeyboard]
 * shows the dock with the keyboard down, so it is sized without it.
 */
internal fun resolveHomeLandscapeTier(
    metrics: HomeLandscapeMetrics,
    cardSpacingDp: Int = HOME_CARD_SPACING_DP,
): HomeLandscapeTier {
    if (!metrics.isWiderThanPortrait) return HomeLandscapeTier.Full
    val dockBlockDp = if (metrics.dockHeightDp > 0) cardSpacingDp + metrics.dockHeightDp else 0
    // The Full test reserves the same per-row floor as everything else
    // (appListFloorRowHeightDp — text rows and NameBelow labels included), not
    // the bare icon row.
    val needWithBoxDp = metrics.searchBoxHeightDp + cardSpacingDp + metrics.appListFloorRowHeightDp + dockBlockDp
    // DockNoKeyboard renders without the keyboard, but `HomeScreen` still floors
    // the app list at APP_LIST_MIN_VISIBLE_ROWS rows above the dock, so the tier
    // has to reserve that whole floor — not just one app row. The per-row height
    // comes from `appListFloorRowHeightDp`, the same helper HomeScreen's own
    // minimum uses: the icon-grid row (plus the NameBelow label strip when the
    // persisted layout draws names under the icons), never less than the
    // fixed-height text row — so a dense dock or a labeled grid can't let the
    // tier under-reserve and squeeze the layout instead of falling back to
    // Compact.
    // A window without typing headroom never renders the search card, so the
    // keyboard-down fit must not budget it either — otherwise a window whose
    // dock and two-row list fit fine would drop to Compact (losing the dock)
    // over an 88dp card that was never going to be shown.
    val searchBlockDp = if (metrics.searchBoxFitsWithKeyboard) {
        metrics.searchBoxHeightDp + cardSpacingDp
    } else {
        0
    }
    val needWithDockFloorDp = searchBlockDp +
        APP_LIST_MIN_VISIBLE_ROWS * metrics.appListFloorRowHeightDp + dockBlockDp
    return when {
        // Full explicitly requires the search-box gate: the gate's keyboard
        // estimate can exceed the tier's `predictedKeyboardHeightDp` (a
        // portrait-measured keyboard vs the landscape fallback), and a window
        // must never resolve Full — auto-showing the IME, waiting on it for
        // home-ready — while the search card it would focus is hidden. When
        // only the gate fails, the window falls through to the keyboard-down
        // states instead.
        metrics.searchBoxFitsWithKeyboard &&
            needWithBoxDp + cardSpacingDp + metrics.predictedKeyboardHeightDp <= metrics.availableHeightDp ->
            HomeLandscapeTier.Full
        metrics.dockHeightDp > 0 && metrics.dockFitsAsSingleRow &&
            needWithDockFloorDp <= metrics.availableHeightDp ->
            HomeLandscapeTier.DockNoKeyboard
        else -> HomeLandscapeTier.Compact
    }
}

private fun pxToDp(px: Int, densityDpi: Int): Int {
    val dpi = densityDpi.coerceAtLeast(1)
    return px * 160 / dpi
}
