package app.typelauncher

import android.content.Context

/**
 * Persists the package identities of the top apps from the previous session so that
 * [LauncherViewModel] can issue targeted [android.content.pm.LauncherApps.getActivityList]
 * calls on cold start and populate the dock and first-page app list before the full
 * [android.content.pm.LauncherApps.getActivityList] scan completes.
 */
internal class InstalledAppCache(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal data class CachedEntry(val packageName: String, val userHashCode: Int)

    internal fun topByUsage(): List<CachedEntry> = decode(prefs.getString(KEY_TOP_BY_USAGE, null))

    internal fun topAlphabetical(): List<CachedEntry> = decode(prefs.getString(KEY_TOP_ALPHABETICAL, null))

    internal fun update(sortedByAlpha: List<InstalledApp>, sortedByUsage: List<InstalledApp>) {
        prefs.edit()
            .putString(KEY_TOP_ALPHABETICAL, encode(sortedByAlpha.take(PRELOAD_COUNT)))
            .putString(KEY_TOP_BY_USAGE, encode(sortedByUsage.take(PRELOAD_COUNT)))
            .apply()
    }

    private fun encode(apps: List<InstalledApp>): String =
        apps.joinToString(SEPARATOR) { "${it.packageName}:${it.user.hashCode()}" }

    private fun decode(raw: String?): List<CachedEntry> =
        raw.orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val colon = line.lastIndexOf(':')
                if (colon < 1) return@mapNotNull null
                val pkg = line.substring(0, colon)
                val hash = line.substring(colon + 1).toIntOrNull() ?: return@mapNotNull null
                CachedEntry(pkg, hash)
            }

    companion object {
        private const val PREFS_NAME = "installed_app_cache"
        private const val KEY_TOP_BY_USAGE = "top_by_usage"
        private const val KEY_TOP_ALPHABETICAL = "top_alphabetical"
        private const val SEPARATOR = "\n"
        internal const val PRELOAD_COUNT = 20
    }
}
