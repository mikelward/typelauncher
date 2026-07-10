package app.typelauncher

import android.content.Context
import android.content.SharedPreferences
import android.icu.text.MessageFormat
import android.os.LocaleList
import java.util.Locale

internal class AppLaunchStatsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val launchCounts = mutableMapOf<String, Int>()
    private var cachedRecentAppIds: List<String> = emptyList()
    private val preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
        synchronized(lock) {
            when {
                key == null -> {
                    launchCounts.clear()
                    cachedRecentAppIds = emptyList()
                }
                key == KEY_RECENT_APP_IDS -> cachedRecentAppIds = parseRecentAppIds(preferences.getString(key, "").orEmpty())
                key.startsWith(KEY_LAUNCH_COUNT_PREFIX) -> {
                    val appId = key.removePrefix(KEY_LAUNCH_COUNT_PREFIX)
                    if (preferences.contains(key)) {
                        launchCounts[appId] = preferences.getInt(key, 0)
                    } else {
                        launchCounts.remove(appId)
                    }
                }
            }
        }
    }

    init {
        sharedPreferences.all.forEach { (key, value) ->
            if (key.startsWith(KEY_LAUNCH_COUNT_PREFIX) && value is Int) {
                launchCounts[key.removePrefix(KEY_LAUNCH_COUNT_PREFIX)] = value
            }
        }
        cachedRecentAppIds = parseRecentAppIds(sharedPreferences.getString(KEY_RECENT_APP_IDS, "").orEmpty())
        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)
    }

    fun launchCount(appId: String): Int =
        synchronized(lock) { launchCounts[appId] ?: 0 }

    fun launchCountsSnapshot(): Map<String, Int> =
        synchronized(lock) { launchCounts.toMap() }

    /**
     * Most-recently-launched app IDs, newest first, capped at [MAX_RECENT_APP_IDS].
     * Updated by [recordLaunch]. Used to back the dock's drag-up "recents" panel,
     * which is the launcher's best-effort substitute for the system task switcher
     * — third-party launchers can't read system recents, so this only includes
     * apps the user launched from Type Launcher.
     */
    val recentAppIds: List<String>
        get() = synchronized(lock) { cachedRecentAppIds }

    fun recordLaunch(appId: String) {
        // Hold the lock across the full read-modify-write *including* the
        // SharedPreferences edit so two concurrent launches can't interleave a
        // stale in-memory state with a newer persisted string (or vice versa).
        // `apply()` only schedules the disk write and dispatches the change
        // listener asynchronously on the main-thread Looper, so it never
        // re-enters this lock synchronously — no deadlock.
        synchronized(lock) {
            val updatedRecents = (listOf(appId) + cachedRecentAppIds.filterNot { id -> id == appId })
                .take(MAX_RECENT_APP_IDS)
            val updatedLaunchCount = (launchCounts[appId] ?: 0) + 1
            launchCounts[appId] = updatedLaunchCount
            cachedRecentAppIds = updatedRecents
            sharedPreferences.edit()
                .putInt(appId.toLaunchCountKey(), updatedLaunchCount)
                .putString(KEY_RECENT_APP_IDS, updatedRecents.joinToString(RECENT_APP_ID_SEPARATOR))
                .apply()
        }
    }

    fun resetLaunchCount(appId: String) {
        synchronized(lock) {
            launchCounts.remove(appId)
            sharedPreferences.edit()
                .remove(appId.toLaunchCountKey())
                .apply()
        }
    }

    /**
     * Drops [appId] from the recents list without touching its launch count —
     * the recents bar's "Dismiss" action surfaces this so the user can take an
     * app off that bar without affecting its rank in the main app list.
     * No-op if the app isn't in the list.
     */
    fun removeRecent(appId: String) {
        synchronized(lock) {
            if (appId !in cachedRecentAppIds) return
            val updated = cachedRecentAppIds.filterNot { it == appId }
            cachedRecentAppIds = updated
            sharedPreferences.edit()
                .putString(KEY_RECENT_APP_IDS, updated.joinToString(RECENT_APP_ID_SEPARATOR))
                .apply()
        }
    }

    private fun String.toLaunchCountKey(): String = "$KEY_LAUNCH_COUNT_PREFIX$this"

    private fun parseRecentAppIds(raw: String): List<String> =
        raw.split(RECENT_APP_ID_SEPARATOR)
            .filter { id -> id.isNotBlank() }

    private companion object {
        const val PREFERENCES_NAME = "app_launch_stats"
        const val KEY_LAUNCH_COUNT_PREFIX = "launch_count:"
        const val KEY_RECENT_APP_IDS = "recent_app_ids"
        const val RECENT_APP_ID_SEPARATOR = "\n"
        const val MAX_RECENT_APP_IDS = 16
    }
}

