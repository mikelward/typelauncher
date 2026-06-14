package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.min

class HomeLandscapeLayoutTest {

    // search(88) + spacing(8) + appRow(84) + spacing(8) + dock(108) = 296
    private fun metrics(
        isWiderThanPortrait: Boolean = true,
        availableHeightDp: Int,
        predictedKeyboardHeightDp: Int,
        dockHeightDp: Int = 108,
        appRowHeightDp: Int = 84,
        dockFitsAsSingleRow: Boolean = true,
    ) = HomeLandscapeMetrics(
        isWiderThanPortrait = isWiderThanPortrait,
        availableHeightDp = availableHeightDp,
        predictedKeyboardHeightDp = predictedKeyboardHeightDp,
        searchBoxHeightDp = 88,
        dockHeightDp = dockHeightDp,
        appRowHeightDp = appRowHeightDp,
        dockFitsAsSingleRow = dockFitsAsSingleRow,
    )

    @Test
    fun portraitAlwaysFull() {
        // Even a height that could never fit anything in landscape stays Full in
        // portrait — the two-state degradation is landscape-only.
        val tier = resolveHomeLandscapeTier(
            metrics(isWiderThanPortrait = false, availableHeightDp = 10, predictedKeyboardHeightDp = 999),
        )
        assertEquals(HomeLandscapeTier.Full, tier)
    }

