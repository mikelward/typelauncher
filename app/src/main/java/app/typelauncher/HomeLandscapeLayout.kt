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

// Estimated height of the search card (the `SectionCard` wrapping the filter
// field) used only by the fit decision below — never to lay the card out. The
// filter field is a default-height text field (~56dp) inside the card's
// `SECTION_CARD_PADDING_DP` of vertical chrome on each side (16 * 2 = 32).
internal const val SEARCH_CARD_ESTIMATED_HEIGHT_DP = 88

// Height of one text-mode app row: the 40dp `AppIcon` plus the row's 8dp
// vertical padding on each side (see `AppRow`). Text rows don't scale with the
// dock icon-size slider, so both min-visible-rows floors — HomeScreen's
// `appListMinVisibleRowsHeightDp` and the DockNoKeyboard tier fit below —
// reserve the larger of this and the icon-grid row, or a dense dock (whose
// icon-grid row is shorter than a text row) would under-reserve the user's
// text-row layout.
internal const val APP_LIST_TEXT_ROW_HEIGHT_DP = 56

// Outer padding applied around the whole Home layout (see `HomeScreen`'s root
// `Modifier.padding(... 8.dp ...)`). Subtracted from the screen height when
// estimating how much vertical space the Home cards actually get.
internal const val HOME_OUTER_PADDING_DP = 8

// Fallback keyboard height, as a percentage of the (short) landscape screen
// height, used only before a real keyboard height has been measured and
// persisted for the current configuration. Landscape IMEs on a phone occupy
// roughly half the short edge; 55% biases slightly toward suppressing the
// keyboard, which is the safer default when we cannot yet measure.
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
    // Whether every visible dock renders as a single row in this window — the
    // gate for the keyboard-down DockNoKeyboard tier (see resolveHomeLandscapeTier).
    val dockFitsAsSingleRow: Boolean = true,
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
    fontScale: Float = 1f,
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

    val predictedKeyboardHeightDp = if (
        keyboardReservation.appliesUnder(reservationFingerprint) && keyboardReservation.bottomPx > 0
    ) {
        val navBottomPx = keyboardReservation.configFingerprint?.navBottomPx
            ?: reservationFingerprint.navBottomPx
        val heightPx = (keyboardReservation.bottomPx - navBottomPx).coerceAtLeast(0)
        pxToDp(heightPx, densityDpi)
    } else {
        screenHeightDp * LANDSCAPE_KEYBOARD_FALLBACK_PERCENT / 100
    }

    val dockFitsAsSingleRow = landscapeDockFitsAsSingleRow(
        isWiderThanPortrait = isWiderThanPortrait,
        landscapeColumnCount = landscapeColumnCount,
        isPersonalDockEnabled = isPersonalDockEnabled,
        personalDockOccupantIds = personalDockOccupantIds,
        isWorkDockVisible = isWorkDockVisible,
        workDockOccupantIds = workDockedAppIds,
    )

    return HomeLandscapeMetrics(
        isWiderThanPortrait = isWiderThanPortrait,
        availableHeightDp = (screenHeightDp - HOME_OUTER_PADDING_DP * 2).coerceAtLeast(0),
        predictedKeyboardHeightDp = predictedKeyboardHeightDp,
        searchBoxHeightDp = SEARCH_CARD_ESTIMATED_HEIGHT_DP,
        dockHeightDp = dockHeightDp,
        appRowHeightDp = appRowHeightDp,
        dockFitsAsSingleRow = dockFitsAsSingleRow,
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
 *  - [Full] when the search box, the keyboard, one app row, and the dock(s) fit.
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
    val needWithBoxDp = metrics.searchBoxHeightDp + cardSpacingDp + metrics.appRowHeightDp + dockBlockDp
    // DockNoKeyboard renders without the keyboard, but `HomeScreen` still floors
    // the app list at APP_LIST_MIN_VISIBLE_ROWS rows above the dock, so the tier
    // has to reserve that whole floor — not just one app row. The row height is
    // the *larger* of the icon-grid row (`appRowHeightDp`, from the dock icon
    // size) and the fixed-height text row: DockNoKeyboard keeps the user's
    // app-list layout, which may be text rows, and a dense dock makes the
    // icon-grid row shorter than a text row, so flooring on the icon row alone
    // would under-reserve and squeeze the layout instead of falling back to
    // Compact.
    val appListFloorRowDp = maxOf(metrics.appRowHeightDp, APP_LIST_TEXT_ROW_HEIGHT_DP)
    val needWithDockFloorDp = metrics.searchBoxHeightDp + cardSpacingDp +
        APP_LIST_MIN_VISIBLE_ROWS * appListFloorRowDp + dockBlockDp
    return when {
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
