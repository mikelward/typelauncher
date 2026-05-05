package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class IconOnlyGridColumnCountTest {

    // Simulates GridCells.Adaptive column count: floor((available + spacing) / (minCellSize + spacing))
    private fun adaptiveColumnCount(availableDp: Int, minCellSizeDp: Int, spacingDp: Int = 8): Int =
        ((availableDp + spacingDp) / (minCellSizeDp + spacingDp)).coerceAtLeast(1)

    @Test
    fun iconOnlyGrid_columnCountMatchesDockSlotCount_acrossScreenWidths() {
        // The grid uses GridCells.Adaptive((iconSizeDp + 8).dp) where +8 accounts for the
        // 4dp padding on each side of IconOnlyAppButton. Without the +8, devices with
        // certain screen widths (e.g. Pixel 9 Pro ~427dp) produce one extra column.
        val iconButtonPaddingBothSides = 8 // 4dp * 2
        val gridHorizontalInsetDp = 64 // 16dp home padding * 2 + 16dp card padding * 2

        for (screenWidthDp in 360..500) {
            for (slotCount in 1..8) {
                val iconSizeDp = dockIconSizeForSlotCount(screenWidthDp, slotCount)
                val gridAvailable = screenWidthDp - gridHorizontalInsetDp
                val minCellSize = iconSizeDp + iconButtonPaddingBothSides

                val columns = adaptiveColumnCount(gridAvailable, minCellSize)

                // The grid must show exactly as many columns as the user requested via slotCount,
                // unless the icon size was clamped (too many or too few slots for the screen).
                val clampedSlotCount = slotCount.coerceIn(dockSlotCountRange(screenWidthDp))
                if (slotCount == clampedSlotCount) {
                    assertEquals(
                        "Expected $slotCount columns on ${screenWidthDp}dp screen " +
                            "(iconSize=$iconSizeDp, gridAvailable=$gridAvailable)",
                        slotCount,
                        columns,
                    )
                }
            }
        }
    }

    @Test
    fun iconOnlyGrid_pixel9Pro_6slotsGives6columns() {
        // Regression: Pixel 9 Pro (~427dp wide) was showing 7 icons per row when set to 6.
        val screenWidthDp = 427
        val slotCount = 6
        val iconSizeDp = dockIconSizeForSlotCount(screenWidthDp, slotCount)
        val gridAvailable = screenWidthDp - 64
        val columns = adaptiveColumnCount(gridAvailable, iconSizeDp + 8)
        assertEquals(6, columns)
    }
}
