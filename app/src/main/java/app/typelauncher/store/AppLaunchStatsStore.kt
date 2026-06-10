package app.typelauncher

import android.content.Context
import android.content.SharedPreferences

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
        AppListSortOrder.Usage -> appLaunchStatsStore.launchCountsSnapshot()
        AppListSortOrder.Alphabetical -> emptyMap()
        else -> emptyMap()
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
    return if (query.isEmpty()) {
        when (sortOrder.dataOrdering) {
            AppListSortOrder.Usage -> candidates.sortedWith(
                dockedFirst
                    .thenByDescending { app -> launchCounts[app.id] ?: 0 }
                    .thenBy(DISPLAY_NAME_ORDER) { app -> app.displayName },
            )
            AppListSortOrder.Alphabetical -> candidates.sortedWith(
                dockedFirst
                    .thenBy(DISPLAY_NAME_ORDER) { app -> app.displayName },
            )
            else -> candidates
        }
    } else {
        val dockedFirstByPair = compareBy<Pair<InstalledApp, LauncherMatchTier>> { (app, _) -> dockIndexById[app.id] ?: notDockedRank }
        val withinTier = when (sortOrder.dataOrdering) {
            AppListSortOrder.Usage -> dockedFirstByPair
                .thenByDescending { (app, _) -> launchCounts[app.id] ?: 0 }
                .thenBy(DISPLAY_NAME_ORDER) { (app, _) -> app.displayName }
            AppListSortOrder.Alphabetical -> dockedFirstByPair
                .thenBy(DISPLAY_NAME_ORDER) { (app, _) -> app.displayName }
            else -> dockedFirstByPair
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
