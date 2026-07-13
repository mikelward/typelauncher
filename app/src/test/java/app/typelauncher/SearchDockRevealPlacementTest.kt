package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Placement math for the search-time dock reveal: the dock's top edge sits at
 * the top of the band the hidden keyboard reserved (one content inset below
 * the home layout's bottom edge), and floats up just far enough to stay fully
 * on screen when the band is shorter than the dock.
 */
class SearchDockRevealPlacementTest {
    @Test
    fun tallBand_placesDockAtTheKeyboardTopEdge() {
        // 800px band, 300px dock: the dock top sits where the keyboard's top
        // edge was — layout bottom (2000) plus the 24px content inset.
        assertEquals(
            2024,
            searchDockRevealTopPx(
                layoutHeightPx = 2000,
                contentInsetPx = 24,
                keyboardReservationPx = 800,
                dockHeightPx = 300,
            ),
        )
    }

    @Test
    fun bandExactlyDockSized_stillSitsAtTheBandTop() {
        assertEquals(
            2024,
            searchDockRevealTopPx(
                layoutHeightPx = 2000,
                contentInsetPx = 24,
                keyboardReservationPx = 300,
                dockHeightPx = 300,
            ),
        )
    }

    @Test
    fun collapsedBand_floatsDockUpToStayOnScreen() {
        // Nothing reserved (the keyboard was already dismissed): the dock's
        // bottom rests at the layout bottom + inset — the top of the nav
        // inset — overlapping the bottom of the list as a drop shelf.
        assertEquals(
            2000 + 24 - 300,
            searchDockRevealTopPx(
                layoutHeightPx = 2000,
                contentInsetPx = 24,
                keyboardReservationPx = 0,
                dockHeightPx = 300,
            ),
        )
    }

    @Test
    fun oversizedDock_neverPlacesAboveTheLayoutTop() {
        assertEquals(
            0,
            searchDockRevealTopPx(
                layoutHeightPx = 100,
                contentInsetPx = 0,
                keyboardReservationPx = 0,
                dockHeightPx = 500,
            ),
        )
    }
}