internal fun List<InstalledApp>.filterByName(
    query: String,
    appLaunchStatsStore: AppLaunchStatsStore,
    excludedAppIds: Collection<String>,
    dockedAppIds: List<String> = emptyList(),
    sortOrder: AppListSortOrder = AppListSortOrder.Usage,
    // Reversed variants share the data ordering of their forward counterpart —
    // the visual flip is applied later in the UI via `reverseLayout = true`,
    // not by reversing the list here. That keeps the docked-first / tier-first
    // contracts intact and means typed search still anchors the best match at
    // index 0 (which renders at the visual bottom under reverseLayout).
    launchCounts: Map<String, Int> = when (sortOrder.dataOrdering) {
        AppListDataOrdering.Usage -> appLaunchStatsStore.launchCountsSnapshot()
        AppListDataOrdering.Alphabetical -> emptyMap()
    },
): List<InstalledApp> {
    // Callers pass the docked app ids in `excludedAppIds` while the dock is
    // enabled and an empty collection while it's disabled — docked apps don't
    // belong in the main list when they're already rendered in the dock row,
    // but they have to reappear here when the dock UI is hidden or no other
    // surface would show them. `dockedAppIds` is the full docked set regardless
    // of dock visibility; when the dock is hidden the docked apps surface here
    // and float to the top of their bucket — *in the same persisted order the
    // dock would render them in* — so the user's pinned apps stay reachable
    // instead of getting buried under usage-ranked entries and the "first
    // docked entry" muscle-memory target survives turning the dock UI off.
    val candidates = if (excludedAppIds.isEmpty()) this else filterNot { app -> app.id in excludedAppIds }
    val dockIndexById: Map<String, Int> = dockedAppIds.withIndex().associate { (index, id) -> id to index }
    val notDockedRank = Int.MAX_VALUE
    val dockedFirst = compareBy<InstalledApp> { app -> dockIndexById[app.id] ?: notDockedRank }
    // One collator snapshot for the whole refresh so the ordering stays
    // self-consistent even if the default locale changes mid-sort.
    val displayNameOrder = displayNameOrder()
    return if (query.isEmpty()) {
        when (sortOrder.dataOrdering) {
            AppListDataOrdering.Usage -> candidates.sortedWith(
                dockedFirst
                    .thenByDescending { app -> launchCounts[app.id] ?: 0 }
                    .thenBy(displayNameOrder) { app -> app.displayName },
            )
            AppListDataOrdering.Alphabetical -> candidates.sortedWith(
                dockedFirst
                    .thenBy(displayNameOrder) { app -> app.displayName },
            )
        }
    } else {
        val dockedFirstByPair = compareBy<Pair<InstalledApp, LauncherMatchTier>> { (app, _) -> dockIndexById[app.id] ?: notDockedRank }
        val withinTier = when (sortOrder.dataOrdering) {
            AppListDataOrdering.Usage -> dockedFirstByPair
                .thenByDescending { (app, _) -> launchCounts[app.id] ?: 0 }
                .thenBy(displayNameOrder) { (app, _) -> app.displayName }
            AppListDataOrdering.Alphabetical -> dockedFirstByPair
                .thenBy(displayNameOrder) { (app, _) -> app.displayName }
        }
        // Spell the digits out once for the whole refresh, not per row — a numeric
        // query otherwise re-allocates this set and re-enters the synchronized
        // digit-map lookup for every app on the keystroke path. Empty (a cheap
        // singleton) for the common non-numeric query.
        val digitSpellings = DigitSpeller.expansions(query)
        candidates
            // Match against `displayName` so the disambiguator suffix (e.g.
            // "(US)" / "(UK)") is searchable when a brand has multiple regional
            // installs. When no disambiguator is set, displayName falls back to
            // name and behaviour is unchanged. The package's brand segments are
            // also matched, so an app whose title hides its brand (e.g. Virgin
            // Money's "Credit Card" / com.virginmoney.cards) is still reachable
            // by name. For a work-profile app, the un-prefixed name is matched
            // too (workPrefixStrippedSearchName), so typing "cal" prefix-matches
            // the work "Calendar" in the same bucket as the personal copy rather
            // than being demoted to a mid-string anchored match on the visible
            // "Work Calendar". All signals are always evaluated and the *best*
            // (lowest ordinal) tier wins — rather than only checking a fallback
            // when the title misses — so the ranking stays correct even if the
            // tier order is ever changed.
            .mapNotNull { app ->
                val tier = listOfNotNull(
                    app.displayName.launcherMatchTier(query, digitSpellings),
                    app.workPrefixStrippedSearchName?.launcherMatchTier(query, digitSpellings),
                    app.packageName.packageBrandMatchTier(query),
                ).minByOrNull { it.ordinal }
                tier?.let { app to it }
            }
            .sortedWith(
                compareBy<Pair<InstalledApp, LauncherMatchTier>> { (_, tier) -> tier.ordinal }
                    .then(withinTier),
            )
            .map { (app, _) -> app }
    }
}

