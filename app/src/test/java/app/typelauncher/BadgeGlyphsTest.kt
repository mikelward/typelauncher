package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        assertEquals(names.sortedBy { it.lowercase(Locale.ENGLISH) }, names)
        // Each country whose ISO data exists everywhere we care about should
        // be listed exactly once.
        val keys = options.map { it.key }
        assertEquals(keys.distinct().size, keys.size)
        assertTrue(options.any { it.key == "country:US" })
        assertTrue(options.any { it.key == "country:GB" })
    }

    @Test
    fun countryBadgeOptionGlyphsAreFlagSequences() {
        val us = countryBadgeOptions(Locale.ENGLISH).firstOrNull { it.key == "country:US" }
        assertNotNull(us)
        assertEquals("🇺🇸", us!!.glyph)
    }
}
