package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class DockPositionTest {
    @Test
    fun resolveCollapsesEmptyLeadingRow() {
        val resolved = resolvedDockPositions(
            dockedAppIds = listOf("e", "f"),
            persistedPositions = mapOf(
                "e" to DockPosition(1, 0),
                "f" to DockPosition(1, 1),
            ),
            columnCount = 4,
        )

        assertEquals(DockPosition(0, 0), resolved["e"])
        assertEquals(DockPosition(0, 1), resolved["f"])
    }

    @Test
    fun resolveCollapsesEmptyInteriorRow() {
        val resolved = resolvedDockPositions(
            dockedAppIds = listOf("a", "b"),
            persistedPositions = mapOf(
                "a" to DockPosition(0, 0),
                "b" to DockPosition(2, 0),
            ),
            columnCount = 4,
        )

        assertEquals(DockPosition(0, 0), resolved["a"])
        assertEquals(DockPosition(1, 0), resolved["b"])
    }

    @Test
    fun resolvePreservesColumnGapsWithinARow() {
        val resolved = resolvedDockPositions(
            dockedAppIds = listOf("a", "b"),
            persistedPositions = mapOf(
                "a" to DockPosition(1, 0),
                "b" to DockPosition(1, 2),
            ),
            columnCount = 4,
        )

        assertEquals(DockPosition(0, 0), resolved["a"])
        assertEquals(DockPosition(0, 2), resolved["b"])
    }

    @Test
    fun rowCountIgnoresEmptyLeadingRow() {
        val rowCount = dockRowCount(
            dockedAppIds = listOf("e", "f"),
            persistedPositions = mapOf(
                "e" to DockPosition(1, 0),
                "f" to DockPosition(1, 1),
            ),
            columnCount = 4,
        )

        assertEquals(1, rowCount)
    }
}
