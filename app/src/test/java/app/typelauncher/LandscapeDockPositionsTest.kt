package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers `landscapeDockPositions` — the render-time transform that flattens a
 * portrait dock grid into a wider single landscape row in reading order (top
 * row first, then the next row appended on the right). The portrait grid below
 * is the 6×2 the design used as the worked example:
 *
 *     a b c d e f
 *     g h i j k l
 *
 * Occupants are ordered left-to-right by the resulting landscape column so the
 * assertions read as the flattened row.
 */
class LandscapeDockPositionsTest {
    private val portraitColumns = 6
    private val sixByTwoIds = listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l")
    private val sixByTwoPositions = mapOf(
        "a" to DockPosition(0, 0), "b" to DockPosition(0, 1), "c" to DockPosition(0, 2),
        "d" to DockPosition(0, 3), "e" to DockPosition(0, 4), "f" to DockPosition(0, 5),
        "g" to DockPosition(1, 0), "h" to DockPosition(1, 1), "i" to DockPosition(1, 2),
        "j" to DockPosition(1, 3), "k" to DockPosition(1, 4), "l" to DockPosition(1, 5),
    )

    /** Occupant ids in landscape order (by resolved row, then column). */
    private fun flattenedOrder(
        ids: List<String>,
        positions: Map<String, DockPosition>,
        landscapeColumns: Int = 12,
    ): List<String> {
        val resolved = landscapeDockPositions(ids, positions, portraitColumns, landscapeColumns)
        return resolved.entries
            .sortedWith(compareBy({ it.value.row }, { it.value.column }))
            .map { it.key }
    }

    @Test
    fun sixByTwoFlattensToReadingOrder() {
        // The user's 6×2: top row, then the bottom row appended on the right.
        assertEquals(
            listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"),
            flattenedOrder(sixByTwoIds, sixByTwoPositions),
        )
    }

    @Test
    fun flattenProducesASingleRowWhenItFits() {
        val resolved = landscapeDockPositions(
            sixByTwoIds, sixByTwoPositions, portraitColumns, landscapeColumnCount = 12,
        )
        assertEquals(0, resolved.values.maxOf { it.row })
        // The flattened row occupies columns 0..11 contiguously.
        assertEquals((0..11).toList(), resolved.values.map { it.column }.sorted())
    }

    @Test
    fun overflowWrapsToASecondRowWhenColumnsAreCapped() {
        // Only 11 columns fit, so the 12th occupant (last in reading order) wraps.
        val resolved = landscapeDockPositions(
            sixByTwoIds, sixByTwoPositions, portraitColumns, landscapeColumnCount = 11,
        )

        assertEquals(DockPosition(0, 10), resolved["k"])
        assertEquals(DockPosition(1, 0), resolved["l"])
    }

    @Test
    fun flattenCompactsPortraitGaps() {
        // A sparse portrait grid — a gap at (0, 4) — compacts away in the
        // flattened row, so the whole point of the reflow (reclaiming
        // horizontal space) holds even for deliberate portrait gaps.
        //     a b c d . f
        //     g h . j k l
        val ids = listOf("a", "b", "c", "d", "f", "g", "h", "j", "k", "l")
        val positions = sixByTwoPositions.filterKeys { it in ids }

        assertEquals(
            listOf("a", "b", "c", "d", "f", "g", "h", "j", "k", "l"),
            flattenedOrder(ids, positions),
        )
        val resolved = landscapeDockPositions(ids, positions, portraitColumns, landscapeColumnCount = 12)
        assertEquals((0..9).toList(), resolved.values.map { it.column }.sorted())
    }

    @Test
    fun flattenResolvesUnpositionedOccupantsLikeTheDockDoes() {
        // Occupants without a persisted position fall into the first open cells
        // (the same `resolvedDockPositions` fallback the dock renders with), so
        // the flatten and the portrait grid agree on where they live.
        val ids = listOf("a", "b", "c", "d", "e", "f", "g", "h")
        val resolved = landscapeDockPositions(ids, emptyMap(), portraitColumns, landscapeColumnCount = 8)

        assertEquals(
            ids,
            resolved.entries.sortedWith(compareBy({ it.value.row }, { it.value.column })).map { it.key },
        )
        assertEquals(0, resolved.values.maxOf { it.row })
    }

    @Test
    fun portraitPositionsAreNeverMutated() {
        // The transform is render-time only: the persisted map passed in is
        // untouched, so rotating back to portrait reproduces the original grid.
        val persisted = sixByTwoPositions.toMutableMap()
        landscapeDockPositions(sixByTwoIds, persisted, portraitColumns, landscapeColumnCount = 12)

        assertEquals(sixByTwoPositions, persisted)
    }

    @Test
    fun columnCountUsesTheBusiestVisibleDock() {
        // Personal (5) is busier than work (3); both visible -> 5 columns.
        assertEquals(
            5,
            landscapeDockColumnCount(
                isPersonalDockEnabled = true,
                personalDockOccupantCount = 5,
                isWorkDockVisible = true,
                workDockOccupantCount = 3,
                dockIconCount = 4,
                landscapeFitColumns = 12,
            ),
        )
    }

    @Test
    fun columnCountIgnoresAHiddenPersonalDock() {
        // Show dock off but work dock visible: the saved personal pins must not
        // widen the work-only dock into a row of empty slots. Work has 3 apps,
        // which is below the portrait per-row count, so it floors at dockIconCount.
        assertEquals(
            4,
            landscapeDockColumnCount(
                isPersonalDockEnabled = false,
                personalDockOccupantCount = 9,
                isWorkDockVisible = true,
                workDockOccupantCount = 3,
                dockIconCount = 4,
                landscapeFitColumns = 12,
            ),
        )
    }

    @Test
    fun columnCountClampsToTheLandscapeFit() {
        // More occupants than fit the landscape width -> capped to the fit (the
        // overflow wraps to a second row instead of shrinking the icons).
        assertEquals(
            12,
            landscapeDockColumnCount(
                isPersonalDockEnabled = true,
                personalDockOccupantCount = 20,
                isWorkDockVisible = false,
                workDockOccupantCount = 0,
                dockIconCount = 4,
                landscapeFitColumns = 12,
            ),
        )
    }
}