/**
 * Returns recently-launched apps in display order — oldest first, most recent
 * last — so the recents row renders the freshest entry on the right (next to
 * the keyboard / typing area). Storage in [AppLaunchStatsStore.recentAppIds] is
 * still most-recent-first; the reversal happens here at the display boundary.
 * Apps that no longer appear in the installed-app set drop out silently. The
 * query field is intentionally not consulted: the recents row is meant as a
 * launcher's substitute for the system task switcher and a query already
 * filters the main apps list, so re-filtering recents would either hide apps
 * the user just opened or duplicate the typed-search behaviour.
 */
internal fun List<InstalledApp>.filterRecent(recentAppIds: List<String>): List<InstalledApp> {
    if (recentAppIds.isEmpty()) return emptyList()
    val byId = associateBy { app -> app.id }
    return recentAppIds.asReversed().mapNotNull { id -> byId[id] }
}

/**
 * Returns the docked apps in their persisted insertion order, regardless of the
 * current search query. The dock is intentionally unfiltered by typed search:
 * the main app list handles query matching, and re-filtering the dock would
 * hide pinned apps the user expects to be a stable, always-tappable row. The
 * recents row uses the same convention via [filterRecent].
 */
internal fun List<InstalledApp>.filterDocked(dockedAppIds: List<String>): List<InstalledApp> =
    filter { app -> app.id in dockedAppIds }
        .sortedBy { app -> dockedAppIds.indexOf(app.id) }

/**
 * Returns the apps whose IDs appear in [hiddenAppIds], in the persisted insertion
 * order. Backs the Settings "Manage hidden apps" dialog so the user can review
 * and unhide previously hidden apps. Hidden IDs that no longer match an installed
 * app drop out silently.
 */
internal fun List<InstalledApp>.filterHidden(hiddenAppIds: List<String>): List<InstalledApp> =
    filter { app -> app.id in hiddenAppIds }
        .sortedBy { app -> hiddenAppIds.indexOf(app.id) }

