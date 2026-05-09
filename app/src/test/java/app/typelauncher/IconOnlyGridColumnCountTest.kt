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
    // second row at 411dp/420dpi (and similar configurations) even though
    // the dp math said six fit. Each dock item used to be rendered as
    // `padding(4.dp) + AppIcon(iconSize.dp)`, which produces three
    // independent `Density.roundToPx` calls (left padding, icon size, right
    // padding). At density 2.625 that turns 51 dp logical into
    // 11 + 113 + 11 = 135 px per item — 1 px more than rounding
    // `(iconSize+8).dp` once would give — and with six items the accumulated
    // overrun exceeds the row width and the row wraps.
    //
    // The fix: each dock item is now a single `size((iconSize+8).dp)` box,
    // so its width is one `roundToPx` call and accumulation is eliminated.
    // This test verifies the single-call model fits at every density.
    @Test
    fun dockFlowRow_fitsRequestedSlotCountInOneRow_acrossDensities() {
        val itemSidePaddingDp = 4
        val gridHorizontalInsetDp = 64
        val standardDensities = floatArrayOf(1.0f, 1.5f, 2.0f, 2.25f, 2.5f, 2.625f, 2.75f, 3.0f, 3.5f, 4.0f)

        for (screenWidthDp in 360..500) {
            for (slotCount in dockSlotCountRange(screenWidthDp)) {
                val iconSizeDp = dockIconSizeForSlotCount(screenWidthDp, slotCount)
                val rowWidthDp = screenWidthDp - gridHorizontalInsetDp

                for (density in standardDensities) {
                    val itemPx = roundToPx((iconSizeDp + 2 * itemSidePaddingDp).toFloat(), density)
                    val spacingPx = roundToPx(8f, density)
                    val rowWidthPx = roundToPx(rowWidthDp.toFloat(), density)
                    val usedPx = slotCount * itemPx + (slotCount - 1) * spacingPx

                    assertTrue(
                        "Expected $slotCount dock items to fit in one row at " +
                            "${screenWidthDp}dp / density=$density (iconSize=${iconSizeDp}dp, " +
                            "itemPx=$itemPx, spacingPx=$spacingPx, rowWidthPx=$rowWidthPx, usedPx=$usedPx)",
                        usedPx <= rowWidthPx,
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
