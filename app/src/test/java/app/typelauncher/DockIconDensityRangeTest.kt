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

    // A ~424dp-wide phone (e.g. Pixel 10 at default display size).
    private val pixel10WidthDp = 424

    @Test
    fun denseEnd_allowsSevenPerRow() {
        // The 32dp floor opens up a 7-per-row stop on this phone.
        val range = dockSlotCountRange(pixel10WidthDp)
        assertTrue(
            "Expected 7 per row to be reachable on a ${pixel10WidthDp}dp screen, got $range",
            range.last >= 7,
        )
        // The densest stop never renders below the 32dp floor.
        assertTrue(
            dockIconSizeForSlotCount(pixel10WidthDp, range.last) >= MIN_DOCK_APP_ICON_SIZE_DP,
        )
    }

    @Test
    fun denseEnd_allowsSevenPerRow_onA411dpPixel10() {
        // The reported case: at this width 6 per row renders ~43dp, and the old
        // 36dp floor capped the slider at 6. The 32dp floor adds a 7th stop.
        val range = dockSlotCountRange(411)
        assertTrue(
            "Expected 7 per row to be reachable on a 411dp screen, got $range",
            range.last >= 7,
        )
    }

    @Test
    fun sparseEnd_capsTheLargestIconAtTheCeiling() {
        // The fewest-per-row stop on this phone is 4, and its icon never
        // exceeds the 72dp ceiling.
        val range = dockSlotCountRange(pixel10WidthDp)
        assertEquals(4, range.first)
        assertTrue(
            "Largest stop must not exceed the ceiling",
            dockIconSizeForSlotCount(pixel10WidthDp, range.first) <= MAX_DOCK_APP_ICON_SIZE_DP,
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
