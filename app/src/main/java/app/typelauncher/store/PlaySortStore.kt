package app.typelauncher

import android.content.Context

/**
 * "Sort using Google Play", on the phone only: the Google Play category found
 * for each package (empty when Google Play had none), the packages still to
 * look up, the ones the user said "Not now" to, whether new apps are looked up
 * on their own, and whether the user has ever used it.
 *
 * Categories are kept as Play's own codes, not folders, so a change to how
 * codes map to folders applies to apps already looked up.
 */
internal class PlaySortStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val found: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        sharedPreferences.getString(KEY_CATEGORIES, "").orEmpty()
            .split(ENTRY_SEPARATOR)
            .filter { it.isNotEmpty() }
            .forEach { entry ->
                val packageName = entry.substringBefore(FIELD_SEPARATOR)
                if (packageName.isNotEmpty()) put(packageName, entry.substringAfter(FIELD_SEPARATOR, ""))
            }
    }

    /** Package → Play category; an empty category means Google Play had none. */
    val categories: Map<String, String>
        get() = synchronized(lock) { LinkedHashMap(found) }

    fun record(packageName: String, category: String) {
        synchronized(lock) {
            found[packageName] = category
            val raw = found.entries.joinToString(ENTRY_SEPARATOR) { (name, code) -> "$name$FIELD_SEPARATOR$code" }
            sharedPreferences.edit().putString(KEY_CATEGORIES, raw).apply()
        }
    }

    /**
     * Forgets every answer, so the apps can be looked up again. Keeps
     * [dismissed]: an app the user kept out stays out until they tick it.
     */
    fun clearCategories() {
        synchronized(lock) {
            found.clear()
            sharedPreferences.edit().remove(KEY_CATEGORIES).apply()
        }
    }

    /** Packages the user agreed to look up and that haven't been yet, in order. */
    var pending: List<String>
        get() = sharedPreferences.getString(KEY_PENDING, "").orEmpty().split(ENTRY_SEPARATOR).filter { it.isNotEmpty() }
        set(value) {
            sharedPreferences.edit().putString(KEY_PENDING, value.joinToString(ENTRY_SEPARATOR)).apply()
        }

    /** Packages offered when the user said "Not now"; the offer returns only for others. */
    var dismissed: Set<String>
        get() = sharedPreferences.getStringSet(KEY_DISMISSED, emptySet()).orEmpty().toSet()
        set(value) {
            sharedPreferences.edit().putStringSet(KEY_DISMISSED, value).apply()
        }

    /** "Sort new apps using Google Play". */
    var isAutoEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AUTO, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_AUTO, value).apply()
        }

    /** True once the user has sorted with Google Play, which brings its Settings rows. */
    var hasUsed: Boolean
        get() = sharedPreferences.getBoolean(KEY_USED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_USED, value).apply()
        }

    internal companion object {
        const val PREFERENCES_NAME = "play_sort"
        private const val KEY_CATEGORIES = "categories"
        private const val KEY_PENDING = "pending"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_AUTO = "auto"
        private const val KEY_USED = "used"

        // Package names hold only letters, digits, '_' and '.'.
        private const val ENTRY_SEPARATOR = ","
        private const val FIELD_SEPARATOR = "="
    }
}
