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
}
