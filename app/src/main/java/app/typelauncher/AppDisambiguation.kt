package app.typelauncher

// Recognised reverse-DNS prefixes that effectively act as eTLDs in Android
// package names. Multi-component entries are matched first so e.g.
// `com.au.foo.bar` resolves to brand `foo`, not `au`.
//
// This is intentionally a small hard-coded subset rather than the full Public
// Suffix List — pulling in the PSL just to disambiguate launcher icons would
// be far more weight than the feature warrants.
private val ETLD_PREFIXES: List<List<String>> = listOf(
    "co.uk", "com.au", "co.jp", "co.kr", "co.nz", "com.br", "com.mx", "com.cn",
    "com.tr", "com.sg", "com.hk", "com.tw",
    // com.ie — reverse-DNS of ie.com (used by some Irish/UK apps, e.g. com.ie.capitalone.uk)
    // com.konylabs — Kony/Temenos mobile-platform prefix; the brand follows it
    "com.ie", "com.konylabs",
    "com", "org", "net", "io", "app", "dev", "co",
    "de", "fr", "jp", "ru", "uk", "us",
).map { it.split('.') }
    .sortedByDescending { it.size }

// ISO 3166-1 alpha-2 country codes plus "uk" (which the standard spells "gb"
// but Android packages routinely use the colloquial form). "tv" (Tuvalu) and
// "cd" (DR Congo) are deliberately omitted because they collide with common
// English abbreviations — "Google TV" / "Foo CD" should not be treated as
// regional variants of "Google" / "Foo". Non-ISO regional markers (EMEA,
// APAC, INTL, etc.) are deliberately excluded too — keeping the set to a
// closed standard avoids false-classifying real brands like ANZ-the-bank as
// regional variants. Re-add specific markers if real apps in the wild are
// observed using them.
private val COUNTRY_CODES: Set<String> = buildSet {
    addAll(
        listOf(
            "ad", "ae", "af", "ag", "ai", "al", "am", "ao", "aq", "ar", "as", "at", "au", "aw", "ax", "az",
            "ba", "bb", "bd", "be", "bf", "bg", "bh", "bi", "bj", "bl", "bm", "bn", "bo", "bq", "br", "bs",
            "bt", "bv", "bw", "by", "bz",
            "ca", "cc", "cf", "cg", "ch", "ci", "ck", "cl", "cm", "cn", "co", "cr", "cu", "cv", "cw", "cx",
            "cy", "cz",
            "de", "dj", "dk", "dm", "do", "dz",
            "ec", "ee", "eg", "eh", "er", "es", "et",
            "fi", "fj", "fk", "fm", "fo", "fr",
            "ga", "gb", "gd", "ge", "gf", "gg", "gh", "gi", "gl", "gm", "gn", "gp", "gq", "gr", "gs", "gt",
            "gu", "gw", "gy",
            "hk", "hm", "hn", "hr", "ht", "hu",
            "id", "ie", "il", "im", "in", "io", "iq", "ir", "is", "it",
            "je", "jm", "jo", "jp",
            "ke", "kg", "kh", "ki", "km", "kn", "kp", "kr", "kw", "ky", "kz",
            "la", "lb", "lc", "li", "lk", "lr", "ls", "lt", "lu", "lv", "ly",
            "ma", "mc", "md", "me", "mf", "mg", "mh", "mk", "ml", "mm", "mn", "mo", "mp", "mq", "mr", "ms",
            "mt", "mu", "mv", "mw", "mx", "my", "mz",
            "na", "nc", "ne", "nf", "ng", "ni", "nl", "no", "np", "nr", "nu", "nz",
            "om",
            "pa", "pe", "pf", "pg", "ph", "pk", "pl", "pm", "pn", "pr", "ps", "pt", "pw", "py",
            "qa",
            "re", "ro", "rs", "ru", "rw",
            "sa", "sb", "sc", "sd", "se", "sg", "sh", "si", "sj", "sk", "sl", "sm", "sn", "so", "sr", "ss",
            "st", "sv", "sx", "sy", "sz",
            "tc", "td", "tf", "tg", "th", "tj", "tk", "tl", "tm", "tn", "to", "tr", "tt", "tw", "tz",
            "ua", "ug", "um", "us", "uy", "uz",
            "va", "vc", "ve", "vg", "vi", "vn", "vu",
            "wf", "ws",
            "ye", "yt",
            "za", "zm", "zw",
        ),
    )
    add("uk")
}

/**
 * The "brand" component of an Android package name — the first component after
 * any recognised eTLD prefix. Returns null only for empty input. Examples:
 *  - `com.americanexpress.android.acctsvcs.us` → `americanexpress`
 *  - `com.chase.sig.android`                   → `chase`
 *  - `co.uk.bigbank.app`                       → `bigbank`
 *  - `org.fdroid.fdroid`                       → `fdroid`
 *  - `de.dwd.warnapp`                          → `dwd`
 *  - `typelauncher.app.fake0`                  → `typelauncher`
 */
