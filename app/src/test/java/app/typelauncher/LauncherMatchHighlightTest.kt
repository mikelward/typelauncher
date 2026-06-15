package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for [launcherMatchHighlightIndices], which decides *which* title
 * letters the inline search suggestion bolds. The returned indices must follow
 * the same tier precedence as [launcherMatchTier] so the highlighted run matches
 * how the app actually ranked, and must be empty when only the package brand
 * matched (nothing in the title to bold).
 */
class LauncherMatchHighlightTest {
    @Test
    fun prefixHighlightsLeadingRun() {
        assertEquals(listOf(0, 1), launcherMatchHighlightIndices("Gmail", "gm"))
    }

    @Test
    fun prefixIsCaseInsensitive() {
        assertEquals(listOf(0, 1, 2), launcherMatchHighlightIndices("Calculator", "CAL"))
    }

    @Test
    fun substringHighlightsContiguousRun() {
        // "mail" isn't a prefix and doesn't anchor on a word boundary, so it
        // matches as a substring starting at index 1.
        assertEquals(listOf(1, 2, 3, 4), launcherMatchHighlightIndices("Gmail", "mail"))
    }

    @Test
    fun anchoredHighlightsBoundaryLetters() {
        // "atv" -> "Apple TV": 'a' anchors at 0, then 'T'/'V' on the second word.
        assertEquals(listOf(0, 6, 7), launcherMatchHighlightIndices("Apple TV", "atv"))
    }

    @Test
    fun fuzzyHighlightsSubsequence() {
        // "vw" -> "Volkswagen": 'v' anchors at 0, 'w' is mid-word so only the
        // loose fuzzy tier matches it (index 5).
        assertEquals(listOf(0, 5), launcherMatchHighlightIndices("Volkswagen", "vw"))
    }

    @Test
    fun packageOnlyMatchHighlightsNothing() {
        // The Virgin Money case: query reaches the app via its package brand, not
        // its title, so there's nothing in "Credit Card" to bold.
        assertEquals(emptyList<Int>(), launcherMatchHighlightIndices("Credit Card", "virgin"))
    }

    @Test
    fun emptyQueryHighlightsNothing() {
        assertEquals(emptyList<Int>(), launcherMatchHighlightIndices("Gmail", ""))
    }
}
