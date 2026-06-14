package app.typelauncher

import org.junit.Assert.assertEquals
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
    ) = HomeLandscapeMetrics(
        isWiderThanPortrait = isWiderThanPortrait,
        availableHeightDp = availableHeightDp,
        predictedKeyboardHeightDp = predictedKeyboardHeightDp,
        searchBoxHeightDp = 88,
        dockHeightDp = dockHeightDp,
        appRowHeightDp = 84,
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
    fun compactOneDpBelowFullBoundary() {
        // One dp short of fitting the keyboard → drop to Compact (app list only).
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 503, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun compactIgnoresDockHeightForTierChoice() {
        // Below the Full (keyboard) threshold there is no middle tier gated on the
        // dock — dropping the dock is a render decision, not a tier decision — so
        // two viewports differing only in dock height both resolve Compact.
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 300, predictedKeyboardHeightDp = 200)),
        )
        assertEquals(
            HomeLandscapeTier.Compact,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 300, predictedKeyboardHeightDp = 200, dockHeightDp = 0),
            ),
        )
    }

    @Test
    fun disablingDockLowersTheFullThreshold() {
        // The dock height is still reserved in the Full fit test, so a height that
        // is Compact with a dock (504 needed) becomes Full without one: removing
        // the dock drops the need to 88 + 8 + 84 + 8 + 200 = 388.
        assertEquals(
            HomeLandscapeTier.Compact,
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
                dockIconCount = 4,
                landscapeDockMode = mode,
                isPersonalDockEnabled = true,
                isWorkDockVisible = true,
                workDockedAppIds = workApps,
                workDockPositions = emptyMap(),
                keyboardReservation = KeyboardReservation(),
                reservationFingerprint = fingerprint(1280, 800, 420, navBottomPx = 0),
            ).dockHeightDp

        // Nine apps exceed the icons-per-row this 1280×800 window renders (the
        // short edge caps it at 7), so they form a two-row portrait work dock —
        // yet all nine still fit in one row across the 1280dp landscape width.
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

    private fun fingerprint(widthDp: Int, heightDp: Int, densityDpi: Int, navBottomPx: Int) =
        KeyboardReservationConfig(
            orientation = if (widthDp > heightDp) 2 else 1,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            densityDpi = densityDpi,
            navBottomPx = navBottomPx,
        )
}
