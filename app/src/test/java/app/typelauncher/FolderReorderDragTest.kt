package app.typelauncher

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit coverage for [handleFolderDrag], the open-folder member drag-reorder. The
 * geometry mirrors a single row of member cells with centers 100px apart, so the
 * reorder boundary between two neighbors sits at the midpoint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderReorderDragTest {
    // Three members in a row at x = 50 / 150 / 250 (the close + empty cells, which
    // never report a center, are deliberately absent — they are not drop targets).
    private val memberIds = listOf("a", "b", "c")
    private val memberCenters = mapOf(
        "a" to Offset(50f, 50f),
        "b" to Offset(150f, 50f),
        "c" to Offset(250f, 50f),
    )

    @Test
    fun draggingMemberPastNeighborCenterMovesItToThatIndex() {
        var movedAppId: String? = null
        var targetIndex = -1

        handleFolderDrag(
            // Drag "a" to x=160, just past "b"'s center at 150.
            delta = Offset(110f, 0f),
            draggedAppId = "a",
            memberIds = memberIds,
            memberCenters = memberCenters,
            onReorder = { appId, index ->
                movedAppId = appId
                targetIndex = index
            },
            currentOffset = Offset.Zero,
            setOffset = {},
        )

        assertEquals("a", movedAppId)
        assertEquals(1, targetIndex)
    }

    @Test
    fun smallDragWithinOwnCellDoesNotReorder() {
        var reordered = false

        handleFolderDrag(
            // 30px is short of "b"'s center, so "a" stays nearest its own cell.
            delta = Offset(30f, 0f),
            draggedAppId = "a",
            memberIds = memberIds,
            memberCenters = memberCenters,
            onReorder = { _, _ -> reordered = true },
            currentOffset = Offset.Zero,
            setOffset = {},
        )

        assertEquals(false, reordered)
    }

    @Test
    fun draggingOntoNonMemberCellsDoesNotReorder() {
        // A lone member: every other cell in the grid (close, empty fillers)
        // reports no center, so there is nowhere to reorder to no matter how far
        // the icon is dragged.
        var reordered = false

        handleFolderDrag(
            delta = Offset(500f, 0f),
            draggedAppId = "a",
            memberIds = listOf("a"),
            memberCenters = mapOf("a" to Offset(50f, 50f)),
            onReorder = { _, _ -> reordered = true },
            currentOffset = Offset.Zero,
            setOffset = {},
        )

        assertEquals(false, reordered)
    }

    @Test
    fun reorderRebasesOffsetOntoTheTargetCell() {
        var updatedOffset = Offset.Zero

        handleFolderDrag(
            // Drag "a" 110px right: lands at x=160, past "b"'s center. After the
            // reorder the lifted tile rebases onto "b"'s old cell (x=150), leaving
            // a residual +10px so the icon keeps tracking the finger.
            delta = Offset(110f, 0f),
            draggedAppId = "a",
            memberIds = memberIds,
            memberCenters = memberCenters,
            onReorder = { _, _ -> },
            currentOffset = Offset.Zero,
            setOffset = { updatedOffset = it },
        )

        assertEquals(Offset(10f, 0f), updatedOffset)
    }

    @Test
    fun staleCenterOfRemovedMemberDoesNotBlockReorder() {
        // A member ("b") was moved out / hidden while the folder stayed open, so
        // its last center lingers in the remembered map at x=150 even though it is
        // no longer in memberIds. Dragging "a" toward "c" puts the dragged center
        // nearest that stale entry; it must be ignored so the reorder onto "c"
        // still fires instead of breaking the loop early.
        val liveIds = listOf("a", "c")
        val centersWithStale = mapOf(
            "a" to Offset(50f, 50f),
            "b" to Offset(150f, 50f),
            "c" to Offset(250f, 50f),
        )
        var movedAppId: String? = null
        var targetIndex = -1

        handleFolderDrag(
            // Drag "a" to x=160 — closest to "b"'s stale center (150), then "c".
            delta = Offset(110f, 0f),
            draggedAppId = "a",
            memberIds = liveIds,
            memberCenters = centersWithStale,
            onReorder = { appId, index ->
                movedAppId = appId
                targetIndex = index
            },
            currentOffset = Offset.Zero,
            setOffset = {},
        )

        assertEquals("a", movedAppId)
        assertEquals(1, targetIndex)
    }

    @Test
    fun unknownDraggedIdJustAccumulatesOffset() {
        var reordered = false
        var updatedOffset = Offset.Zero

        handleFolderDrag(
            delta = Offset(40f, 0f),
            draggedAppId = "ghost",
            memberIds = memberIds,
            memberCenters = memberCenters,
            onReorder = { _, _ -> reordered = true },
            currentOffset = Offset(5f, 0f),
            setOffset = { updatedOffset = it },
        )

        assertEquals(false, reordered)
        assertEquals(Offset(45f, 0f), updatedOffset)
    }

    @Test
    fun nullDraggedIdIsANoOp() {
        var setOffsetCalled = false
        var reordered = false

        handleFolderDrag(
            delta = Offset(40f, 0f),
            draggedAppId = null,
            memberIds = memberIds,
            memberCenters = memberCenters,
            onReorder = { _, _ -> reordered = true },
            currentOffset = Offset.Zero,
            setOffset = { setOffsetCalled = true },
        )

        assertEquals(false, reordered)
        assertEquals(false, setOffsetCalled)
    }
}
