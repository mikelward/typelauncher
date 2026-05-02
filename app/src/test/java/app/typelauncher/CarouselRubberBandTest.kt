package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CarouselRubberBandTest {
    private val pageWidth = 1000f

    @Test
    fun zeroDragReturnsZero() {
        assertEquals(0f, rubberBand(0f, pageWidth), 0.0001f)
    }

    @Test
    fun signMatchesInput() {
        assertTrue(rubberBand(-50f, pageWidth) < 0f)
        assertTrue(rubberBand(50f, pageWidth) > 0f)
    }

    @Test
    fun outputAtOnePageWidthIsHalfPageWidth() {
        // The asymptotic curve hits exactly half the page width when the raw
        // drag equals the page width — this is the visual "stiffness" the user
        // feels at full-width drag.
        assertEquals(pageWidth / 2f, rubberBand(pageWidth, pageWidth), 0.001f)
        assertEquals(-pageWidth / 2f, rubberBand(-pageWidth, pageWidth), 0.001f)
    }

    @Test
    fun outputAlwaysLessThanPageWidth() {
        // The clamp on dispatchRawDelta relies on rubberBand never exceeding
        // pageWidth, so the pager can never advance past one page from a
        // single drag, no matter how long the user keeps pulling.
        for (multiplier in listOf(1f, 5f, 50f, 500f, 5_000f)) {
            val value = rubberBand(multiplier * pageWidth, pageWidth)
            assertTrue("|rubberBand($multiplier·pw)|=$value must be < pw", abs(value) < pageWidth)
        }
    }

    @Test
    fun strictlyMonotonicInDragMagnitude() {
        var previous = rubberBand(0f, pageWidth)
        var x = 10f
        while (x <= 4f * pageWidth) {
            val current = rubberBand(x, pageWidth)
            assertTrue("rubberBand should grow with |x| (x=$x)", current > previous)
            previous = current
            x += 10f
        }
    }

    @Test
    fun zeroPageWidthReturnsZero() {
        // Defensive: avoid divide-by-zero on surfaces that haven't been laid
        // out yet (size.width can be 0 before first measure).
        assertEquals(0f, rubberBand(500f, 0f), 0.0001f)
        assertEquals(0f, rubberBand(-500f, 0f), 0.0001f)
    }

    @Test
    fun atFortyPercentDragDisplayIsAboutTwentyEightPercent() {
        // Sanity check that the commit threshold (40% raw drag) corresponds
        // to a substantial visible offset (~28% of page width), so when the
        // user reaches the commit point the next page is clearly emerging.
        val rawDrag = 0.4f * pageWidth
        val displayed = rubberBand(rawDrag, pageWidth) / pageWidth
        assertTrue("display=$displayed should be > 0.27", displayed > 0.27f)
        assertTrue("display=$displayed should be < 0.30", displayed < 0.30f)
    }

    @Test
    fun carouselClaimsOnlyPredominantlyHorizontalAvailableDrag() {
        assertTrue(shouldClaimCarouselDrag(availableDragX = 20f, totalDragY = 5f, touchSlopPx = 8f))
        assertTrue(shouldClaimCarouselDrag(availableDragX = -20f, totalDragY = 5f, touchSlopPx = 8f))
    }

    @Test
    fun carouselDoesNotClaimDiagonalDragWhoseTotalMovementIsVertical() {
        // Regression: child LazyColumns can consume most of the Y delta before
        // PointerEventPass.Final. The carousel must compare X against total Y,
        // not only the unconsumed vertical remainder.
        assertTrue(!shouldClaimCarouselDrag(availableDragX = 20f, totalDragY = 80f, touchSlopPx = 8f))
    }

    @Test
    fun carouselDoesNotClaimUntilHorizontalDragClearsTouchSlop() {
        assertTrue(!shouldClaimCarouselDrag(availableDragX = 8f, totalDragY = 0f, touchSlopPx = 8f))
    }
}
