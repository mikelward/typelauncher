package app.typelauncher

import androidx.annotation.StringRes
import java.util.Locale

// Globe glyph rendered when an ambiguous app group falls back to the
// "INTL" regional marker (Chase / Chase International), and one of the
// preset options offered in the badge picker.
internal const val INTL_GLOBE = "🌐"

// Base codepoint for Unicode regional-indicator letters. Two consecutive
// regional indicators rendered together produce the corresponding country
// flag emoji (e.g. RI('U') + RI('S') = 🇺🇸).
internal const val REGIONAL_INDICATOR_BASE = 0x1F1E6

/**
 * Maps a 2-letter ISO 3166-1 alpha-2 country code to its Unicode flag emoji
 * by replacing each letter with the corresponding regional-indicator
 * codepoint. Caller is responsible for passing an upper-cased A-Z pair —
 * passing anything else produces a malformed string but won't throw.
 */
internal fun countryFlag(countryCode: String): String =
    countryCode.map { codePoint ->
        Character.toChars(REGIONAL_INDICATOR_BASE + (codePoint - 'A')).concatToString()
    }.joinToString(separator = "")

/**
 * Inverse of [countryFlag]. If [glyph] is a valid two-character regional-
 * indicator pair (the encoding Unicode uses for flag emoji), returns the
 * corresponding 2-letter ISO 3166-1 alpha-2 code; otherwise returns
 * `null`. Used by the corner-badge accessibility helper to recover a
 * country code from a user-chosen flag glyph so the screen-reader content
 * description names the country instead of saying generic "badge".
 *
 * The encoding lives in the Unicode supplementary plane (codepoints
 * 0x1F1E6 ... 0x1F1FF), so each regional indicator is two UTF-16
 * surrogate halves and a flag string is exactly four UTF-16 chars long.
 */
internal fun decodeRegionalIndicatorPair(glyph: String): String? {
    if (glyph.length != 4) return null
    val first = glyph.codePointAt(0) - REGIONAL_INDICATOR_BASE
    val second = glyph.codePointAt(2) - REGIONAL_INDICATOR_BASE
    if (first !in 0..25 || second !in 0..25) return null
    return "${('A' + first)}${('A' + second)}"
}

/**
 * One option in the badge picker. [key] is a stable identifier used as the
 * picker's test tag suffix and selection comparison; [glyph] is the literal
 * string persisted to [CustomBadgeStore] and rendered as the corner badge.
 *
 * Exactly one of [labelRes] or [labelText] is set: presets (Home / Work /
 * School / Travel / World) carry an `@StringRes` so the picker can resolve a
 * localized label per the device locale, while country tiles carry the
 * already-localized display name from [Locale.getDisplayCountry] in
 * [labelText] because that string isn't representable as a fixed resource.
 */
internal data class BadgeOption(
    val key: String,
    val glyph: String,
    @StringRes val labelRes: Int? = null,
    val labelText: String? = null,
)

/**
 * The fixed-position presets at the top of the badge picker. Kept
 * deliberately small — Home / Work / School / Travel cover the most common
 * "second account" use cases (the my-personal-Gmail-vs-my-work-Gmail story)
 * and each of these glyphs is a single-codepoint emoji that renders cleanly
 * at the 14 dp corner-badge size on every Android emoji font we ship for.
 * Multi-codepoint ZWJ sequences and skin-toned emoji degrade to tofu at
 * that size, so we don't expose them here.
 *
 * Labels are resource IDs rather than literal strings so the picker shows
 * the right copy in every locale we ship for; the picker resolves them via
 * `stringResource(option.labelRes)` at render time.
 */
internal val BUILT_IN_BADGE_OPTIONS: List<BadgeOption> = listOf(
    BadgeOption(key = "home", glyph = "🏠", labelRes = R.string.badge_preset_home),
    BadgeOption(key = "work", glyph = "💼", labelRes = R.string.badge_preset_work),
    BadgeOption(key = "school", glyph = "🎓", labelRes = R.string.badge_preset_school),
    BadgeOption(key = "travel", glyph = "✈️", labelRes = R.string.badge_preset_travel),
)

/**
 * Globe option rendered as the first tile of the flag grid (i.e. *not*
 * part of the preset row above). Conceptually "international / not a
 * specific country" — pairs with the auto-disambiguator's INTL fallback
 * so a user can explicitly pick the same glyph for, e.g., Chase
 * International. Kept separate from [BUILT_IN_BADGE_OPTIONS] so the
 * preset row stays a small, semantically-coherent set of life-context
 * categories (Home / Work / School / Travel) and the globe sits next to
 * the country flags it generalises over.
 */
internal val WORLD_BADGE_OPTION: BadgeOption = BadgeOption(
    key = "world",
    glyph = INTL_GLOBE,
    labelRes = R.string.badge_preset_world,
)

/**
 * Every ISO 3166-1 alpha-2 country code reported by the JVM, mapped to a
 * regional-indicator flag glyph and the localised country name from
 * [Locale.getDisplayCountry]. Sorted by display name so the picker reads
 * top-to-bottom in alphabetical order in the user's current locale.
 *
 * Codes whose `getDisplayCountry` resolves to an empty string (e.g. the
 * handful of historical reservations that have no display name in the
 * current locale's data) are dropped so the picker doesn't show
 * unlabelled rows.
 */
internal fun countryBadgeOptions(locale: Locale = Locale.getDefault()): List<BadgeOption> {
    // Collate rather than compare code points: raw string order puts accented
    // names after "Z" ("Åland Islands", "Égypte"), so users scanning the grid
    // alphabetically can't find them. Same collation rule as displayNameOrder();
    // one instance per call because Collators are neither cheap to create nor
    // thread-safe.
    val collator = secondaryStrengthCollator(locale)
    return Locale.getISOCountries()
        .mapNotNull { code ->
            val displayName = Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
            if (displayName.isEmpty() || displayName == code) return@mapNotNull null
            BadgeOption(
                key = "country:$code",
                glyph = countryFlag(code),
                labelText = displayName,
            )
        }
        .sortedWith(compareBy(collator) { it.labelText.orEmpty() })
}

