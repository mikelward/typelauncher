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

    // A ~424dp-wide phone (e.g. Pixel 10 at default display size): the width
    // where 4 icons per row lands on an exact, unclamped 76dp — the case that
    // motivated trimming the old 80dp step and adding the dense 36dp step.
    private val pixel10WidthDp = 424

    @Test
    fun denseEnd_allowsSevenPerRowAtTheFloor() {
        // Lowering the floor to 36dp opens up a 7-per-row stop on this phone.
        val range = dockSlotCountRange(pixel10WidthDp)
        assertTrue(
            "Expected 7 per row to be reachable on a ${pixel10WidthDp}dp screen, got $range",
            range.last >= 7,
        )
        // The densest stop renders at the 36dp floor, not below it.
        assertEquals(
            MIN_DOCK_APP_ICON_SIZE_DP,
            dockIconSizeForSlotCount(pixel10WidthDp, 7),
        )
    }

    @Test
    fun sparseEnd_dropsTheClampedGiantStep() {
        // The old 80dp cap exposed a 3-per-row stop whose natural size
        // overflowed and clamped to 80dp. With the 76dp cap the sparse end
        // starts at 4 per row instead, and that stop is an exact, unclamped
        // 76dp (no size is chopped down to the ceiling).
        val range = dockSlotCountRange(pixel10WidthDp)
        assertEquals(4, range.first)
        assertEquals(76, dockIconSizeForSlotCount(pixel10WidthDp, 4))
        assertTrue(
            "Largest stop should be the unclamped natural size, never the ceiling",
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
