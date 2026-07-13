package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class BadgeGlyphsTest {
    @Test
    fun countryFlagBuildsRegionalIndicatorPair() {
        // 🇺🇸 = U+1F1FA + U+1F1F8 (regional indicator U + regional indicator S).
        assertEquals("🇺🇸", countryFlag("US"))
        assertEquals("🇬🇧", countryFlag("GB"))
        assertEquals("🇦🇺", countryFlag("AU"))
    }

    @Test
    fun builtInBadgeOptionsCoverHomeWorkSchoolTravel() {
        val keys = BUILT_IN_BADGE_OPTIONS.map { it.key }.toSet()
        assertEquals(setOf("home", "work", "school", "travel"), keys)
    }

    @Test
    fun builtInBadgeOptionsHaveNonEmptyGlyphsAndResourceLabels() {
        for (option in BUILT_IN_BADGE_OPTIONS) {
            assertFalse("preset ${option.key} has empty glyph", option.glyph.isEmpty())
            // Presets must carry a @StringRes — that's how the picker resolves
            // a localized label per device locale instead of falling back to a
            // hardcoded English string.
            assertNotNull("preset ${option.key} has no labelRes", option.labelRes)
        }
    }

    @Test
    fun worldBadgeOptionUsesGlobeGlyphAndResourceLabel() {
        // The globe lives in the flag grid, not the preset row, so it isn't
        // in BUILT_IN_BADGE_OPTIONS. Verify it carries the same INTL_GLOBE
        // glyph the auto-disambiguator falls back to for INTL groups, and a
        // @StringRes so the label is localised by the picker.
        assertEquals(INTL_GLOBE, WORLD_BADGE_OPTION.glyph)
        assertEquals("world", WORLD_BADGE_OPTION.key)
        assertNotNull(WORLD_BADGE_OPTION.labelRes)
        // …and is *not* duplicated in the preset row.
        assertFalse(BUILT_IN_BADGE_OPTIONS.any { it.key == "world" })
    }

    @Test
    fun countryBadgeOptionsAreAlphabeticalAndIncludeKnownCountries() {
        val options = countryBadgeOptions(Locale.ENGLISH)
        // Enough countries are always reported by the JVM that "a substantial
        // list" is safer than a brittle exact count: this test mostly guards
        // the sort order and the presence of a couple of staples.
        assertTrue("expected many flag options, got ${options.size}", options.size > 50)
        val names = options.map { it.labelText.orEmpty() }
        // Alphabetical per the locale's collation, not per raw code points —
        // code-point order exiles accented names ("Åland Islands") past "Z".
        val collator = Collator.getInstance(Locale.ENGLISH).apply {
            strength = Collator.SECONDARY
        }
        assertEquals(names.sortedWith(collator), names)
        // Each country whose ISO data exists everywhere we care about should
        // be listed exactly once.
        val keys = options.map { it.key }
        assertEquals(keys.distinct().size, keys.size)
        assertTrue(options.any { it.key == "country:US" })
        assertTrue(options.any { it.key == "country:GB" })
    }

    @Test
    fun countryBadgeOptionsSortAccentedNamesIntoTheirAlphabeticalHome() {
        // Regression test for the code-point sort: 'Å' (U+00C5) compares
        // greater than 'z', so "Åland Islands" used to land after "Zimbabwe"
        // at the very bottom of the picker grid instead of under A where a
        // user scanning alphabetically looks for it.
        val names = countryBadgeOptions(Locale.ENGLISH).map { it.labelText.orEmpty() }
        val aland = names.indexOfFirst { it.startsWith("Åland") }
        assertTrue("expected Åland Islands in the English country list", aland >= 0)
        val albania = names.indexOf("Albania")
        assertTrue("expected Albania in the English country list", albania >= 0)
        assertTrue(
            "Åland Islands (index $aland) must sort under A, before Albania (index $albania)",
            aland < albania,
        )
    }

    @Test
    fun countryBadgeOptionGlyphsAreFlagSequences() {
        val us = countryBadgeOptions(Locale.ENGLISH).firstOrNull { it.key == "country:US" }
        assertNotNull(us)
        assertEquals("🇺🇸", us!!.glyph)
    }

    @Test
    fun countryBadgeOptionsAreMemoizedPerLocale() {
        // Building the list is ~250 ICU display-name lookups plus a collated
        // sort — far too costly to redo on the badge picker's dialog-open frame
        // every time it opens. The result is cached per locale, so repeated
        // calls for one locale hand back the very same instance.
        val first = countryBadgeOptions(Locale.ENGLISH)
        val second = countryBadgeOptions(Locale.ENGLISH)
        assertSame(first, second)
    }

    @Test
    fun countryBadgeOptionsCacheIsKeyedByLocale() {
        // A different locale keys a distinct entry, so a system-language change
        // still re-collates the names in the new locale rather than serving the
        // previous locale's ordering.
        val english = countryBadgeOptions(Locale.ENGLISH)
        val french = countryBadgeOptions(Locale.FRENCH)
        assertNotSame(english, french)
        // Both are non-empty, well-formed lists — the cache doesn't corrupt one
        // locale's result by keying it under another.
        assertTrue(english.any { it.key == "country:US" })
        assertTrue(french.any { it.key == "country:US" })
    }
}
