package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers `sideDockFit`: a side-column dock shrinks its icons to fit the
 * window's height, wraps to two columns once even the minimum size can't fit,
 * and shares one size across both sides. The height is a phone's in landscape
 * (a 914×411dp window leaves about 343dp for each side column).
 */
class SideDockFitTest {
    private val phoneColumnDp = 343

    private fun fit(vararg sides: List<Int>, maxIconSizeDp: Int = DEFAULT_DOCK_APP_ICON_SIZE_DP) =
        sideDockFit(phoneColumnDp, sides.toList(), maxIconSizeDp, DockLayout.IconOnly)

    @Test
    fun aSideShrinksThenWrapsAsAppsArrive() {
        val expected = mapOf(
            1 to SideDockFit(1, 43),
            5 to SideDockFit(1, 43),
            6 to SideDockFit(1, 37),
            7 to SideDockFit(2, 43),
            10 to SideDockFit(2, 43),
            11 to SideDockFit(2, 37),
            12 to SideDockFit(2, 37),
            13 to SideDockFit(2, MIN_DOCK_APP_ICON_SIZE_DP),
            20 to SideDockFit(2, MIN_DOCK_APP_ICON_SIZE_DP),
        )
        expected.forEach { (apps, fit) -> assertEquals("$apps apps", fit, fit(listOf(apps))) }
    }

    @Test
    fun bothSidesShareTheFullerSidesSize() {
        // Six personal apps need 37dp; the five work apps would fit at 43dp but match.
        assertEquals(SideDockFit(1, 37), fit(listOf(6), listOf(5)))
        // Either side wrapping wraps both.
        assertEquals(SideDockFit(2, 43), fit(listOf(7), listOf(2)))
    }

    @Test
    fun iconsNeverGrowPastTheIconsPerRowSize() {
        assertEquals(SideDockFit(1, 36), fit(listOf(2), maxIconSizeDp = 36))
    }

    @Test
    fun aColumnOfTwoCardsCountsBothCardsAndTheGapBetween() {
        // "Right" stacks the personal and work cards in one column: three apps
        // each can't fit one column even at the minimum, so both wrap.
        assertEquals(SideDockFit(2, 43), fit(listOf(3, 3)))
        assertEquals(SideDockFit(1, 43), fit(listOf(2, 2)))
    }

    @Test
    fun anEmptyCardStillTakesARowForItsAddButton() {
        assertEquals(
            sideDockHeightDp(listOf(1), 1, 43, DockLayout.IconOnly),
            sideDockHeightDp(listOf(0), 1, 43, DockLayout.IconOnly),
        )
    }

    @Test
    fun titlesBelowTakeTheirRowHeightIntoAccount() {
        // A title under each icon makes five slots too tall for one column.
        assertEquals(
            SideDockFit(2, 43),
            sideDockFit(phoneColumnDp, listOf(listOf(5)), DEFAULT_DOCK_APP_ICON_SIZE_DP, DockLayout.TitleBelow),
        )
    }

    @Test
    fun aSidesHeightIsItsCardsPaddingRowsAndGaps() {
        // Six 37dp slots (45dp tall) with five 8dp gaps inside 16dp padding.
        assertEquals(16 + 6 * 45 + 5 * 8 + 16, sideDockHeightDp(listOf(6), 1, 37, DockLayout.IconOnly))
        // Two cards and the 8dp gap between them.
        assertEquals(
            2 * (16 + 51 + 16) + 8,
            sideDockHeightDp(listOf(1, 1), 1, 43, DockLayout.IconOnly),
        )
        assertEquals(0, sideDockHeightDp(emptyList(), 1, 43, DockLayout.IconOnly))
    }
}
