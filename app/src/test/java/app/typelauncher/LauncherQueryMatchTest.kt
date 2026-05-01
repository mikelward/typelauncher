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
    fun subsequentCharactersMayBeLowercaseAfterAnchor() {
        // "boa" anchors at B (start of name); o and a then match as subsequence,
        // including the lowercase o at index 1.
        assertTrue("BofA".matchesLauncherQuery("boa"))
        assertTrue("BofA".matchesLauncherQuery("bf"))
        assertTrue("BofA".matchesLauncherQuery("ba"))
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
