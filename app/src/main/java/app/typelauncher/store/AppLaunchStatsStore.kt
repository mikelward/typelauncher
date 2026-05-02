package app.typelauncher

import android.content.Context

internal class AppLaunchStatsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun launchCount(appId: String): Int =
        sharedPreferences.getInt(appId.toLaunchCountKey(), 0)

    /**
     * Most-recently-launched app IDs, newest first, capped at [MAX_RECENT_APP_IDS].
     * Updated by [recordLaunch]. Used to back the dock's drag-up "recents" panel,
     * which is the launcher's best-effort substitute for the system task switcher
     * — third-party launchers can't read system recents, so this only includes
     * apps the user launched from Type Launcher.
     */
    val recentAppIds: List<String>
        get() = sharedPreferences.getString(KEY_RECENT_APP_IDS, "").orEmpty()
            .split(RECENT_APP_ID_SEPARATOR)
            .filter { id -> id.isNotBlank() }

    fun recordLaunch(appId: String) {
        val updatedRecents = (listOf(appId) + recentAppIds.filterNot { id -> id == appId })
            .take(MAX_RECENT_APP_IDS)
        sharedPreferences.edit()
            .putInt(appId.toLaunchCountKey(), launchCount(appId) + 1)
            .putString(KEY_RECENT_APP_IDS, updatedRecents.joinToString(RECENT_APP_ID_SEPARATOR))
            .apply()
    }

    fun resetLaunchCount(appId: String) {
        sharedPreferences.edit()
            .remove(appId.toLaunchCountKey())
            .apply()
    }

    /**
     * Drops [appId] from the recents list without touching its launch count —
     * the recents bar's "Dismiss" action surfaces this so the user can take an
     * app off that bar without affecting its rank in the main app list.
     * No-op if the app isn't in the list.
     */
    fun removeRecent(appId: String) {
        val current = recentAppIds
        if (appId !in current) return
        sharedPreferences.edit()
            .putString(KEY_RECENT_APP_IDS, current.filterNot { it == appId }.joinToString(RECENT_APP_ID_SEPARATOR))
            .apply()
    }

    private fun String.toLaunchCountKey(): String = "$KEY_LAUNCH_COUNT_PREFIX$this"

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
    dockedAppIds: Collection<String> = emptyList(),
    sortOrder: AppListSortOrder = AppListSortOrder.Usage,
): List<InstalledApp> {
    // Callers pass the docked app ids in `excludedAppIds` while the dock is
    // enabled and an empty collection while it's disabled — docked apps don't
    // belong in the main list when they're already rendered in the dock row,
    // but they have to reappear here when the dock UI is hidden or no other
    // surface would show them. `dockedAppIds` is the full docked set regardless
    // of dock visibility; when the dock is hidden the docked apps surface here
    // and float to the top of their bucket so the user's pinned apps stay
    // reachable instead of getting buried under usage-ranked entries.
    val candidates = if (excludedAppIds.isEmpty()) this else filterNot { app -> app.id in excludedAppIds }
    val dockedSet = dockedAppIds.toSet()
    val dockedFirst = compareByDescending<InstalledApp> { app -> app.id in dockedSet }
    return if (query.isEmpty()) {
        when (sortOrder) {
            AppListSortOrder.Usage -> candidates.sortedWith(
                dockedFirst
                    .thenByDescending { app -> appLaunchStatsStore.launchCount(app.id) }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
            )
            AppListSortOrder.Alphabetical -> candidates.sortedWith(
                dockedFirst
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
            )
        }
    } else {
        val dockedFirstByPair = compareByDescending<Pair<InstalledApp, LauncherMatchTier>> { (app, _) -> app.id in dockedSet }
        val withinTier = when (sortOrder) {
            AppListSortOrder.Usage -> dockedFirstByPair
                .thenByDescending { (app, _) -> appLaunchStatsStore.launchCount(app.id) }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { (app, _) -> app.name }
            AppListSortOrder.Alphabetical -> dockedFirstByPair
                .thenBy(String.CASE_INSENSITIVE_ORDER) { (app, _) -> app.name }
        }
        candidates
            // Match against `displayName` so the disambiguator suffix (e.g.
            // "(US)" / "(UK)") is searchable when a brand has multiple regional
            // installs. When no disambiguator is set, displayName falls back
            // to name and behaviour is unchanged.
            .mapNotNull { app -> app.displayName.launcherMatchTier(query)?.let { tier -> app to tier } }
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

/**
 * Returns the installed apps whose package currently has at least one active
 * user-visible notification, ordered oldest-first / newest-last so the bar
 * displays the freshest entry on the right edge — closest to the keyboard /
 * typing area, matching the recents row convention. When the same package has
 * both a personal-profile and a work-profile entry, both surface here — the
 * listener service reports the package, and the launcher lets the user pick
 * which profile to launch. Display-name then provides a stable tiebreak when
 * two packages share the same postTime.
 *
 * The bar shows one icon per app even if the app has multiple notifications
 * (the dot is a presence signal, not a count); the launcher takes the most
 * recent postTime across the package's user-visible notifications as the
 * sort key.
 */
internal fun List<InstalledApp>.filterNotifying(notifyingPackages: Map<String, Long>): List<InstalledApp> {
    if (notifyingPackages.isEmpty()) return emptyList()
    return filter { app -> app.packageName in notifyingPackages }
        .sortedWith(
            compareBy<InstalledApp> { app -> notifyingPackages[app.packageName] ?: 0L }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
        )
}

internal enum class LauncherMatchTier { Prefix, Anchored, Substring }

internal fun String.launcherMatchTier(query: String): LauncherMatchTier? {
    if (query.isEmpty()) return LauncherMatchTier.Prefix
    if (startsWith(query, ignoreCase = true)) return LauncherMatchTier.Prefix
    if (matchesLauncherQuery(query)) return LauncherMatchTier.Anchored
    if (contains(query, ignoreCase = true)) return LauncherMatchTier.Substring
    return null
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