// Match quality, best first — the `ordinal` drives result ranking, so order
// matters. `Fuzzy`, the `DigitWord*` band, and `PackageBrand` are the looser
// fallback tiers and sit below the precise literal title tiers so they only
// surface when nothing better matches, and never outrank a prefix/anchored/
// substring hit. The digit-word band (a numeric query matched after spelling the
// digit out, e.g. "1" -> "one") sits below the literal title tiers — a label
// that contains the digit itself always wins — but above `PackageBrand` (a
// spelled-out title match beats a package-segment match). It keeps its own
// prefix/anchored/substring split rather than collapsing to one tier, so a
// digit-word prefix like "OneDrive" for "1" still outranks an incidental
// digit-word substring like "Phone" (which carries "one") regardless of usage.
internal enum class LauncherMatchTier {
    Prefix,
    Anchored,
    Substring,
    Fuzzy,
    DigitWordPrefix,
    DigitWordAnchored,
    DigitWordSubstring,
    PackageBrand,
}

// Below this query length the fuzzy tier stays off: a 1-char fuzzy match
// reduces to "some word in the label starts with this letter", which Prefix
// and Anchored already cover, so it would only add noise. (PackageBrand has no
// such floor — see `packageBrandMatchTier`.)
private const val FUZZY_MIN_QUERY_LENGTH = 2

// Reverse-DNS / platform boilerplate that must never anchor a brand match, so
// `com.google.android.apps.maps` is searched as `google` / `maps` rather than
// the shared `com` / `android` / `apps` noise every package carries. Without
// this strip a two/three-letter query would match the prefix every package
// shares (e.g. "and" -> every com.*.android.* app).
private val GENERIC_PACKAGE_SEGMENTS = setOf(
    "com", "org", "net", "io", "co", "app", "apps", "android", "mobile",
)

internal fun String.launcherMatchTier(
    query: String,
    // The spelled-out digit candidates for [query], hoisted out of the per-app
    // loop by `filterByName` so a numeric search computes them once per keystroke
    // instead of once per row (each call would otherwise re-allocate the set and
    // re-enter the synchronized digit-map path). Defaults to computing them here
    // so direct/test callers keep the simple one-arg form.
    digitSpellings: Set<String> = DigitSpeller.expansions(query),
): LauncherMatchTier? {
    if (query.isEmpty()) return LauncherMatchTier.Prefix
    if (startsWith(query, ignoreCase = true)) return LauncherMatchTier.Prefix
    if (matchesLauncherQuery(query)) return LauncherMatchTier.Anchored
    if (contains(query, ignoreCase = true)) return LauncherMatchTier.Substring
    // Fuzzy is the strict anchored match with the skip-boundary rule relaxed:
    // the first query character still has to anchor on a word boundary, but the
    // rest may match any later characters in order. This is what lets "vw" find
    // "Volkswagen" (the 'w' is mid-word, so the strict tier rejects it). It is
    // deliberately the same loose behavior that was tightened out of the
    // *anchored* tier in commit 80c8d68 — at that time matching was a flat,
    // unranked filter, so "fa" -> "Air France" polluted the list with no way to
    // sink it. Reintroduced here only as the lowest title tier, those loose
    // matches now rank below every precise match instead of mixing in.
    if (query.length >= FUZZY_MIN_QUERY_LENGTH && matchesLauncherQueryFuzzy(query)) {
        return LauncherMatchTier.Fuzzy
    }
    // Numeric query -> spelled-out word: typing "3" reaches "Three"
    // (com.hutchison3g.planet3 / com.hutchison3g.threeplus), "1" reaches
    // "OneDrive", etc. Checked only after every literal title tier, so a label
    // that contains the digit itself always outranks the spelled-out reading,
    // and limited to the precise prefix/anchored/substring matches (no fuzzy) so
    // the looser interpretation can't add subsequence noise. The prefix/anchored/
    // substring split is preserved as its own band so a digit-word prefix always
    // outranks a digit-word substring — otherwise a frequently-launched
    // incidental substring (e.g. "Phone" for "1", "one" mid-label) could float
    // above the intended prefix ("OneDrive") under the usage sort.
    if (digitSpellings.isNotEmpty()) {
        // Precedence-first across all candidate spellings, so a prefix hit in one
        // locale always beats an anchored/substring hit in another — same band
        // ordering the literal tiers use.
        when {
            digitSpellings.any { startsWith(it, ignoreCase = true) } -> return LauncherMatchTier.DigitWordPrefix
            digitSpellings.any { matchesLauncherQuery(it) } -> return LauncherMatchTier.DigitWordAnchored
            digitSpellings.any { contains(it, ignoreCase = true) } -> return LauncherMatchTier.DigitWordSubstring
        }
    }
    return null
}

