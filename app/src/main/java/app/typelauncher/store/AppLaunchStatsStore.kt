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
    dockedAppIds: Collection<String>,
): List<InstalledApp> =
    if (query.isEmpty()) {
        sortedWith(
            compareByDescending<InstalledApp> { app ->
                if (app.id in dockedAppIds) DOCKED_APP_LIST_RANK else appLaunchStatsStore.launchCount(app.id)
            }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { app -> app.name },
        )
    } else {
        filter { app -> app.name.contains(query, ignoreCase = true) }
    }

internal fun List<InstalledApp>.filterDockedByName(dockedAppIds: List<String>, query: String): List<InstalledApp> =
    filter { app -> app.id in dockedAppIds }
        .let { dockedApps ->
            if (query.isEmpty()) {
                dockedApps
            } else {
                dockedApps.filter { app -> app.name.contains(query, ignoreCase = true) }
            }
        }
        .sortedBy { app -> dockedAppIds.indexOf(app.id) }

private const val DOCKED_APP_LIST_RANK = -1