    @Test
    fun fullWhenEverythingFitsIncludingKeyboard() {
        // A tall (tablet) viewport fits box + keyboard + an app row + the dock:
        // need-with-box(296) + spacing(8) + keyboard(200) = 504 <= 800.
        assertEquals(
            HomeLandscapeTier.Full,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 800, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun fullAtExactBoundary() {
        // need-with-box(296) + spacing(8) + keyboard(200) = 504.
        assertEquals(
            HomeLandscapeTier.Full,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 504, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun dockNoKeyboardOneDpBelowFullBoundary() {
        // One dp short of fitting the keyboard, but the box + the two-row app-list
        // floor + a single-row dock fit without it (need-with-floor = 88 + 8 +
        // 2*84 + 8 + 108 = 380 <= 503) → show the dock with the keyboard down
        // rather than dropping it.
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 503, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun dockNoKeyboardAtExactAppListFloorBoundary() {
        // Exactly enough for the box + the two-row app-list floor + a single-row
        // dock without the keyboard (need-with-floor = 380).
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 380, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun compactWhenTheTwoRowFloorPlusDockDoesNotFit() {
        // One dp short of the box + two-row app-list floor + dock (380) → Compact,
        // rather than entering DockNoKeyboard and squeezing the dock below the
        // app-list floor.
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 379, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun compactWhenDockWouldBeMultipleRows() {
        // The box + two-row floor + dock fit without the keyboard (380 <= 400),
        // but a multi-row dock isn't worth the height-constrained app list, so it
        // falls through to Compact instead of DockNoKeyboard.
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 400, predictedKeyboardHeightDp = 200, dockFitsAsSingleRow = false),
            ),
        )
    }

    @Test
    fun compactWhenThereIsNoDockAndTheKeyboardDoesNotFit() {
        // With no dock there is no DockNoKeyboard tier to fall into (the middle
        // tier exists to show the dock), so below the no-dock Full threshold
        // (88 + 8 + 84 + 8 + 200 = 388) the result is Compact.
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 300, predictedKeyboardHeightDp = 200, dockHeightDp = 0),
            ),
        )
    }

    @Test
    fun dockNoKeyboardFloorReservesTextRowHeightForADenseDock() {
        // A dense dock makes the icon-grid app row (40dp here) shorter than a
        // 56dp text row. DockNoKeyboard keeps the user's app-list layout (maybe
        // text rows), so the floor must reserve two *text* rows, not two icon
        // rows: text floor = 88 + 8 + 2*56 + 8 + 108 = 324; the icon-row floor
        // (the bug) would be 88 + 8 + 2*40 + 8 + 108 = 292.
        // At 300dp the icon-row floor would wrongly admit DockNoKeyboard; the
        // text-row floor correctly falls back to Compact.
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 300, predictedKeyboardHeightDp = 200, appRowHeightDp = 40),
            ),
        )
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 324, predictedKeyboardHeightDp = 200, appRowHeightDp = 40),
            ),
        )
    }

    @Test
    fun disablingDockLowersTheFullThreshold() {
        // The dock height is reserved in the Full fit test, so a height that is
        // only DockNoKeyboard with a dock (504 needed for Full) becomes Full
        // without one: removing the dock drops the need to 88 + 8 + 84 + 8 + 200 = 388.
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 400, predictedKeyboardHeightDp = 200)),
        )
        assertEquals(
            HomeLandscapeTier.Full,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 400, predictedKeyboardHeightDp = 200, dockHeightDp = 0),
            ),
        )
    }

    @Test
    fun metricsUseLandscapeFallbackWhenNoReservation() {
        // 393dp short edge, no persisted reservation → 55% fallback = 216dp.
        val m = homeLandscapeMetrics(
            screenWidthDp = 851,
            screenHeightDp = 393,
            densityDpi = 420,
            targetDockIconSizeDp = dockIconSizeForSlotCount(393, 4),
            landscapeDockMode = LandscapeDockMode.Same,
            isPersonalDockEnabled = true,
            personalDockedAppIds = emptyList(),
            personalDockPositions = emptyMap(),
            isWorkDockVisible = false,
            workDockedAppIds = emptyList(),
            workDockPositions = emptyMap(),
            keyboardReservation = KeyboardReservation(),
            reservationFingerprint = fingerprint(851, 393, 420, navBottomPx = 0),
        )
        assertEquals(true, m.isWiderThanPortrait)
        assertEquals(393 - HOME_OUTER_PADDING_DP * 2, m.availableHeightDp)
        assertEquals(393 * LANDSCAPE_KEYBOARD_FALLBACK_PERCENT / 100, m.predictedKeyboardHeightDp)
        assertEquals(SEARCH_CARD_ESTIMATED_HEIGHT_DP, m.searchBoxHeightDp)
        // Dock + app-row heights mirror the layout's dock-icon-size arithmetic.
        val iconSize = dockIconSizeForSlotCount(min(851, 393), 4.coerceIn(dockSlotCountRange(min(851, 393))))
        assertEquals(iconSize + DOCK_ITEM_SPACING_DP * 2, m.appRowHeightDp)
        assertEquals(iconSize + DOCK_ITEM_VERTICAL_PADDING_DP + SECTION_CARD_PADDING_DP * 2, m.dockHeightDp)
    }

    @Test
    fun metricsUsePersistedReservationWhenItApplies() {
        // 2x density: (bottomPx 600 − nav 100) px = 500 px = 250 dp.
        val fp = fingerprint(851, 393, 320, navBottomPx = 100)
        val m = homeLandscapeMetrics(
            screenWidthDp = 851,
            screenHeightDp = 393,
            densityDpi = 320,
            targetDockIconSizeDp = dockIconSizeForSlotCount(393, 4),
            landscapeDockMode = LandscapeDockMode.Same,
            isPersonalDockEnabled = true,
            personalDockedAppIds = emptyList(),
            personalDockPositions = emptyMap(),
            isWorkDockVisible = false,
            workDockedAppIds = emptyList(),
            workDockPositions = emptyMap(),
            keyboardReservation = KeyboardReservation(
                bottomPx = 600,
                configFingerprint = fp,
                source = KeyboardReservationSource.VisibleIme,
            ),
            reservationFingerprint = fp,
        )
        assertEquals(250, m.predictedKeyboardHeightDp)
    }

    @Test
    fun metricsIgnoreReservationFromADifferentConfiguration() {
        // A reservation measured in a different (portrait) configuration does
        // not apply, so the landscape fallback is used instead.
        val persisted = fingerprint(393, 851, 420, navBottomPx = 60)
        val current = fingerprint(851, 393, 420, navBottomPx = 0)
        val m = homeLandscapeMetrics(
            screenWidthDp = 851,
            screenHeightDp = 393,
            densityDpi = 420,
            targetDockIconSizeDp = dockIconSizeForSlotCount(393, 4),
            landscapeDockMode = LandscapeDockMode.Same,
            isPersonalDockEnabled = true,
            personalDockedAppIds = emptyList(),
            personalDockPositions = emptyMap(),
            isWorkDockVisible = false,
            workDockedAppIds = emptyList(),
            workDockPositions = emptyMap(),
            keyboardReservation = KeyboardReservation(bottomPx = 1200, configFingerprint = persisted),
            reservationFingerprint = current,
        )
        assertEquals(393 * LANDSCAPE_KEYBOARD_FALLBACK_PERCENT / 100, m.predictedKeyboardHeightDp)
    }

    @Test
    fun metricsCountBothDockCardsWhenWorkDockVisible() {
        fun dockHeightFor(personal: Boolean, workVisible: Boolean, workApps: List<String> = emptyList()) =
            homeLandscapeMetrics(
                // Tall enough for a two-row work dock (>= 600dp short edge).
                screenWidthDp = 1280,
                screenHeightDp = 800,
                densityDpi = 420,
                targetDockIconSizeDp = dockIconSizeForSlotCount(800, 4),
                landscapeDockMode = LandscapeDockMode.Same,
                isPersonalDockEnabled = personal,
                personalDockedAppIds = emptyList(),
                personalDockPositions = emptyMap(),
                isWorkDockVisible = workVisible,
                workDockedAppIds = workApps,
                workDockPositions = emptyMap(),
                keyboardReservation = KeyboardReservation(),
                reservationFingerprint = fingerprint(1280, 800, 420, navBottomPx = 0),
            ).dockHeightDp

        val personalOnly = dockHeightFor(personal = true, workVisible = false)
        // Enough apps to overflow any row width forces the work card's two-row cap.
        val oneWorkApp = listOf("w1")
        val manyWorkApps = (1..20).map { "w$it" }

        // Adding the work card adds a second card plus the inter-card gap.
        assertTrue(
            dockHeightFor(personal = true, workVisible = true, workApps = oneWorkApp) >
                personalOnly + HOME_CARD_SPACING_DP,
        )
        // A visible but *empty* work dock still occupies a one-row card (HomeScreen
        // renders the add-hint card), so it must be counted the same as one app.
        assertEquals(
            dockHeightFor(personal = true, workVisible = true, workApps = oneWorkApp),
            dockHeightFor(personal = true, workVisible = true, workApps = emptyList()),
        )
        // A two-row work dock is estimated taller than a one-row one.
        assertTrue(
            dockHeightFor(personal = true, workVisible = true, workApps = manyWorkApps) >
                dockHeightFor(personal = true, workVisible = true, workApps = oneWorkApp),
        )
        assertEquals(0, dockHeightFor(personal = false, workVisible = false))
    }

    @Test
    fun metricsCountFlattenedWorkDockAsASingleRow() {
        fun dockHeightFor(mode: LandscapeDockMode, workApps: List<String>) =
            homeLandscapeMetrics(
                screenWidthDp = 1280,
                screenHeightDp = 800,
                densityDpi = 420,
                targetDockIconSizeDp = MAX_DOCK_APP_ICON_SIZE_DP,
                landscapeDockMode = mode,
                isPersonalDockEnabled = true,
                personalDockedAppIds = emptyList(),
                personalDockPositions = emptyMap(),
                isWorkDockVisible = true,
                workDockedAppIds = workApps,
                workDockPositions = emptyMap(),
                keyboardReservation = KeyboardReservation(),
                reservationFingerprint = fingerprint(1280, 800, 420, navBottomPx = 0),
            ).dockHeightDp

        // At the max icon size the 800dp short edge renders well under nine per
        // row, so nine apps form a two-row portrait work dock — yet all nine
        // still fit in one row across the 1280dp landscape width.
        val twoPortraitRows = (1..9).map { "w$it" }

        // Premise: in Same mode the nine apps wrap to a taller, two-row card.
        assertTrue(
            dockHeightFor(LandscapeDockMode.Same, twoPortraitRows) >
                dockHeightFor(LandscapeDockMode.Same, listOf("w1")),
        )
        // A flatten mode renders the work dock as one wider row, so its card is
        // the height of a single-row work dock — shorter than Same's two-row
        // estimate. This is what lets the tier decision stay Full where the
        // two-row estimate would have dropped to Compact and hidden the dock.
        assertEquals(
            dockHeightFor(LandscapeDockMode.Split, listOf("w1")),
            dockHeightFor(LandscapeDockMode.Split, twoPortraitRows),
        )
        assertTrue(
            dockHeightFor(LandscapeDockMode.Split, twoPortraitRows) <
                dockHeightFor(LandscapeDockMode.Same, twoPortraitRows),
        )
    }

    private fun singleRow(
        mode: LandscapeDockMode,
        personalApps: Int,
        dockIconCount: Int = 6,
        landscapeFitColumns: Int = 12,
        isWiderThanPortrait: Boolean = true,
    ) = landscapeDockFitsAsSingleRow(
        isWiderThanPortrait = isWiderThanPortrait,
        landscapeDockMode = mode,
        dockIconCount = dockIconCount,
        landscapeFitColumns = landscapeFitColumns,
        isPersonalDockEnabled = true,
        personalDockedAppIds = (1..personalApps).map { "p$it" },
        personalDockPositions = emptyMap(),
        isWorkDockVisible = false,
        workDockedAppIds = emptyList(),
        workDockPositions = emptyMap(),
    )

    @Test
    fun singleRowTrueForFlattenWhenAppsFitOneLandscapeRow() {
        // Eight apps flatten into a single 8-wide row (<= the 12-column fit).
        assertTrue(singleRow(LandscapeDockMode.Split, personalApps = 8))
    }

    @Test
    fun singleRowFalseForFlattenWhenAppsOverflowLandscapeWidth() {
        // Twenty apps exceed the 12-column landscape fit, so the flatten wraps.
        assertFalse(singleRow(LandscapeDockMode.Reading, personalApps = 20))
    }

    @Test
    fun singleRowTrueForSameWhenDockIsOneRow() {
        // Four apps at six icons per row is a single portrait row.
        assertTrue(singleRow(LandscapeDockMode.Same, personalApps = 4))
    }

    @Test
    fun singleRowFalseForSameWhenDockWrapsToTwoRows() {
        // Eight apps at six icons per row is two portrait rows; Same keeps that,
        // so the height-constrained landscape tier won't show it.
        assertFalse(singleRow(LandscapeDockMode.Same, personalApps = 8))
    }

    @Test
    fun singleRowTrueInPortrait() {
        // Portrait is forced to Full regardless, so the gate is a no-op there.
        assertTrue(singleRow(LandscapeDockMode.Same, personalApps = 8, isWiderThanPortrait = false))
    }

    private fun fingerprint(widthDp: Int, heightDp: Int, densityDpi: Int, navBottomPx: Int) =
        KeyboardReservationConfig(
            orientation = if (widthDp > heightDp) 2 else 1,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            densityDpi = densityDpi,
            navBottomPx = navBottomPx,
        )
}