/**
 * Spells the ASCII digits of a query out as words so a numeric query can reach an
 * app whose label writes the number as a word — typing "3" finds "Three". Rather
 * than a hardcoded English table (which wouldn't scale past one language), the
 * words come from ICU's `{0,spellout}` [MessageFormat] type, which delegates to
 * the on-device `RuleBasedNumberFormat` and gives `3` -> `three` / `trois` /
 * `drei` per locale. (ICU's `RuleBasedNumberFormat` itself is `@hide` in the SDK,
 * but `MessageFormat` exposes the same spell-out without bundling the ~13 MB
 * `icu4j` artifact.)
 *
 * The locale set is the user's full preference list ([LocaleList.getDefault])
 * plus English, because app branding is overwhelmingly English even on a
 * non-English device ("Three" stays "Three" in France), so English has to be in
 * the mix regardless of the device language. A query is expanded once per locale,
 * spelling *every* digit with that locale's words, which bounds the candidate
 * count by the number of locales (no cross-product blow-up); multi-digit queries
 * are spelled digit-by-digit ("12" -> "onetwo"), which won't reach a "Twelve"
 * label but also can't mis-fire.
 *
 * Building the formatters isn't free, so the per-locale digit maps are cached and
 * only rebuilt when the device locale set changes.
 */
private object DigitSpeller {
    @Volatile private var cachedKey: String? = null
    @Volatile private var perLocaleDigits: List<Map<Char, String>> = emptyList()

    /**
     * Expansions of [query] with its digits spelled out, one per locale in the
     * current set, lowercased. Empty when [query] holds no ASCII digit — letting
     * callers skip the extra match pass on the common all-letters query.
     */
    fun expansions(query: String): Set<String> {
        if (query.none { it in '0'..'9' }) return emptySet()
        return digitMaps().mapTo(LinkedHashSet()) { digitMap ->
            buildString {
                for (ch in query) append(digitMap[ch] ?: ch.toString())
            }
        }
    }

    @Synchronized
    private fun digitMaps(): List<Map<Char, String>> {
        // Defensive: spelling a number out is a search nicety, never worth
        // crashing the app list over. If the locale lookup or ICU formatting ever
        // throws, degrade to "no digit expansion" (and cache that) rather than
        // letting it propagate into filterByName on every keystroke.
        val locales = try {
            currentLocales()
        } catch (_: Throwable) {
            return emptyList()
        }
        val key = locales.joinToString(",") { it.toLanguageTag() }
        if (key == cachedKey) return perLocaleDigits
        // Build each locale independently so one locale whose ICU spellout throws
        // drops only that locale, not the whole list — in particular the English
        // fallback that currentLocales() always appends must survive a failure in
        // some other locale, or typing "3" would stop finding an English-branded
        // "Three" for that locale set.
        perLocaleDigits = locales.mapNotNull { locale ->
            try {
                val speller = MessageFormat("{0,spellout}", locale)
                ('0'..'9').associateWith { digit ->
                    speller.format(arrayOf<Any>((digit - '0').toLong())).lowercase(locale)
                }
            } catch (_: Throwable) {
                null
            }
        }
        cachedKey = key
        return perLocaleDigits
    }

    private fun currentLocales(): List<Locale> {
        val list = LocaleList.getDefault()
        val locales = LinkedHashSet<Locale>()
        for (i in 0 until list.size()) locales.add(list[i])
        locales.add(Locale.ENGLISH)
        return locales.toList()
    }
}

