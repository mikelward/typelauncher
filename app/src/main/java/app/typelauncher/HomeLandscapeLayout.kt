package app.typelauncher

import kotlin.math.min

/**
 * How much of Home to show given the available landscape viewport.
 *
 * In landscape the screen is short, and an auto-shown keyboard can leave too
 * little room for the search box, dock(s), and app list. Rather than squeeze
 * the app list to nothing, Home degrades in three tiers as height shrinks:
 *
 *  - [KeyboardAndBox]: everything fits with the keyboard up — the default, and
 *    the only tier ever used in portrait.
 *  - [BoxOnly]: the keyboard would not fit alongside the search box, dock, and
 *    one app row, so the auto-shown keyboard is suppressed and that space goes
 *    to content. The search box stays; tapping it brings the keyboard up.
 *  - [Hidden]: even the search box + dock + one app row do not fit, so the box
 *    is hidden too and the dock + app grid get the whole viewport. A second
 *    pull-up reveals the search box and keyboard on demand.
 */
internal enum class HomeLandscapeTier {
    KeyboardAndBox,
    BoxOnly,
    Hidden,
}

// Estimated height of the search card (the `SectionCard` wrapping the filter
// field) used only by the fit decision below — never to lay the card out. The
// filter field is a default-height text field (~56dp) inside the card's
// `SECTION_CARD_PADDING_DP` of vertical chrome on each side (16 * 2 = 32).
internal const val SEARCH_CARD_ESTIMATED_HEIGHT_DP = 88

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
    dockIconCount: Int,
    isPersonalDockEnabled: Boolean,
    isWorkDockVisible: Boolean,
    workDockedAppIds: List<String>,
    workDockPositions: Map<String, DockPosition>,
    keyboardReservation: KeyboardReservation,
    reservationFingerprint: KeyboardReservationConfig,
): HomeLandscapeMetrics {
    // Dock icon size is derived from the short screen edge, matching
    // `HomeScreen` so the estimate is stable across rotation.
    val dockReferenceWidthDp = min(screenWidthDp, screenHeightDp)
    val coercedDockIconCount = dockIconCount.coerceIn(dockSlotCountRange(dockReferenceWidthDp))
    val dockIconSizeDp = dockIconSizeForSlotCount(dockReferenceWidthDp, coercedDockIconCount)

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
    val workRowHeightDp = dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP
    val maxWorkRows = if (screenHeightDp >= SMALL_SCREEN_TWO_ROW_WORK_DOCK_THRESHOLD_DP) {
        MAX_WORK_DOCK_ROWS
    } else {
        1
    }
    val workRows = if (isWorkDockVisible) {
        dockRowCount(workDockedAppIds, workDockPositions, coercedDockIconCount).coerceAtMost(maxWorkRows)
    } else {
        0
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

    return HomeLandscapeMetrics(
        isWiderThanPortrait = screenWidthDp > screenHeightDp,
        availableHeightDp = (screenHeightDp - HOME_OUTER_PADDING_DP * 2).coerceAtLeast(0),
        predictedKeyboardHeightDp = predictedKeyboardHeightDp,
        searchBoxHeightDp = SEARCH_CARD_ESTIMATED_HEIGHT_DP,
        dockHeightDp = dockHeightDp,
        appRowHeightDp = appRowHeightDp,
    )
}

/**
 * Resolves the [HomeLandscapeTier] from pre-computed [HomeLandscapeMetrics].
 * Portrait is always [HomeLandscapeTier.KeyboardAndBox]; the keyboard and then
 * the search box are dropped only when the landscape viewport can't fit them
 * alongside the dock and at least one app row.
 */
internal fun resolveHomeLandscapeTier(
    metrics: HomeLandscapeMetrics,
    cardSpacingDp: Int = HOME_CARD_SPACING_DP,
): HomeLandscapeTier {
    if (!metrics.isWiderThanPortrait) return HomeLandscapeTier.KeyboardAndBox
    val dockBlockDp = if (metrics.dockHeightDp > 0) cardSpacingDp + metrics.dockHeightDp else 0
    val needWithBoxDp = metrics.searchBoxHeightDp + cardSpacingDp + metrics.appRowHeightDp + dockBlockDp
    return when {
        needWithBoxDp + cardSpacingDp + metrics.predictedKeyboardHeightDp <= metrics.availableHeightDp ->
            HomeLandscapeTier.KeyboardAndBox
        needWithBoxDp <= metrics.availableHeightDp -> HomeLandscapeTier.BoxOnly
        else -> HomeLandscapeTier.Hidden
    }
}

private fun pxToDp(px: Int, densityDpi: Int): Int {
    val dpi = densityDpi.coerceAtLeast(1)
    return px * 160 / dpi
}
