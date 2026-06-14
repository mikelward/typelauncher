package app.typelauncher

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for the icon-only grid's cross-axis mirror. Under a reversed
 * sort the grid flips the vertical axis so the highest-rank app lands on the
 * bottom row; the cross-axis flip added here moves it to the bottom-*right* so
 * the grid reads in the same order as the dock. Forward sorts must be left
 * untouched, and the mirror is relative to the base direction so RTL locales
 * flip the opposite way.
 */
class IconGridLayoutDirectionTest {

    @Test
    fun forwardSort_keepsBaseDirection() {
        assertEquals(
            LayoutDirection.Ltr,
            iconGridCrossAxisLayoutDirection(LayoutDirection.Ltr, reverseLayout = false),
        )
        assertEquals(
            LayoutDirection.Rtl,
            iconGridCrossAxisLayoutDirection(LayoutDirection.Rtl, reverseLayout = false),
        )
    }

    @Test
    fun reversedSort_mirrorsLtrToRtl_soFirstItemFillsFromTheRight() {
        assertEquals(
            LayoutDirection.Rtl,
            iconGridCrossAxisLayoutDirection(LayoutDirection.Ltr, reverseLayout = true),
        )
    }

    @Test
    fun reversedSort_mirrorsRtlToLtr_soTheFlipIsRelativeToTheLocale() {
        assertEquals(
            LayoutDirection.Ltr,
            iconGridCrossAxisLayoutDirection(LayoutDirection.Rtl, reverseLayout = true),
        )
    }
}
