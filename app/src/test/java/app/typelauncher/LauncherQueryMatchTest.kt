package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherQueryMatchTest {
    @Test
    fun emptyQueryMatchesAnyName() {
        assertTrue("".let { "Surfshark".matchesLauncherQuery(it) })
        assertTrue("".let { "1password".matchesLauncherQuery(it) })
    }

    @Test
    fun firstLetterMatchesAtStart() {
        assertTrue("Surfshark".matchesLauncherQuery("s"))
        assertTrue("Surfshark".matchesLauncherQuery("S"))
    }

    @Test
    fun capitalLetterAnchorsMatchInsideName() {
        assertTrue("mySA".matchesLauncherQuery("s"))
        assertTrue("mySA".matchesLauncherQuery("sa"))
    }

    @Test
    fun lowercaseInteriorLetterDoesNotAnchorMatch() {
        assertFalse("1password".matchesLauncherQuery("s"))
        assertFalse("1password".matchesLauncherQuery("p"))
        assertFalse("1password".matchesLauncherQuery("pass"))
    }

    @Test
    fun firstLetterStillReachableForLowercaseStarters() {
        assertTrue("1password".matchesLauncherQuery("1"))
        assertTrue("1password".matchesLauncherQuery("1p"))
        assertTrue("1password".matchesLauncherQuery("1pa"))
    }

    @Test
    fun consecutiveLowercaseCharactersMatchAfterAnchor() {
        // "boa" anchors at B; the lowercase o is consecutive with the anchor (no
        // skip), and the capital A is a word-boundary skip target.
        assertTrue("BofA".matchesLauncherQuery("boa"))
        // "ba" anchors at B and skips to the capital A — A is a boundary.
        assertTrue("BofA".matchesLauncherQuery("ba"))
    }

    @Test
    fun skippedLowercaseCharactersRequireWordBoundary() {
        // "bf" anchors at B, then would skip past lowercase o to land on the
        // mid-word lowercase f. f is not a word boundary, so no match.
        assertFalse("BofA".matchesLauncherQuery("bf"))
    }

    @Test
    fun skipsIntoMidWordLowercaseDoNotMatch() {
        // The reported bug: "fa" should not pull in apps where the only 'a'
        // after the F-anchor sits in the middle of a word.
        assertFalse("Air France".matchesLauncherQuery("fa"))
        assertFalse("Fly Delta".matchesLauncherQuery("fa"))
    }

    @Test
    fun acronymsAcrossWordsMatch() {
        // "ATV" against "Apple TV": A anchors at start, T is uppercase
        // (skip-boundary), V is consecutive with T.
        assertTrue("Apple TV".matchesLauncherQuery("ATV"))
        assertTrue("Apple TV".matchesLauncherQuery("atv"))
    }

    @Test
    fun lowercaseStartOfNextWordIsASkipBoundary() {
        // After matching 'g' at the start of "google mail", the lowercase 'm'
        // beginning the second word is reachable because it follows a space.
        assertTrue("google mail".matchesLauncherQuery("gm"))
        // But a mid-word lowercase letter is not a boundary, even when the
        // anchor is the start of the label.
        assertFalse("google mail".matchesLauncherQuery("ga"))
    }

    @Test
    fun capitalLetterAfterSpaceStillAnchorsBecauseItIsCapital() {
        // Anchors only on start or capital letters in v1 — the capital M in
        // "Google Mail" is what makes it reachable, not the space.
        assertTrue("Google Mail".matchesLauncherQuery("gm"))
        assertTrue("Google Mail".matchesLauncherQuery("m"))
    }

    @Test
    fun spacesAloneDoNotCountAsAnchors() {
        // All-lowercase "google mail" has no capitals, so only the leading 'g'
        // is reachable. A bare 'm' query has no anchor.
        assertFalse("google mail".matchesLauncherQuery("m"))
        // 'g' anchors at start and 'm' is then a subsequence after — match.
        assertTrue("google mail".matchesLauncherQuery("gm"))
    }

    @Test
    fun lowercaseInteriorLetterWithoutBoundaryDoesNotMatch() {
        // No leading "ail" anchor in Gmail (no capital before 'a' or 'm').
        assertFalse("Gmail".matchesLauncherQuery("ail"))
        assertFalse("Gmail".matchesLauncherQuery("mail"))
    }

    @Test
    fun singleLetterQueryDoesNotPullInUnrelatedNames() {
        // Guards the cases that motivated the v1 rule.
        assertFalse("Ross".matchesLauncherQuery("s"))
        assertFalse("1password".matchesLauncherQuery("s"))
    }

    @Test
    fun queryLongerThanCandidateNeverMatches() {
        assertFalse("Surfshark".matchesLauncherQuery("Surfsharkxyz"))
    }

    @Test
    fun matchIsCaseInsensitive() {
        assertTrue("Surfshark".matchesLauncherQuery("SURF"))
        assertTrue("Surfshark".matchesLauncherQuery("sUrF"))
        assertTrue("mySA".matchesLauncherQuery("SA"))
    }
}
