package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the "Icons per row" slider range that the icon-size bounds produce.
 * The slider runs from the fewest-per-row count (largest icons, capped by
 * [MAX_DOCK_APP_ICON_SIZE_DP]) to the most-per-row count (smallest icons,
 * floored by [MIN_DOCK_APP_ICON_SIZE_DP]); both ends are derived per screen
 * width by [dockSlotCountRange] / [dockIconSizeForSlotCount].
 */
class DockIconDensityRangeTest {

    // The standard Pixel width (see DEFAULT_DOCK_SCREEN_WIDTH_DP): a Pixel at
    // its default display size reports 411dp. A 36dp floor only reached 7 per
    // row from ~420dp up, so this width topped out at 6; the 32dp floor opens
    // up the 7-per-row stop here too.
    private val standardPixelWidthDp = 411

    @Test
    fun denseEnd_allowsSevenPerRowOnAStandardPixel() {
        val range = dockSlotCountRange(standardPixelWidthDp)
        assertTrue(
            "Expected 7 per row to be reachable on a ${standardPixelWidthDp}dp screen, got $range",
            range.last >= 7,
        )
        // The densest stop renders at an unclamped 34dp icon — above the floor,
        // not chopped down to it.
        val denseIconSize = dockIconSizeForSlotCount(standardPixelWidthDp, 7)
        assertEquals(34, denseIconSize)
        assertTrue(
            "Densest icon $denseIconSize should sit above the ${MIN_DOCK_APP_ICON_SIZE_DP}dp floor",
            denseIconSize > MIN_DOCK_APP_ICON_SIZE_DP,
        )
    }

    @Test
    fun sparseEnd_runsFourToSevenOnAStandardPixel() {
        // The 72dp ceiling drops the sparsest 3-per-row stop on a 411dp phone
        // (its icons clamped against the old 76dp cap anyway), so the slider
        // runs a clean 4..7 there. The largest stop is a row of four at the
        // 72dp ceiling.
        val range = dockSlotCountRange(standardPixelWidthDp)
        assertEquals(4, range.first)
        assertEquals(7, range.last)
        assertEquals(
            MAX_DOCK_APP_ICON_SIZE_DP,
            dockIconSizeForSlotCount(standardPixelWidthDp, range.first),
        )
    }

    @Test
    fun everyStop_staysWithinTheIconSizeBounds_acrossPhoneWidths() {
        for (screenWidthDp in 320..540) {
            val range = dockSlotCountRange(screenWidthDp)
            assertTrue(range.first in 1..range.last)
            for (slotCount in range) {
                val iconSizeDp = dockIconSizeForSlotCount(screenWidthDp, slotCount)
                assertTrue(
                    "iconSize $iconSizeDp out of bounds at ${screenWidthDp}dp / $slotCount per row",
                    iconSizeDp in MIN_DOCK_APP_ICON_SIZE_DP..MAX_DOCK_APP_ICON_SIZE_DP,
                )
            }
        }
    }

    @Test
    fun range_neverAdvertisesAStopAboveTheCountCap_acrossWideWindows() {
        // The slider's persisted value is coerced to MAX_DOCK_ICON_COUNT, so
        // its range must never offer a denser stop. Without clamping, the 36dp
        // floor lets a wide window exceed the cap (524dp -> 9 per row), giving
        // a stop that snaps back to 8 on release.
        for (screenWidthDp in 320..900) {
            val range = dockSlotCountRange(screenWidthDp)
            assertTrue(
                "Range $range exceeds count cap at ${screenWidthDp}dp",
                range.last <= MAX_DOCK_ICON_COUNT,
            )
            assertTrue(
                "Range $range drops below count floor at ${screenWidthDp}dp",
                range.first >= MIN_DOCK_ICON_COUNT,
            )
            assertTrue("Range $range is empty at ${screenWidthDp}dp", range.first <= range.last)
        }
    }

    @Test
    fun range_cappedAtEightPerRow_onA524dpWindow() {
        // Regression: the densest stop on this width was an unreachable 9.
        val range = dockSlotCountRange(524)
        assertEquals(MAX_DOCK_ICON_COUNT, range.last)
    }
}
