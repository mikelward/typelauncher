package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers [bottomScrollFadeStrength], the rule behind Settings' bottom fade:
 * full strength while there is more to scroll to, tapering off over the last
 * fade's worth, and absent entirely on a page that doesn't scroll.
 */
class ScrollFadeTest {
    @Test
    fun pageThatDoesNotScroll_hasNoFade() {
        assertEquals(
            0f,
            bottomScrollFadeStrength(scrollValue = 0, maxScrollValue = 0, fadeHeightPx = 120f),
            0f,
        )
    }

    @Test
    fun moreThanAFadeBelow_isFullStrength() {
        assertEquals(
            1f,
            bottomScrollFadeStrength(scrollValue = 0, maxScrollValue = 2000, fadeHeightPx = 120f),
            0f,
        )
    }

    @Test
    fun lastFadeWorthOfContent_tapersOff() {
        assertEquals(
            0.5f,
            bottomScrollFadeStrength(
                scrollValue = 1940,
                maxScrollValue = 2000,
                fadeHeightPx = 120f,
            ),
            0.001f,
        )
    }

    @Test
    fun scrolledToTheBottom_hasNoFade() {
        assertEquals(
            0f,
            bottomScrollFadeStrength(
                scrollValue = 2000,
                maxScrollValue = 2000,
                fadeHeightPx = 120f,
            ),
            0f,
        )
    }

    @Test
    fun unmeasuredScrollContainer_doesNotOverflow() {
        // ScrollState reports Int.MAX_VALUE until it has been measured; that
        // must read as a full-strength fade, not an overflowed negative.
        assertEquals(
            1f,
            bottomScrollFadeStrength(
                scrollValue = 0,
                maxScrollValue = Int.MAX_VALUE,
                fadeHeightPx = 120f,
            ),
            0f,
        )
    }

    @Test
    fun zeroHeightFade_isAbsent() {
        assertEquals(
            0f,
            bottomScrollFadeStrength(scrollValue = 0, maxScrollValue = 2000, fadeHeightPx = 0f),
            0f,
        )
    }
}
