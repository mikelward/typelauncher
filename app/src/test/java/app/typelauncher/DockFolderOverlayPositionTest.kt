package app.typelauncher

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for [dockFolderOverlayPositionProvider], the pure popup-position
 * math behind the [DockFolderOpenStyle.Overlay] card. The card sits directly
 * above the folder cell — its bottom edge on the cell's top edge, no gap, so it
 * reads as the dock growing another row straight up out of the folder — and is
 * clamped to the window so a folder near an edge stays fully on screen.
 */
class DockFolderOverlayPositionTest {
    private val provider = dockFolderOverlayPositionProvider()

    private fun position(
        anchor: IntRect,
        window: IntSize,
        popup: IntSize,
    ) = provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup)

    @Test
    fun bottomEdgeSitsOnFolderCellTopCenteredHorizontally() {
        val anchor = IntRect(left = 400, top = 1000, right = 500, bottom = 1100)
        val popup = IntSize(width = 300, height = 200)
        val offset = position(anchor, IntSize(1080, 1920), popup)

        // Centered on the cell (centerX 450 - half the 300-wide card = 300).
        assertEquals(300, offset.x)
        // Bottom edge (y + height) lands exactly on the cell's top edge (1000).
        assertEquals(800, offset.y)
        assertEquals(1000, offset.y + popup.height)
    }

    @Test
    fun clampsToLeftWindowEdge() {
        // A folder hugging the left edge would center the card off-screen left.
        val anchor = IntRect(left = 0, top = 1000, right = 100, bottom = 1100)
        val offset = position(anchor, IntSize(1080, 1920), IntSize(300, 200))
        assertEquals(0, offset.x)
    }

    @Test
    fun clampsToRightWindowEdge() {
        // A folder hugging the right edge would center the card off-screen right.
        val anchor = IntRect(left = 980, top = 1000, right = 1080, bottom = 1100)
        val offset = position(anchor, IntSize(1080, 1920), IntSize(300, 200))
        assertEquals(780, offset.x) // window width 1080 - card width 300
    }

    @Test
    fun clampsToTopWhenCardWouldOverflowAboveWindow() {
        // A folder near the top can't push the card above the window.
        val anchor = IntRect(left = 400, top = 100, right = 500, bottom = 200)
        val offset = position(anchor, IntSize(1080, 1920), IntSize(300, 400))
        assertEquals(0, offset.y)
    }
}
