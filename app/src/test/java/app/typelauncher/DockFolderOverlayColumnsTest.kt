package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for [dockFolderOverlayColumns], the pure column-count math behind
 * the [DockFolderOpenStyle.Overlay] shapes. `maxColumnsForWidth` is the most
 * columns the screen width allows; the helper caps every shape by it (and by
 * [DOCK_FOLDER_OVERLAY_MAX_COLUMNS] for the wrapping shapes).
 */
class DockFolderOverlayColumnsTest {
    private fun columns(count: Int, shape: DockFolderOverlayShape, maxForWidth: Int = 6) =
        dockFolderOverlayColumns(count, shape, maxForWidth)

    @Test
    fun compactIsAsWideAsTheAppsCappedAtFive() {
        assertEquals(4, columns(4, DockFolderOverlayShape.Compact))
        assertEquals(1, columns(1, DockFolderOverlayShape.Compact))
        // Capped at DOCK_FOLDER_OVERLAY_MAX_COLUMNS (5) even with more members.
        assertEquals(5, columns(7, DockFolderOverlayShape.Compact))
    }

    @Test
    fun rectangleFillsEveryColumnTheWidthAllows() {
        assertEquals(6, columns(4, DockFolderOverlayShape.Rectangle))
        // Fills the row even when there are fewer members than columns.
        assertEquals(6, columns(2, DockFolderOverlayShape.Rectangle))
    }

    @Test
    fun squareIsCeilSqrtOfTheMemberCount() {
        assertEquals(2, columns(4, DockFolderOverlayShape.Square)) // 2x2
        assertEquals(3, columns(9, DockFolderOverlayShape.Square)) // 3x3
        assertEquals(3, columns(5, DockFolderOverlayShape.Square)) // ceil(sqrt 5) = 3
        assertEquals(2, columns(2, DockFolderOverlayShape.Square)) // ceil(sqrt 2) = 2
        assertEquals(1, columns(1, DockFolderOverlayShape.Square))
    }

    @Test
    fun everyShapeIsClampedToTheWidthBudget() {
        // A narrow screen (maxForWidth = 2) caps each shape at two columns.
        assertEquals(2, columns(7, DockFolderOverlayShape.Compact, maxForWidth = 2))
        assertEquals(2, columns(4, DockFolderOverlayShape.Rectangle, maxForWidth = 2))
        assertEquals(2, columns(9, DockFolderOverlayShape.Square, maxForWidth = 2))
    }

    @Test
    fun emptyOrDegenerateInputsNeverGoBelowOneColumn() {
        assertEquals(1, columns(0, DockFolderOverlayShape.Compact))
        assertEquals(1, columns(0, DockFolderOverlayShape.Square))
        assertEquals(1, columns(0, DockFolderOverlayShape.Rectangle, maxForWidth = 0))
    }
}
