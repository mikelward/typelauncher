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
    fun portraitAlwaysFitsKeyboardAndBox() {
        // Even a height that could never fit anything in landscape stays
        // KeyboardAndBox in portrait — the tiers are landscape-only.
        val tier = resolveHomeLandscapeTier(
            metrics(isWiderThanPortrait = false, availableHeightDp = 10, predictedKeyboardHeightDp = 999),
        )
        assertEquals(HomeLandscapeTier.KeyboardAndBox, tier)
    }

    @Test
    fun keyboardFitsAtExactBoundary() {
        // need-with-box(296) + spacing(8) + keyboard(200) = 504.
        assertEquals(
            HomeLandscapeTier.KeyboardAndBox,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 504, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun keyboardDroppedOneDpBelowBoundary() {
        assertEquals(
            HomeLandscapeTier.BoxOnly,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 503, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun boxFitsAtExactBoundary() {
        // need-with-box is 296; the keyboard does not fit but the box does.
        assertEquals(
            HomeLandscapeTier.BoxOnly,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 296, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun boxDroppedOneDpBelowBoundary() {
        assertEquals(
            HomeLandscapeTier.Hidden,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 295, predictedKeyboardHeightDp = 200)),
        )
    }

    @Test
    fun disablingDockLowersTheBoxThreshold() {
        // Without a dock, need-with-box drops to 88 + 8 + 84 = 180, so a height
        // that hid the box with a dock now keeps it.
        assertEquals(
            HomeLandscapeTier.Hidden,
            resolveHomeLandscapeTier(metrics(availableHeightDp = 200, predictedKeyboardHeightDp = 200)),
        )
        assertEquals(
            HomeLandscapeTier.BoxOnly,
            resolveHomeLandscapeTier(
                metrics(availableHeightDp = 200, predictedKeyboardHeightDp = 200, dockHeightDp = 0),
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
            dockIconCount = 4,
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
            dockIconCount = 4,
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
            dockIconCount = 4,
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
                dockIconCount = 4,
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

    private fun fingerprint(widthDp: Int, heightDp: Int, densityDpi: Int, navBottomPx: Int) =
        KeyboardReservationConfig(
            orientation = if (widthDp > heightDp) 2 else 1,
            screenWidthDp = widthDp,
            screenHeightDp = heightDp,
            densityDpi = densityDpi,
            navBottomPx = navBottomPx,
        )
}
