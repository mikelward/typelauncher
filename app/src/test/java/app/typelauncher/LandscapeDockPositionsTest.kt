package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers `landscapeDockPositions` / `landscapeDockFlattenOrder` — the
 * render-time transform that flattens a portrait dock grid into a wider
 * landscape row. The portrait grid below is the 6×2 the design used as the
 * worked example:
 *
 *     a b c d e f
 *     g h i j k l
 *
 * Apps are ordered left-to-right by the resulting landscape column so the
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

    /** App ids in landscape reading order (by resolved row, then column). */
    private fun flattenedOrder(
        ids: List<String>,
        positions: Map<String, DockPosition>,
        mode: LandscapeDockMode,
        landscapeColumns: Int = 12,
    ): List<String> {
        val resolved = landscapeDockPositions(ids, positions, portraitColumns, landscapeColumns, mode)
        return resolved.entries
            .sortedWith(compareBy({ it.value.row }, { it.value.column }))
            .map { it.key }
    }

    @Test
    fun sameModeReturnsPortraitPositionsUntouched() {
        val resolved = landscapeDockPositions(
            sixByTwoIds, sixByTwoPositions, portraitColumns, landscapeColumnCount = 12,
            mode = LandscapeDockMode.Same,
        )

        assertEquals(sixByTwoPositions, resolved)
    }

    @Test
    fun readingModeAppendsBottomRowAfterTopRow() {
        assertEquals(
            listOf("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"),
            flattenedOrder(sixByTwoIds, sixByTwoPositions, LandscapeDockMode.Reading),
        )
    }

    @Test
    fun zipModeInterleavesEachColumnsPair() {
        assertEquals(
            listOf("a", "g", "b", "h", "c", "i", "d", "j", "e", "k", "f", "l"),
            flattenedOrder(sixByTwoIds, sixByTwoPositions, LandscapeDockMode.Zip),
        )
    }

    @Test
    fun splitModeCentersBottomRowBetweenTopRowHalves() {
        // top-left half (a b c), then the whole bottom row, then top-right half (d e f).
        assertEquals(
            listOf("a", "b", "c", "g", "h", "i", "j", "k", "l", "d", "e", "f"),
            flattenedOrder(sixByTwoIds, sixByTwoPositions, LandscapeDockMode.Split),
        )
    }

    @Test
    fun flattenedModesProduceASingleRowWhenItFits() {
        for (mode in listOf(LandscapeDockMode.Zip, LandscapeDockMode.Reading, LandscapeDockMode.Split)) {
            val resolved = landscapeDockPositions(
                sixByTwoIds, sixByTwoPositions, portraitColumns, landscapeColumnCount = 12, mode,
            )
            assertEquals("mode=$mode", 0, resolved.values.maxOf { it.row })
        }
    }

    @Test
    fun overflowWrapsToASecondRowWhenColumnsAreCapped() {
        // Only 11 columns fit, so the 12th app (last in reading order) wraps.
        val resolved = landscapeDockPositions(
            sixByTwoIds, sixByTwoPositions, portraitColumns, landscapeColumnCount = 11,
            mode = LandscapeDockMode.Reading,
        )

        assertEquals(DockPosition(0, 10), resolved["k"])
        assertEquals(DockPosition(1, 0), resolved["l"])
    }

    @Test
    fun splitFallsBackToReadingOrderForASingleRow() {
        val ids = listOf("a", "b", "c")
        val positions = mapOf(
            "a" to DockPosition(0, 0), "b" to DockPosition(0, 1), "c" to DockPosition(0, 2),
        )

        assertEquals(
            listOf("a", "b", "c"),
            flattenedOrder(ids, positions, LandscapeDockMode.Split, landscapeColumns = 6),
        )
    }

    @Test
    fun columnCountUsesTheBusiestVisibleDock() {
        // Personal (5) is busier than work (3); both visible -> 5 columns.
        assertEquals(
            5,
            landscapeDockColumnCount(
                isPersonalDockEnabled = true,
                personalDockedAppCount = 5,
                isWorkDockVisible = true,
                workDockedAppCount = 3,
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
                personalDockedAppCount = 9,
                isWorkDockVisible = true,
                workDockedAppCount = 3,
                dockIconCount = 4,
                landscapeFitColumns = 12,
            ),
        )
    }

    @Test
    fun columnCountIgnoresAHiddenWorkDockAndClampsToFit() {
        // Work dock not visible; personal has more apps than fit -> capped to fit.
        assertEquals(
            12,
            landscapeDockColumnCount(
                isPersonalDockEnabled = true,
                personalDockedAppCount = 20,
                isWorkDockVisible = false,
                workDockedAppCount = 0,
                dockIconCount = 4,
                landscapeFitColumns = 12,
            ),
        )
    }

    @Test
    fun flattenCompactsAPortraitGapAndKeepsSplitWingsContiguous() {
        // The real-world dock from the design: top row has a gap at column 4.
        //     maps cal yt blue  .  help
        //     wa   gh  gem orng brave gmail
        val ids = listOf("maps", "cal", "yt", "blue", "help", "wa", "gh", "gem", "orng", "brave", "gmail")
        val positions = mapOf(
            "maps" to DockPosition(0, 0), "cal" to DockPosition(0, 1), "yt" to DockPosition(0, 2),
            "blue" to DockPosition(0, 3), "help" to DockPosition(0, 5),
            "wa" to DockPosition(1, 0), "gh" to DockPosition(1, 1), "gem" to DockPosition(1, 2),
            "orng" to DockPosition(1, 3), "brave" to DockPosition(1, 4), "gmail" to DockPosition(1, 5),
        )

        // Split: top-left (col < 3) = maps cal yt; bottom row whole; top-right (col >= 3) = blue help.
        assertEquals(
            listOf("maps", "cal", "yt", "wa", "gh", "gem", "orng", "brave", "gmail", "blue", "help"),
            flattenedOrder(ids, positions, LandscapeDockMode.Split, landscapeColumns = 11),
        )
    }
}