internal fun brandKey(packageName: String): String? {
    val components = packageName.split('.').filter { it.isNotEmpty() }
    if (components.isEmpty()) return null
    for (parts in ETLD_PREFIXES) {
        if (components.size > parts.size && components.subList(0, parts.size) == parts) {
            return components[parts.size]
        }
    }
    return components.firstOrNull()
}

internal data class AmbiguityKey(val brand: String, val firstWord: String)

private fun ambiguityKey(packageName: String, name: String): AmbiguityKey? {
    val brand = brandKey(packageName) ?: return null
    val word = firstWord(name)
    if (word.isEmpty()) return null
    return AmbiguityKey(brand, word.lowercase())
}

private fun firstWord(name: String): String =
    name.trim().split(Regex("\\s+")).firstOrNull().orEmpty()

private fun nameSuffix(name: String): String {
    val parts = name.trim().split(Regex("\\s+"), limit = 2)
    return parts.getOrNull(1).orEmpty()
}

private fun cleanedSuffix(suffix: String): String =
    suffix.trim().trim('(', ')', '[', ']', '-', '–', '—').trim()

// True if the suffix is a recognised country tag — "UK" / "(US)" / "AU" all
// qualify, but "Cloud" / "Premium" / "TV" do not.
private fun isCountryCodeSuffix(suffix: String): Boolean {
    val cleaned = cleanedSuffix(suffix)
    if (cleaned.isEmpty()) return false
    return cleaned.lowercase() in COUNTRY_CODES
}

/**
 * Compute a short disambiguator label for each app that shares a `(brand,
 * firstWord)` key with at least one other app in the input list AND where
 * either:
 *  - at least one member's suffix is a recognised country code, or
 *  - every member has an empty suffix (the Chase-vs-Chase case, where the
 *    display names are literally identical).
 *
 * Apps that are unique, or that share a key only with peers whose suffixes
 * are ordinary English words ("Cloud" / "Music" / "Business" / "Premium"),
 * are absent from the result map.
 *
 * Within each accepted group, only members whose post-brand package tail
 * contains a country-code component receive a badge (e.g. Amex `…acctsvcs.us`
 * / `…acctsvcs.uk` / `…acctsvcs.au` → "US" / "UK" / "AU"). Members whose
 * tail has no country code are left unbadged — non-regional suffixes like
 * "sig" or "intl" do not produce a badge.
 *
 * Returned map is keyed by `InstalledApp.id`.
 */
internal fun computeDisambiguators(apps: List<InstalledApp>): Map<String, String> {
    val groups: Map<AmbiguityKey?, List<InstalledApp>> =
        apps.groupBy { ambiguityKey(it.packageName, it.name) }
    val result = mutableMapOf<String, String>()
    for ((key, group) in groups) {
        if (key == null || group.size < 2) continue
        // Same-package members of a group are the same install cloned across
        // profiles (or one activity exposed under multiple components) — the
        // work-profile badge already disambiguates the personal/work pair,
        // and any package-tail label would be identical for every member, so
        // the badge would just add noise. Skip unless we have at least two
        // distinct packages to compare.
        if (group.distinctBy { it.packageName }.size < 2) continue
        val suffixes = group.map { nameSuffix(it.name) }
        val anyCountryCode = suffixes.any { isCountryCodeSuffix(it) }
        val allEmpty = suffixes.all { cleanedSuffix(it).isEmpty() }
        if (!anyCountryCode && !allEmpty) continue
        val tails: List<Pair<InstalledApp, List<String>>> = group.map { app ->
            val components = app.packageName.split('.').filter { it.isNotEmpty() }
            // brandKey() returned `key.brand`, so it's guaranteed to appear; take
            // the components after the first occurrence.
            val brandIndex = components.indexOf(key.brand)
            val tail = if (brandIndex in 0 until (components.size - 1)) {
                components.subList(brandIndex + 1, components.size)
            } else {
                emptyList()
            }
            app to tail
        }
        val commonPrefixLen = longestCommonPrefixLength(tails.map { it.second })
        val trimmed = tails.map { (app, tail) -> app to tail.drop(commonPrefixLen) }
        for ((app, remaining) in trimmed) {
            val badge = pickDisambiguator(remaining)
            if (badge.isNotEmpty()) result[app.id] = badge
        }
    }
    return result
}

private fun longestCommonPrefixLength(lists: List<List<String>>): Int {
    if (lists.isEmpty()) return 0
    val minLen = lists.minOf { it.size }
    for (i in 0 until minLen) {
        val first = lists[0][i]
        if (lists.any { it[i] != first }) return i
    }
    return minLen
}

private fun pickDisambiguator(remainingTail: List<String>): String =
    remainingTail.firstOrNull { it.lowercase() in COUNTRY_CODES }?.uppercase() ?: ""
