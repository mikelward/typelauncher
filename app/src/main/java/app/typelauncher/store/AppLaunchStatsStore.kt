package app.typelauncher

import android.content.Context

internal class AppLaunchStatsStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun launchCount(appId: String): Int =
        sharedPreferences.getInt(appId.toLaunchCountKey(), 0)

    fun recordLaunch(appId: String) {
        sharedPreferences.edit()
            .putInt(appId.toLaunchCountKey(), launchCount(appId) + 1)
            .apply()
    }

    fun resetLaunchCount(appId: String) {
        sharedPreferences.edit()
            .remove(appId.toLaunchCountKey())
            .apply()
    }

    private fun String.toLaunchCountKey(): String = "$KEY_LAUNCH_COUNT_PREFIX$this"

    private companion object {
        const val PREFERENCES_NAME = "app_launch_stats"
        const val KEY_LAUNCH_COUNT_PREFIX = "launch_count:"
    }
}

internal fun List<InstalledApp>.filterByName(
    query: String,
    appLaunchStatsStore: AppLaunchStatsStore,
    downrankedAppIds: Collection<String>,
    sortOrder: AppListSortOrder = AppListSortOrder.Usage,
): List<InstalledApp> =
    if (query.isEmpty()) {
        when (sortOrder) {
            AppListSortOrder.Usage -> sortedWith(
                compareByDescending<InstalledApp> { app ->
                    if (app.id in downrankedAppIds) DOCKED_APP_LIST_RANK else appLaunchStatsStore.launchCount(app.id)
                }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
            )
            AppListSortOrder.Alphabetical -> sortedWith(
                compareBy<InstalledApp> { app -> if (app.id in downrankedAppIds) 1 else 0 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
            )
        }
    } else {
        filter { app -> app.name.matchesLauncherQuery(query) }
    }

internal fun List<InstalledApp>.filterDockedByName(dockedAppIds: List<String>, query: String): List<InstalledApp> =
    filter { app -> app.id in dockedAppIds }
        .let { dockedApps ->
            if (query.isEmpty()) {
                dockedApps
            } else {
                dockedApps.filter { app -> app.name.matchesLauncherQuery(query) }
            }
        }
        .sortedBy { app -> dockedAppIds.indexOf(app.id) }

internal fun String.matchesLauncherQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    val firstQueryChar = query[0]
    for (anchor in indices) {
        if (!isWordBoundary(anchor)) continue
        if (!this[anchor].equalsIgnoreCase(firstQueryChar)) continue
        if (containsSubsequenceFrom(query, queryStart = 1, nameStart = anchor + 1)) return true
    }
    return false
}

private fun String.isWordBoundary(index: Int): Boolean {
    if (index == 0) return true
    return this[index].isUpperCase()
}

private fun String.containsSubsequenceFrom(query: String, queryStart: Int, nameStart: Int): Boolean {
    var nameIndex = nameStart
    var queryIndex = queryStart
    while (queryIndex < query.length) {
        if (nameIndex >= length) return false
        if (this[nameIndex].equalsIgnoreCase(query[queryIndex])) queryIndex++
        nameIndex++
    }
    return true
}

private fun Char.equalsIgnoreCase(other: Char): Boolean =
    this == other || lowercaseChar() == other.lowercaseChar()

private const val DOCKED_APP_LIST_RANK = -1
