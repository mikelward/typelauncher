package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [dockFolderSlots], the pure cell-assignment behind every
 * folder open style. Each style places the members (in display order) plus a
 * trailing close tile into a row-major grid; these assert the placement is what
 * each style promises and that the "never grow the dock" fallback packs.
 */
class DockFolderSlotsTest {
    private fun M(index: Int) = FolderSlot.Member(index)
    private val C = FolderSlot.Close
    private val E = FolderSlot.Empty

    @Test
    fun topLeftPacksFromStartWithTrailingEmpties() {
        val slots = dockFolderSlots(
            memberCount = 3,
            anchor = DockPosition(0, 2),
            columns = 4,
            rows = 1,
            style = DockFolderOpenStyle.TopLeft,
        )
        assertEquals(listOf(M(0), M(1), M(2), C), slots)
    }

    @Test
    fun directionalLeftHalfFlowsRightFromAnchorColumn() {
        val slots = dockFolderSlots(
            memberCount = 3,
            anchor = DockPosition(0, 1),
            columns = 6,
            rows = 1,
            style = DockFolderOpenStyle.Directional,
        )
        // Leading empty cell, then first member under the anchor (col 1) flowing
        // right, close at the far end, trailing empty.
        assertEquals(listOf(E, M(0), M(1), M(2), C, E), slots)
    }

    @Test
    fun directionalRightHalfFlowsLeftFromAnchorColumn() {
        val slots = dockFolderSlots(
            memberCount = 3,
            anchor = DockPosition(0, 4),
            columns = 6,
            rows = 1,
            style = DockFolderOpenStyle.Directional,
        )
        // First member under the anchor (col 4), flowing left; close furthest left.
        assertEquals(listOf(E, C, M(2), M(1), M(0), E), slots)
        assertEquals(M(0), slots[4]) // member 0 sits on the folder's column
    }

    @Test
    fun bloomPutsFirstMemberAtAnchorThenNearestCells() {
        val slots = dockFolderSlots(
            memberCount = 2,
            anchor = DockPosition(0, 2),
            columns = 5,
            rows = 1,
            style = DockFolderOpenStyle.Bloom,
        )
        // M0 on the anchor (col 2), M1 to the right (nearer cell first), close left.
        assertEquals(listOf(E, C, M(0), M(1), E), slots)
    }

    @Test
    fun zoomFormsA2x2BlockAroundAnchorOnMultiRowDock() {
        val slots = dockFolderSlots(
            memberCount = 4,
            anchor = DockPosition(0, 1),
            columns = 4,
            rows = 2,
            style = DockFolderOpenStyle.Zoom,
        )
        // Members fill the 2×2 block at cols 1–2 across both rows; close takes the
        // nearest remaining cell (top-left).
        assertEquals(
            listOf(
                C, M(0), M(1), E,
                E, M(2), M(3), E,
            ),
            slots,
        )
    }

    @Test
    fun overflowFallsBackToPackedAndNeverExceedsRowsByAnchoring() {
        val slots = dockFolderSlots(
            memberCount = 10,
            anchor = DockPosition(0, 1),
            columns = 4,
            rows = 1,
            style = DockFolderOpenStyle.Bloom,
        )
        // 11 tiles can't fit a 1×4 footprint, so pack (members then close) and let
        // the caller scroll — no leading-cell offset that would push extra rows.
        assertEquals(
            listOf(M(0), M(1), M(2), M(3), M(4), M(5), M(6), M(7), M(8), M(9), C, E),
            slots,
        )
    }

    @Test
    fun singleColumnAlwaysPacks() {
        val slots = dockFolderSlots(
            memberCount = 2,
            anchor = DockPosition(2, 0),
            columns = 1,
            rows = 4,
            style = DockFolderOpenStyle.Directional,
        )
        assertEquals(listOf(M(0), M(1), C), slots)
    }

    @Test
    fun overlayStyleUsesPackedGridSinceItRendersItsOwnCard() {
        val slots = dockFolderSlots(
            memberCount = 2,
            anchor = DockPosition(0, 4),
            columns = 6,
            rows = 1,
            style = DockFolderOpenStyle.Overlay,
        )
        // Packed (members then close), padded with empties to the row width.
        assertEquals(listOf(M(0), M(1), C, E, E, E), slots)
    }

    @Test
    fun anchoredStylesAlwaysContainEveryMemberAndOneClose() {
        for (style in DockFolderOpenStyle.values()) {
            for (anchorCol in 0 until 6) {
                val slots = dockFolderSlots(
                    memberCount = 5,
                    anchor = DockPosition(0, anchorCol),
                    columns = 6,
                    rows = 2,
                    style = style,
                )
                val members = slots.filterIsInstance<FolderSlot.Member>().map { it.index }.sorted()
                assertEquals("members present for $style@$anchorCol", listOf(0, 1, 2, 3, 4), members)
                assertEquals(
                    "exactly one close for $style@$anchorCol",
                    1,
                    slots.count { it is FolderSlot.Close },
                )
                assertTrue("row-major width for $style@$anchorCol", slots.size % 6 == 0)
            }
        }
    }
}
