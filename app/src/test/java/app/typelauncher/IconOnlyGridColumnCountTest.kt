package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // Regression: at v403 the dock's `FlowRow` wrapped the 6th icon to a
    // second row at 411dp/420dpi even though the dp math said six fit.
    // The fix: dock items use `Modifier.weight(1f)` inside the FlowRow.
    // `weight` makes Compose distribute the row's actual pixel width
    // proportionally across the N slots — each item receives
    // `floor((rowWidthPx - (N-1)*spacingPx) / N)` px, so the total never
    // exceeds `rowWidthPx`. Row overflow is impossible by construction.
    //
    // This test verifies the complementary property: the icon + padding
    // fits within the weight-allocated cell width. The dp formula guarantees
    // `iconSizeDp + 8 == cellWidthDp` (exact integer equality), so the px
    // rounding difference is at most 1 px in either direction.
    @Test
    fun dockFlowRow_iconFitsInWeightAllocatedCell_acrossDensities() {
        val itemSidePaddingDp = 4
        val gridHorizontalInsetDp = 64
        val standardDensities = floatArrayOf(1.0f, 1.5f, 2.0f, 2.25f, 2.5f, 2.625f, 2.75f, 3.0f, 3.5f, 4.0f)

        for (screenWidthDp in 360..500) {
            for (slotCount in dockSlotCountRange(screenWidthDp)) {
                val iconSizeDp = dockIconSizeForSlotCount(screenWidthDp, slotCount)
                val rowWidthDp = screenWidthDp - gridHorizontalInsetDp

                for (density in standardDensities) {
                    val spacingPx = roundToPx(8f, density)
                    val rowWidthPx = roundToPx(rowWidthDp.toFloat(), density)
                    // Compose allocates this many px to each weighted slot.
                    val cellPx = (rowWidthPx - (slotCount - 1) * spacingPx) / slotCount
                    val iconItemPx = roundToPx((iconSizeDp + 2 * itemSidePaddingDp).toFloat(), density)

                    // The icon+padding may be at most 1 px wider than the
                    // cell due to independent rounding of cell vs. item dp
                    // values; Compose constrains the item to the cell width
                    // in that case (imperceptible sub-dp clip on one edge).
                    assertTrue(
                        "Expected icon+padding ($iconItemPx px) to fit in weight-allocated " +
                            "cell ($cellPx px) at ${screenWidthDp}dp / density=$density " +
                            "(iconSize=${iconSizeDp}dp, rowWidthPx=$rowWidthPx, spacingPx=$spacingPx)",
                        iconItemPx <= cellPx + 1,
                    )
                }
            }
        }
    }

    // Mirrors `androidx.compose.ui.unit.Density.roundToPx(Dp)`:
    // `(value * density).fastRoundToInt()` where `fastRoundToInt` for
    // positive values is `(this + 0.5f).toInt()` (truncating half-up).
    private fun roundToPx(valueDp: Float, density: Float): Int =
        (valueDp * density + 0.5f).toInt()
}
