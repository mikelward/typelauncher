package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [aroundTapFolderSlots]: the close tile lands where the folder was tapped and
 * the apps fill a block of rows around it, in rank order.
 */
class AroundTapFolderSlotsTest {
    private fun M(index: Int) = FolderSlot.Member(index)
    private val C = FolderSlot.Close
    private val E = FolderSlot.Empty

    @Test
    fun closeTakesTheTappedCellAndAppsFillTheRowsAroundIt() {
        // 5 apps + close need 2 rows of 4; tapped row 2 of 5 → block is rows 1-2.
        val slots = aroundTapFolderSlots(
            memberCount = 5, columns = 4, visibleRows = 5,
            anchorRow = 2, anchorColumn = 1, reversed = false,
        )
        assertEquals(
            listOf(
                E, E, E, E,
                M(0), M(1), M(2), M(3),
                M(4), C, E, E,
                E, E, E, E,
                E, E, E, E,
            ),
            slots,
        )
    }

    @Test
    fun reversedSortPutsRankOneAtTheBlocksBottomLeft() {
        val slots = aroundTapFolderSlots(
            memberCount = 5, columns = 4, visibleRows = 5,
            anchorRow = 2, anchorColumn = 1, reversed = true,
        )
        assertEquals(
            listOf(
                E, E, E, E,
                M(3), M(4), E, E,
                M(0), C, M(1), M(2),
                E, E, E, E,
                E, E, E, E,
            ),
            slots,
        )
    }

    @Test
    fun blockSlidesUpWhenTappedOnTheBottomRow() {
        // 3 rows needed, tapped on the last of 4: the block ends there.
        val slots = aroundTapFolderSlots(
            memberCount = 8, columns = 3, visibleRows = 4,
            anchorRow = 3, anchorColumn = 2, reversed = false,
        )
        assertEquals(
            listOf(
                E, E, E,
                M(0), M(1), M(2),
                M(3), M(4), M(5),
                M(6), M(7), C,
            ),
            slots,
        )
    }

    @Test
    fun blockSlidesDownWhenTappedOnTheTopRow() {
        val slots = aroundTapFolderSlots(
            memberCount = 8, columns = 3, visibleRows = 4,
            anchorRow = 0, anchorColumn = 0, reversed = false,
        )
        assertEquals(
            listOf(
                C, M(0), M(1),
                M(2), M(3), M(4),
                M(5), M(6), M(7),
                E, E, E,
            ),
            slots,
        )
    }

    @Test
    fun oneRowPutsAppsEitherSideOfTheClose() {
        val slots = aroundTapFolderSlots(
            memberCount = 3, columns = 5, visibleRows = 1,
            anchorRow = 0, anchorColumn = 2, reversed = false,
        )
        assertEquals(listOf(M(0), M(1), C, M(2), E), slots)
    }

    @Test
    fun overflowKeepsTheTappedRowAndAddsRowsBelow() {
        // 2-row dock, 9 apps + close need 3 rows of 4; tapped bottom row.
        val slots = aroundTapFolderSlots(
            memberCount = 9, columns = 4, visibleRows = 2,
            anchorRow = 1, anchorColumn = 2, reversed = false,
        )
        assertEquals(
            listOf(
                M(0), M(1), M(2), M(3),
                M(4), M(5), C, M(6),
                M(7), M(8), E, E,
            ),
            slots,
        )
        assertFalse(folderOpensScrolledToBottom(slots.size, columns = 4, visibleRows = 2, reversed = false))
    }

    @Test
    fun reversedOverflowAddsRowsAboveAndStartsAtTheBottom() {
        val slots = aroundTapFolderSlots(
            memberCount = 9, columns = 4, visibleRows = 2,
            anchorRow = 0, anchorColumn = 2, reversed = true,
        )
        // Scrolled to the bottom, rows 1-2 show; the tapped (top) row is row 1.
        assertEquals(
            listOf(
                M(7), M(8), E, E,
                M(4), M(5), C, M(6),
                M(0), M(1), M(2), M(3),
            ),
            slots,
        )
        assertTrue(folderOpensScrolledToBottom(slots.size, columns = 4, visibleRows = 2, reversed = true))
    }

    @Test
    fun outOfRangeAnchorIsClamped() {
        val slots = aroundTapFolderSlots(
            memberCount = 1, columns = 2, visibleRows = 2,
            anchorRow = 9, anchorColumn = 9, reversed = false,
        )
        assertEquals(listOf(E, E, M(0), C), slots)
    }

    @Test
    fun alwaysHoldsEveryAppAndOneClose() {
        for (columns in 1..5) for (rows in 1..4) for (count in 0..12) for (reversed in listOf(false, true)) {
            val slots = aroundTapFolderSlots(count, columns, rows, rows - 1, columns - 1, reversed)
            val where = "cols=$columns rows=$rows count=$count reversed=$reversed"
            assertEquals(where, (0 until count).toList(), slots.filterIsInstance<FolderSlot.Member>().map { it.index }.sorted())
            assertEquals(where, 1, slots.count { it == C })
            assertEquals(where, 0, slots.size % columns)
            assertTrue(where, slots.size / columns >= rows)
        }
    }

    @Test
    fun dockFolderPacksUnlessOpeningAroundAKnownCell() {
        val packed = dockFolderSlots(memberCount = 2, columns = 3)
        assertEquals(
            packed,
            dockFolderOpenSlots(2, 3, FolderOpenStyle.InOrder, DockPosition(0, 2), visibleRows = 1, isSortReversed = false),
        )
        assertEquals(
            packed,
            dockFolderOpenSlots(2, 3, FolderOpenStyle.AroundTap, tapped = null, visibleRows = 1, isSortReversed = false),
        )
        assertEquals(
            listOf(M(0), M(1), C),
            dockFolderOpenSlots(2, 3, FolderOpenStyle.AroundTap, DockPosition(0, 2), visibleRows = 1, isSortReversed = false),
        )
    }

    @Test
    fun scrolledDock_anchorsOnTheRowsOnScreen() {
        // 4 rows 100px apart, capped to a 200px window scrolled to the bottom:
        // rows 2-3 show, so a folder on row 3 opens on the second visible row
        // (Codex on PR #883).
        assertEquals(2 to 2, dockVisibleRows(rowCount = 4, scrollPx = 200, maxScrollPx = 200, viewportPx = 200, pitchPx = 100f))
        val slots = dockFolderOpenSlots(1, 3, FolderOpenStyle.AroundTap, DockPosition(3 - 2, 0), visibleRows = 2, isSortReversed = false)
        assertEquals(listOf(E, E, E, C, M(0), E), slots)
        // An unscrollable dock shows every row.
        assertEquals(0 to 3, dockVisibleRows(rowCount = 3, scrollPx = 0, maxScrollPx = 0, viewportPx = 300, pitchPx = 100f))
    }
}
