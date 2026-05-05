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
    fun linearInsideTheFreeZone() {
        // Inside the free zone the page follows the finger 1:1 — visible
        // offset equals raw drag — so the swipe feels honest at the commit
        // threshold instead of lagging.
        assertEquals(0.1f * pageWidth, rubberBand(0.1f * pageWidth, pageWidth), 0.001f)
        assertEquals(0.4f * pageWidth, rubberBand(0.4f * pageWidth, pageWidth), 0.001f)
        assertEquals(-0.4f * pageWidth, rubberBand(-0.4f * pageWidth, pageWidth), 0.001f)
    }

    @Test
    fun stiffensPastTheFreeZone() {
        // Past the free zone (50% of page width) the curve damps — the user
        // can keep dragging but the page no longer tracks 1:1, signalling
        // they're approaching the one-page limit.
        val pastFreeZone = rubberBand(0.75f * pageWidth, pageWidth)
        assertTrue("display=$pastFreeZone should be < raw 0.75·pw", pastFreeZone < 0.75f * pageWidth)
        assertTrue("display=$pastFreeZone should still grow past freeZone", pastFreeZone > 0.5f * pageWidth)
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
    fun atFortyPercentDragDisplayMatchesFinger() {
        // At the commit threshold (40% raw drag) the visible offset is the
        // full 40% — inside the free zone — so when the user reaches the
        // commit point the next page is clearly half-emerging instead of
        // lagging at ~28% (the previous asymptotic curve).
        val rawDrag = 0.4f * pageWidth
        val displayed = rubberBand(rawDrag, pageWidth) / pageWidth
        assertEquals(0.4f, displayed, 0.001f)
    }

    @Test
    fun chooseBaseExtendsInFlightUsesSettled() {
        // Forward drag during a forward in-flight animation must NOT compound
        // into a multi-page jump: base = settledPage so commit lands at the
        // in-flight target, not one beyond it.
        assertEquals(5, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 6, dragDirection = 1))
        assertEquals(5, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 4, dragDirection = -1))
    }

    @Test
    fun chooseBaseReversesInFlightUsesTarget() {
        // Backward drag during a forward in-flight animation must cancel back
        // to the in-flight destination, not overshoot one further back: base
        // = inFlightTargetPage so the user lands on the screen the animation
        // was already heading to.
        assertEquals(6, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 6, dragDirection = -1))
        assertEquals(4, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 4, dragDirection = 1))
    }

    @Test
    fun chooseBaseNoAnimationUsesCurrentPage() {
        // No animation in flight → settled == target → base is that page.
        assertEquals(5, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 5, dragDirection = 1))
        assertEquals(5, chooseDragBasePage(settledPage = 5, inFlightTargetPage = 5, dragDirection = -1))
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
