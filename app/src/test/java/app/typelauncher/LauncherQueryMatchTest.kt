package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun prefixTierWinsOverAnchoredAndSubstring() {
        assertEquals(LauncherMatchTier.Prefix, "Gmail".launcherMatchTier("g"))
        assertEquals(LauncherMatchTier.Prefix, "Gmail".launcherMatchTier("gma"))
        assertEquals(LauncherMatchTier.Prefix, "google mail".launcherMatchTier("goog"))
        assertEquals(LauncherMatchTier.Prefix, "Surfshark".launcherMatchTier("surf"))
    }

    @Test
    fun anchoredTierMatchesWhenNotAPrefix() {
        assertEquals(LauncherMatchTier.Anchored, "Apple TV".launcherMatchTier("atv"))
        assertEquals(LauncherMatchTier.Anchored, "BofA".launcherMatchTier("ba"))
        assertEquals(LauncherMatchTier.Anchored, "Google Mail".launcherMatchTier("m"))
    }

    @Test
    fun substringTierMatchesWhenAnchorRulesReject() {
        assertEquals(LauncherMatchTier.Substring, "Gmail".launcherMatchTier("mail"))
        assertEquals(LauncherMatchTier.Substring, "1password".launcherMatchTier("pass"))
        // "Snail" contains "ail" mid-word: anchor rules reject it (lowercase
        // 'a' isn't a boundary), substring rules accept it.
        assertEquals(LauncherMatchTier.Substring, "Snail".launcherMatchTier("ail"))
    }

    @Test
    fun fuzzyTierMatchesMidWordSkipBelowSubstring() {
        // The motivating case: "vw" -> "Volkswagen". The 'w' is mid-word, so the
        // strict anchored tier rejects it and it isn't a prefix or substring;
        // the loose Fuzzy tier (first letter anchored, rest a subsequence)
        // catches it, ranked below every precise tier.
        assertEquals(LauncherMatchTier.Fuzzy, "Volkswagen".launcherMatchTier("vw"))
        // "fa" -> "Air France" / "Fly Delta" returns to the results, but only in
        // the Fuzzy (bottom) tier — never above a prefix match like "Facebook".
        assertEquals(LauncherMatchTier.Fuzzy, "Air France".launcherMatchTier("fa"))
        assertEquals(LauncherMatchTier.Fuzzy, "Fly Delta".launcherMatchTier("fa"))
        // A prefix/substring still wins over fuzzy for the same label.
        assertEquals(LauncherMatchTier.Prefix, "Volkswagen".launcherMatchTier("vo"))
    }

    @Test
    fun fuzzyTierStillRequiresFirstLetterAnchor() {
        // The first query letter must start a word — 'v' is mid-word in
        // "Service", so it never anchors and "vw" can't match here.
        assertNull("Service Now".launcherMatchTier("vw"))
        // Single-character queries never reach the fuzzy tier (FUZZY_MIN_QUERY_LENGTH).
        assertNull("Volkswagen".launcherMatchTier("z"))
    }

    @Test
    fun packageBrandTierMatchesHiddenBrandPrefix() {
        // Virgin Money: title is "Credit Card", brand only lives in the package.
        assertEquals(LauncherMatchTier.PackageBrand, "com.virginmoney.cards".packageBrandMatchTier("virgin"))
        assertEquals(LauncherMatchTier.PackageBrand, "com.virginmoney.cards".packageBrandMatchTier("vir"))
    }

    @Test
    fun packageBrandTierIgnoresGenericSegmentsButMatchesShortPrefixes() {
        // Generic reverse-DNS / platform segments never anchor a match, so a
        // short query doesn't pull in every app sharing the boilerplate prefix.
        assertNull("com.google.android.apps.maps".packageBrandMatchTier("and"))
        assertNull("com.google.android.apps.maps".packageBrandMatchTier("app"))
        // Matching activates from the first character: even a lone "v" reaches
        // the brand segment "virginmoney".
        assertEquals(LauncherMatchTier.PackageBrand, "com.virginmoney.cards".packageBrandMatchTier("v"))
        assertEquals(LauncherMatchTier.PackageBrand, "com.virginmoney.cards".packageBrandMatchTier("vi"))
        // A non-prefix substring of a brand segment does not match (prefix only).
        assertNull("com.virginmoney.cards".packageBrandMatchTier("money"))
    }

    @Test
    fun substringTierIsCaseInsensitive() {
        assertEquals(LauncherMatchTier.Substring, "Gmail".launcherMatchTier("MAIL"))
        assertEquals(LauncherMatchTier.Substring, "Gmail".launcherMatchTier("Mail"))
    }

    @Test
    fun nonMatchingQueryReturnsNullTier() {
        assertNull("Gmail".launcherMatchTier("xyz"))
        assertNull("Surfshark".launcherMatchTier("zzz"))
    }

    @Test
    fun emptyQueryFallsIntoPrefixTier() {
        assertEquals(LauncherMatchTier.Prefix, "Gmail".launcherMatchTier(""))
    }

    // --- Work-profile "Work " prefix --------------------------------------
    //
    // Work-profile apps render as "Work <name>" so users can disambiguate them
    // from their personal-profile counterparts by typing. The match tiers below
    // verify that the prefix really is typable both wholesale ("work cal") and
    // that the personal-vs-work pair remain searchable by the original noun.

    @Test
    fun workPrefixTierMatchesLeadingWord() {
        assertEquals(LauncherMatchTier.Prefix, "Work Calendar".launcherMatchTier("work"))
        assertEquals(LauncherMatchTier.Prefix, "Work Calendar".launcherMatchTier("work cal"))
        assertEquals(LauncherMatchTier.Prefix, "Work Calendar".launcherMatchTier("w"))
    }

    @Test
    fun originalNameInsideWorkPrefixedLabelAnchorsOnTheCapital() {
        // Typing "cal" must still surface the work-profile Calendar — the
        // capital C in "Work Calendar" anchors the match (Anchored tier) and
        // ranks below a personal "Calendar" that wins the Prefix tier.
        assertEquals(LauncherMatchTier.Anchored, "Work Calendar".launcherMatchTier("cal"))
        assertEquals(LauncherMatchTier.Anchored, "Work Calendar".launcherMatchTier("c"))
        assertEquals(LauncherMatchTier.Prefix, "Calendar".launcherMatchTier("cal"))
    }

    @Test
    fun workPrefixedLabelOfAppStartingWithWorkStillMatchesBothWords() {
        // "Workplace" already starts with "Work"; the prefixed label is
        // "Work Workplace". A plain "work" is still a prefix match (the label
        // literally starts with it); typing further past the leading word
        // drops to Anchored on the second capital W ("workp" anchors at the
        // second W and walks "orkp").
        assertEquals(LauncherMatchTier.Prefix, "Work Workplace".launcherMatchTier("work"))
        assertEquals(LauncherMatchTier.Anchored, "Work Workplace".launcherMatchTier("workp"))
    }
}
