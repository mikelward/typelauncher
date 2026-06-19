package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [dockFolderSlots], the pure cell-assignment behind the open
 * folder grid. The members (in display order) plus a trailing close tile are
 * placed into a row-major grid, anchored on the folder's own cell as a 2×2 block,
 * with a top-left packed fallback when the arrangement can't fit the dock.
 */
class DockFolderSlotsTest {
    private fun M(index: Int) = FolderSlot.Member(index)
    private val C = FolderSlot.Close
    private val E = FolderSlot.Empty

    @Test
    fun formsA2x2BlockAroundAnchorOnMultiRowDock() {
        val slots = dockFolderSlots(
            memberCount = 4,
            anchor = DockPosition(0, 1),
            columns = 4,
            rows = 2,
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
    fun singleRowDockLaysOutAsALineAroundAnchor() {
        val slots = dockFolderSlots(
            memberCount = 2,
            anchor = DockPosition(0, 2),
            columns = 5,
            rows = 1,
        )
        // No vertical room for a 2×2 block, so it degrades to the nearest-cell line:
        // M0 on the anchor (col 2), M1 to the right, close to the left.
        assertEquals(listOf(E, C, M(0), M(1), E), slots)
    }

    @Test
    fun overflowFallsBackToPackedAndNeverExceedsRowsByAnchoring() {
        val slots = dockFolderSlots(
            memberCount = 10,
            anchor = DockPosition(0, 1),
            columns = 4,
            rows = 1,
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
        )
        assertEquals(listOf(M(0), M(1), C), slots)
    }

    @Test
    fun alwaysContainsEveryMemberAndExactlyOneCloseRegardlessOfAnchor() {
        for (anchorCol in 0 until 6) {
            val slots = dockFolderSlots(
                memberCount = 5,
                anchor = DockPosition(0, anchorCol),
                columns = 6,
                rows = 2,
            )
            val members = slots.filterIsInstance<FolderSlot.Member>().map { it.index }.sorted()
            assertEquals("members present @$anchorCol", listOf(0, 1, 2, 3, 4), members)
            assertEquals(
                "exactly one close @$anchorCol",
                1,
                slots.count { it is FolderSlot.Close },
            )
            assertTrue("row-major width @$anchorCol", slots.size % 6 == 0)
        }
    }
}
