package app.typelauncher

import android.content.Context

/**
 * App-list folder settings: whether the list groups into folders, whether the
 * one-time offer to turn that on has been answered, and the folder the user
 * filed each app into.
 *
 * A filing maps an [InstalledApp.id] to an [AppCategory], or to null for "no
 * folder", which keeps an app loose even when its manifest names a category.
 * An app with no entry follows its manifest.
 */
internal class AppFolderStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Guarded by `lock`: read while building UI state, written from menu taps.
    private val lock = Any()
    private val filings: LinkedHashMap<String, AppCategory?> = parse(
        sharedPreferences.getString(KEY_ASSIGNMENTS, "").orEmpty(),
    )

    var isEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_ENABLED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** True once the user has answered the home-screen offer, either way. */
    var isOfferAnswered: Boolean
        get() = sharedPreferences.getBoolean(KEY_OFFER_ANSWERED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_OFFER_ANSWERED, value).apply()
        }

    val assignments: Map<String, AppCategory?>
        get() = synchronized(lock) { LinkedHashMap(filings) }

    /** Files [appId] into [category]; null keeps it out of every folder. */
    fun assign(appId: String, category: AppCategory?) {
        synchronized(lock) {
            if (filings.containsKey(appId) && filings[appId] == category) return
            filings[appId] = category
            save()
        }
    }

    // Callers hold `lock`.
    private fun save() {
        val raw = filings.entries.joinToString(ENTRY_SEPARATOR) { (id, category) ->
            "$id$FIELD_SEPARATOR${category?.storageKey ?: NO_FOLDER}"
        }
        sharedPreferences.edit().putString(KEY_ASSIGNMENTS, raw).apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "app_folders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OFFER_ANSWERED = "offer_answered"
        private const val KEY_ASSIGNMENTS = "assignments"

        // App ids never contain a newline (other stores split on it too) or a
        // tab; they are user hash, component and package names.
        private const val ENTRY_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "\t"
        private const val NO_FOLDER = "none"

        /**
         * Reads the persisted filings. An entry this build can't read (a
         * folder key from a newer version, or a damaged line) is dropped
         * rather than misfiling the app; it is logged, without the app id,
         * so a report shows that it happened.
         */
        fun parse(raw: String): LinkedHashMap<String, AppCategory?> {
            val result = LinkedHashMap<String, AppCategory?>()
            var skipped = 0
            for (line in raw.split(ENTRY_SEPARATOR)) {
                if (line.isBlank()) continue
                val id = line.substringBefore(FIELD_SEPARATOR, missingDelimiterValue = "")
                val key = line.substringAfter(FIELD_SEPARATOR, missingDelimiterValue = "")
                when {
                    id.isEmpty() -> skipped++
                    key == NO_FOLDER -> result[id] = null
                    else -> AppCategory.fromStorageKey(key)?.let { result[id] = it } ?: skipped++
                }
            }
            if (skipped > 0) {
                LauncherDebugLog.event("AppFolderStore skipped unreadable filings count=%s", skipped)
            }
            return result
        }
    }
}