/**
 * The indices of [name] that [query] matched, for the *best* (lowest-ordinal)
 * tier that hits — mirroring the precedence in [launcherMatchTier]. Used to bold
 * the matched letters in the inline search suggestion. Returns an empty list
 * when the title doesn't match at all (the package-brand case, e.g. typing
 * "virgin" to reach the "Credit Card" app whose package is `com.virginmoney.cards`):
 * there's nothing in the title to highlight, so the suggestion renders faint with
 * no bold. The tiers are checked in the same order as [launcherMatchTier] so the
 * highlighted run always corresponds to how the match actually ranked — including
 * the digit-word band, where the spelled-out form of the digit (e.g. "three" for
 * "3") is what bolds the title run, so "Three" highlights instead of rendering
 * faint like a package-brand-only match.
 */
internal fun launcherMatchHighlightIndices(name: String, query: String): List<Int> {
    if (query.isEmpty()) return emptyList()
    if (name.startsWith(query, ignoreCase = true)) return (0 until query.length).toList()
    name.anchoredMatchIndices(query)?.let { return it }
    val substringStart = name.indexOf(query, ignoreCase = true)
    if (substringStart >= 0) return (substringStart until substringStart + query.length).toList()
    if (query.length >= FUZZY_MIN_QUERY_LENGTH) {
        name.fuzzyMatchIndices(query)?.let { return it }
    }
    // Digit-word band: mirror the prefix/anchored/substring precedence of
    // launcherMatchTier against the spelled-out query so the bolded run reflects
    // the tier that selected the app. No fuzzy step here either, matching the
    // tier helper.
    val spellings = DigitSpeller.expansions(query)
    spellings.firstOrNull { name.startsWith(it, ignoreCase = true) }
        ?.let { return (0 until it.length).toList() }
    for (spelled in spellings) name.anchoredMatchIndices(spelled)?.let { return it }
    for (spelled in spellings) {
        val start = name.indexOf(spelled, ignoreCase = true)
        if (start >= 0) return (start until start + spelled.length).toList()
    }
    return emptyList()
}

/**
 * Tier for a package-name match used as a fallback when the visible label does
 * not match at all — e.g. the Virgin Money app whose title is "Credit Card" but
 * whose package is `com.virginmoney.cards`. Matches [query] as a case-insensitive
 * prefix of any brand segment, after dropping the generic reverse-DNS/platform
 * segments, and returns the lowest tier so a real title match always ranks above
 * it. Active from the first character: a short query can't flood results because
 * the Substring tier already captures every label that *contains* the query, so
 * this tier only adds labels that lack the query entirely. The only guard is for
 * an empty query, which would otherwise prefix-match every segment.
 */
internal fun String.packageBrandMatchTier(query: String): LauncherMatchTier? {
    if (query.isEmpty()) return null
    val matched = splitToSequence('.').any { segment ->
        segment.length >= query.length &&
            segment.lowercase() !in GENERIC_PACKAGE_SEGMENTS &&
            segment.startsWith(query, ignoreCase = true)
    }
    return if (matched) LauncherMatchTier.PackageBrand else null
}

internal fun String.matchesLauncherQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val firstQueryChar = query[0]
    for (anchor in indices) {
        if (!isAnchorBoundary(anchor)) continue
        if (!this[anchor].equalsIgnoreCase(firstQueryChar)) continue
        if (matchesQueryFrom(query, queryStart = 1, nameStart = anchor + 1)) return true
    }
    return false
}

/**
 * Loose variant of [matchesLauncherQuery]: the first query character still has
 * to anchor on a word boundary (start of label or an uppercase letter), but the
 * remaining characters match any later label characters in order, ignoring the
 * skip-boundary rule. Backs [LauncherMatchTier.Fuzzy].
 */
internal fun String.matchesLauncherQueryFuzzy(query: String): Boolean {
    if (query.isEmpty()) return true
    val firstQueryChar = query[0]
    for (anchor in indices) {
        if (!isAnchorBoundary(anchor)) continue
        if (!this[anchor].equalsIgnoreCase(firstQueryChar)) continue
        if (isLooseSubsequenceFrom(query, queryStart = 1, nameStart = anchor + 1)) return true
    }
    return false
}

