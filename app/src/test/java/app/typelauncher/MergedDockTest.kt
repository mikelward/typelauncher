package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [mergePersonalAndWorkDock], the pure transform that folds the
 * work dock's apps into the personal dock when "Show work dock" is off. Work
 * apps pack into the free cells after the personal block; personal coordinates
 * are preserved; an app pinned to both docks renders once at its personal slot.
 */
class MergedDockTest {
    @Test
    fun packsWorkAppsAfterPersonalFillingTheLastPartialRow() {
        val merged = mergePersonalAndWorkDock(
            personalIds = listOf("a", "b", "c"),
            personalPositions = mapOf(
                "a" to DockPosition(0, 0),
                "b" to DockPosition(0, 1),
                "c" to DockPosition(0, 2),
            ),
            workIds = listOf("w1", "w2"),
            columnCount = 4,
        )

        assertEquals(listOf("a", "b", "c", "w1", "w2"), merged.orderedIds)
        // Personal coords untouched; work fills the row-0 gap then wraps.
        assertEquals(DockPosition(0, 0), merged.positions["a"])
        assertEquals(DockPosition(0, 1), merged.positions["b"])
        assertEquals(DockPosition(0, 2), merged.positions["c"])
        assertEquals(DockPosition(0, 3), merged.positions["w1"])
        assertEquals(DockPosition(1, 0), merged.positions["w2"])
    }

    @Test
    fun wrapsWorkAppsToANewRowWhenPersonalFillsTheFirstRow() {
        val merged = mergePersonalAndWorkDock(
            personalIds = listOf("a", "b", "c", "d"),
            personalPositions = mapOf(
                "a" to DockPosition(0, 0),
                "b" to DockPosition(0, 1),
                "c" to DockPosition(0, 2),
                "d" to DockPosition(0, 3),
            ),
            workIds = listOf("w1", "w2"),
            columnCount = 4,
        )

        assertEquals(DockPosition(1, 0), merged.positions["w1"])
        assertEquals(DockPosition(1, 1), merged.positions["w2"])
    }

    @Test
    fun dropsAnAppPinnedToBothDocksKeepingThePersonalSlot() {
        val merged = mergePersonalAndWorkDock(
            personalIds = listOf("a", "b"),
            personalPositions = mapOf(
                "a" to DockPosition(0, 0),
                "b" to DockPosition(0, 1),
            ),
            // "b" is pinned to both docks (the dual-pin case).
            workIds = listOf("b", "w1"),
            columnCount = 4,
        )

        // "b" appears once, at its personal coordinate; "w1" packs after it.
        assertEquals(listOf("a", "b", "w1"), merged.orderedIds)
        assertEquals(DockPosition(0, 1), merged.positions["b"])
        assertEquals(DockPosition(0, 2), merged.positions["w1"])
    }

    @Test
    fun isIdempotentUnderResolvedDockPositions() {
        val merged = mergePersonalAndWorkDock(
            personalIds = listOf("a", "b", "c"),
            personalPositions = mapOf(
                "a" to DockPosition(0, 0),
                "b" to DockPosition(0, 1),
                "c" to DockPosition(0, 2),
            ),
            workIds = listOf("w1", "w2", "w3"),
            columnCount = 4,
        )

        // Re-resolving the merged map (as DockCard does) must not re-flow it.
        val reResolved = resolvedDockPositions(merged.orderedIds, merged.positions, 4)
        assertEquals(merged.positions, reResolved)
    }

    @Test
    fun emptyWorkDockIsTheResolvedPersonalDock() {
        val personalIds = listOf("a", "b")
        val personalPositions = mapOf(
            "a" to DockPosition(0, 0),
            "b" to DockPosition(0, 1),
        )
        val merged = mergePersonalAndWorkDock(
            personalIds = personalIds,
            personalPositions = personalPositions,
            workIds = emptyList(),
            columnCount = 4,
        )

        assertEquals(personalIds, merged.orderedIds)
        assertEquals(resolvedDockPositions(personalIds, personalPositions, 4), merged.positions)
    }

    @Test
    fun keepsWorkAppsTrailingASparsePersonalLayout() {
        // Personal has a single app parked at column 2 of row 0; the leading
        // cells are free. Work apps must NOT back-fill (0,0)/(0,1) — that would
        // render a work icon ahead of the personal icon and break the
        // contiguous-trailing-group contract. The work app trails at (0,3).
        val merged = mergePersonalAndWorkDock(
            personalIds = listOf("a"),
            personalPositions = mapOf("a" to DockPosition(0, 2)),
            workIds = listOf("w1", "w2"),
            columnCount = 4,
        )

        // resolvedDockPositions preserves the column gap for the personal app;
        // work trails after its cell and wraps.
        assertEquals(DockPosition(0, 2), merged.positions["a"])
        assertEquals(DockPosition(0, 3), merged.positions["w1"])
        assertEquals(DockPosition(1, 0), merged.positions["w2"])
        assertEquals(listOf("a", "w1", "w2"), merged.orderedIds)
    }
}
