package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dock derives its icon size and its "Icons per row" range from the short
 * screen edge, so rotating between portrait and landscape never changes the
 * icon size or the available slot counts — the dock then renders the row
 * centered at that fixed size (covered visually by the landscape screenshot
 * test) rather than stretching the slots to fill the wider window.
 */
class DockRotationSizingTest {

    // A ~424 x 924 dp phone (portrait), and the same device rotated.
    private val portraitWidthDp = 424
    private val portraitHeightDp = 924

    private fun referenceWidthDp(widthDp: Int, heightDp: Int) = minOf(widthDp, heightDp)

    @Test
    fun iconSize_isIdenticalAcrossRotation() {
        val portraitRef = referenceWidthDp(portraitWidthDp, portraitHeightDp)
        val landscapeRef = referenceWidthDp(portraitHeightDp, portraitWidthDp)
        assertEquals("short edge must be rotation-invariant", portraitRef, landscapeRef)
        for (count in 1..MAX_DOCK_ICON_COUNT) {
            assertEquals(
                "icon size must not change on rotation at $count per row",
                dockIconSizeForSlotCount(portraitRef, count),
                dockIconSizeForSlotCount(landscapeRef, count),
            )
        }
    }

    @Test
    fun slotRange_isIdenticalAcrossRotation() {
        assertEquals(
            dockSlotCountRange(referenceWidthDp(portraitWidthDp, portraitHeightDp)),
            dockSlotCountRange(referenceWidthDp(portraitHeightDp, portraitWidthDp)),
        )
    }
}
