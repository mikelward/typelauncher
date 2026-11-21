package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [dockFolderSlots], the pure cell-assignment behind the open
 * folder grid. Members pack from the top-left in rank order (the dock's own
 * row-major fill), the close tile follows last, and the final row is padded with
 * empty cells so every tile keeps its `1 / columns` width.
 */
class DockFolderSlotsTest {
    private fun M(index: Int) = FolderSlot.Member(index)
    private val C = FolderSlot.Close
    private val E = FolderSlot.Empty

    @Test
    fun packsMembersThenCloseFromTopLeft() {
        val slots = dockFolderSlots(memberCount = 4, columns = 4)
        // Members pack top-left in rank order (the dock's own fill), the close tile
        // follows last, padded to a whole row.
        assertEquals(
            listOf(
                M(0), M(1), M(2), M(3),
                C, E, E, E,
            ),
            slots,
        )
    }

    @Test
    fun singleRowIsAPlainLineMembersThenClose() {
        val slots = dockFolderSlots(memberCount = 3, columns = 5)
        assertEquals(listOf(M(0), M(1), M(2), C, E), slots)
    }

    @Test
    fun exactFitNeedsNoPadding() {
        val slots = dockFolderSlots(memberCount = 3, columns = 4)
        assertEquals(listOf(M(0), M(1), M(2), C), slots)
    }

    @Test
    fun overflowPacksAndScrolls() {
        val slots = dockFolderSlots(memberCount = 10, columns = 4)
        // 11 tiles (10 members + close) pad to a whole 12-cell last row; the
        // caller's scroll absorbs the overflow past the dock footprint.
        assertEquals(
            listOf(M(0), M(1), M(2), M(3), M(4), M(5), M(6), M(7), M(8), M(9), C, E),
            slots,
        )
    }

    @Test
    fun singleColumnStacks() {
        val slots = dockFolderSlots(memberCount = 2, columns = 1)
        assertEquals(listOf(M(0), M(1), C), slots)
    }

    @Test
    fun alwaysContainsEveryMemberAndExactlyOneClose() {
        for (columns in 1..6) {
            val slots = dockFolderSlots(memberCount = 5, columns = columns)
            val members = slots.filterIsInstance<FolderSlot.Member>().map { it.index }.sorted()
            assertEquals("members present @$columns", listOf(0, 1, 2, 3, 4), members)
            assertEquals(
                "exactly one close @$columns",
                1,
                slots.count { it is FolderSlot.Close },
            )
            assertTrue("row-major width @$columns", slots.size % columns == 0)
        }
    }

    @Test
    fun reversedSort_fillsFromTheBottomLikeTheAppList() {
        // Close bottom-left, rank 1 beside it, rows growing up, with an empty
        // row above so a 2-row dock holds a 1-row folder at the bottom.
        assertEquals(
            listOf(
                E, E, E, E,
                C, M(0), M(1), E,
            ),
            dockFolderSlots(memberCount = 2, columns = 4, visibleRows = 2, reversed = true),
        )
        assertEquals(
            listOf(
                M(3), M(4), M(5), E,
                C, M(0), M(1), M(2),
            ),
            dockFolderSlots(memberCount = 6, columns = 4, visibleRows = 2, reversed = true),
        )
    }

    @Test
    fun reversedSort_tallerThanTheDock_addsRowsAbove() {
        // 7 apps + close in 3 columns need 3 rows; the dock shows 1, and the
        // extra rows go above (the folder opens scrolled to the bottom).
        assertEquals(
            listOf(
                M(5), M(6), E,
                M(2), M(3), M(4),
                C, M(0), M(1),
            ),
            dockFolderSlots(memberCount = 7, columns = 3, visibleRows = 1, reversed = true),
        )
    }

    @Test
    fun scrollingDock_padsOnlyToTheRowsOnScreen() {
        // A 4-row dock capped to a 2-row window: a reversed 1-row folder gets
        // one empty row above, not three (Codex on PR #884).
        val visible = dockVisibleRowCount(rowCount = 4, maxScrollPx = 200, viewportPx = 200, pitchPx = 100f)
        assertEquals(2, visible)
        assertEquals(
            listOf(E, E, E, C, M(0), E),
            dockFolderSlots(memberCount = 1, columns = 3, visibleRows = visible, reversed = true),
        )
        // A dock that doesn't scroll shows every row.
        assertEquals(3, dockVisibleRowCount(rowCount = 3, maxScrollPx = 0, viewportPx = 300, pitchPx = 100f))
    }
}