/**
 * Index-collecting variant of [matchesLauncherQuery]: returns the matched
 * indices along the first anchor path that completes, or `null` if none does.
 * Greedy in the same way as the boolean matcher, so the highlighted run matches
 * the path that decided the tier.
 */
private fun String.anchoredMatchIndices(query: String): List<Int>? {
    if (query.isEmpty()) return emptyList()
    val firstQueryChar = query[0]
    for (anchor in indices) {
        if (!isAnchorBoundary(anchor)) continue
        if (!this[anchor].equalsIgnoreCase(firstQueryChar)) continue
        matchIndicesFrom(query, queryStart = 1, nameStart = anchor + 1)?.let { rest ->
            return listOf(anchor) + rest
        }
    }
    return null
}

/** Index-collecting variant of [matchesLauncherQueryFuzzy]. */
private fun String.fuzzyMatchIndices(query: String): List<Int>? {
    if (query.isEmpty()) return emptyList()
    val firstQueryChar = query[0]
    for (anchor in indices) {
        if (!isAnchorBoundary(anchor)) continue
        if (!this[anchor].equalsIgnoreCase(firstQueryChar)) continue
        looseSubsequenceIndicesFrom(query, queryStart = 1, nameStart = anchor + 1)?.let { rest ->
            return listOf(anchor) + rest
        }
    }
    return null
}

/** Index-collecting variant of [matchesQueryFrom]. */
private fun String.matchIndicesFrom(query: String, queryStart: Int, nameStart: Int): List<Int>? {
    val matched = mutableListOf<Int>()
    var nameIndex = nameStart
    var queryIndex = queryStart
    var skippedSinceLastMatch = false
    while (queryIndex < query.length) {
        if (nameIndex >= length) return null
        val matches = this[nameIndex].equalsIgnoreCase(query[queryIndex])
        if (matches && (!skippedSinceLastMatch || isSkipBoundary(nameIndex))) {
            matched.add(nameIndex)
            queryIndex++
            skippedSinceLastMatch = false
        } else {
            skippedSinceLastMatch = true
        }
        nameIndex++
    }
    return matched
}

/** Index-collecting variant of [isLooseSubsequenceFrom]. */
private fun String.looseSubsequenceIndicesFrom(query: String, queryStart: Int, nameStart: Int): List<Int>? {
    val matched = mutableListOf<Int>()
    var nameIndex = nameStart
    var queryIndex = queryStart
    while (queryIndex < query.length) {
        if (nameIndex >= length) return null
        if (this[nameIndex].equalsIgnoreCase(query[queryIndex])) {
            matched.add(nameIndex)
            queryIndex++
        }
        nameIndex++
    }
    return matched
}

private fun String.isLooseSubsequenceFrom(query: String, queryStart: Int, nameStart: Int): Boolean {
    var nameIndex = nameStart
    var queryIndex = queryStart
    while (queryIndex < query.length) {
        if (nameIndex >= length) return false
        if (this[nameIndex].equalsIgnoreCase(query[queryIndex])) {
            queryIndex++
        }
        nameIndex++
    }
    return true
}

private fun String.isAnchorBoundary(index: Int): Boolean {
    if (index == 0) return true
    return this[index].isUpperCase()
}

private fun String.isSkipBoundary(index: Int): Boolean {
    if (index == 0) return true
    if (this[index].isUpperCase()) return true
    return this[index - 1].isWhitespace()
}

private fun String.matchesQueryFrom(query: String, queryStart: Int, nameStart: Int): Boolean {
    var nameIndex = nameStart
    var queryIndex = queryStart
    var skippedSinceLastMatch = false
    while (queryIndex < query.length) {
        if (nameIndex >= length) return false
        val matches = this[nameIndex].equalsIgnoreCase(query[queryIndex])
        if (matches && (!skippedSinceLastMatch || isSkipBoundary(nameIndex))) {
            queryIndex++
            skippedSinceLastMatch = false
        } else {
            skippedSinceLastMatch = true
        }
        nameIndex++
    }
    return true
}

private fun Char.equalsIgnoreCase(other: Char): Boolean =
    this == other || lowercaseChar() == other.lowercaseChar()
