package app.typelauncher

import android.content.Context

/**
 * App-list folder settings: whether the list groups into folders, whether the
 * one-time offer to turn that on has been answered, the folders the user made
 * or deleted, and the folder the user filed each app into. Also "Sort with
 * on-device AI": whether it's on, whether its offer has been answered, and the
 * folder it chose for each app.
 *
 * A filing maps an [InstalledApp.id] to a folder key ([AppFolderDef.key]), or
 * to null for "no folder", which keeps an app loose even when its manifest
 * names a category. An app with no entry follows the AI's choice, then its
 * manifest. The AI's choices are kept apart from the user's so "Re-sort all
 * apps" can drop them and keep the user's.
 */
internal class AppFolderStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Guarded by `lock`: read while building UI state, written from menu taps.
    private val lock = Any()
    private val filings: LinkedHashMap<String, String?> = parse(
        sharedPreferences.getString(KEY_ASSIGNMENTS, "").orEmpty(),
    )
    private val aiFilings: LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        for ((id, key) in parse(sharedPreferences.getString(KEY_AI_ASSIGNMENTS, "").orEmpty())) {
            if (key != null) put(id, key)
        }
    }
    private val customFolders: MutableList<AppFolderDef> = parseFolders(
        sharedPreferences.getString(KEY_CUSTOM_FOLDERS, "").orEmpty(),
    )
    private val deletedBuiltIns: MutableSet<String> =
        sharedPreferences.getStringSet(KEY_DELETED_FOLDERS, emptySet()).orEmpty().toMutableSet()

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

    val assignments: Map<String, String?>
        get() = synchronized(lock) { LinkedHashMap(filings) }

    /** The folders in use, in list order (see [activeAppFolders]). */
    val folders: List<AppFolderDef>
        get() = synchronized(lock) { activeAppFolders(customFolders.toList(), deletedBuiltIns.toSet()) }

    /** Whether any built-in folder has been deleted, so there's something to restore. */
    val hasDeletedBuiltIns: Boolean
        get() = synchronized(lock) { deletedBuiltIns.isNotEmpty() }

    /** Makes a folder named [name] with [icon] and returns it. */
    fun createFolder(name: String, icon: FolderIcon): AppFolderDef = synchronized(lock) {
        val id = sharedPreferences.getInt(KEY_NEXT_FOLDER_ID, 1)
        val folder = AppFolderDef("$CUSTOM_KEY_PREFIX$id", null, cleanFolderName(name), icon)
        customFolders += folder
        sharedPreferences.edit().putInt(KEY_NEXT_FOLDER_ID, id + 1).apply()
        saveFolders()
        folder
    }

    /** Renames a folder the user made, or changes its icon. */
    fun editFolder(key: String, name: String, icon: FolderIcon) {
        synchronized(lock) {
            val index = customFolders.indexOfFirst { it.key == key }
            if (index < 0) return
            customFolders[index] = customFolders[index].copy(name = cleanFolderName(name), icon = icon)
            saveFolders()
        }
    }

    /**
     * Deletes the folder [key], built-in or the user's own; Other can't be
     * deleted. Filings into it, the user's and the AI's, are dropped, so its
     * apps are sorted again.
     */
    fun deleteFolder(key: String) {
        if (key == AppCategory.Other.storageKey) return
        synchronized(lock) {
            if (AppCategory.fromStorageKey(key) != null) {
                deletedBuiltIns += key
                sharedPreferences.edit().putStringSet(KEY_DELETED_FOLDERS, deletedBuiltIns.toSet()).apply()
            } else if (customFolders.removeAll { it.key == key }) {
                saveFolders()
            }
            if (filings.values.removeAll { it == key }) save()
            if (aiFilings.values.removeAll { it == key }) saveAi()
        }
    }

    /** Brings back every built-in folder the user deleted. */
    fun restoreBuiltInFolders() {
        synchronized(lock) {
            if (deletedBuiltIns.isEmpty()) return
            deletedBuiltIns.clear()
            sharedPreferences.edit().remove(KEY_DELETED_FOLDERS).apply()
        }
    }

    var isAiSortEnabled: Boolean
        get() = sharedPreferences.getBoolean(KEY_AI_ENABLED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_AI_ENABLED, value).apply()
        }

    /** True once the user has answered the offer at the top of Other, either way. */
    var isAiOfferAnswered: Boolean
        get() = sharedPreferences.getBoolean(KEY_AI_OFFER_ANSWERED, false)
        set(value) {
            sharedPreferences.edit().putBoolean(KEY_AI_OFFER_ANSWERED, value).apply()
        }

    /** The folder the AI chose for each app it has sorted. */
    val aiAssignments: Map<String, String>
        get() = synchronized(lock) { LinkedHashMap(aiFilings) }

    /** Records the AI's choices for one batch. */
    fun assignByAi(choices: Map<String, String>) {
        if (choices.isEmpty()) return
        synchronized(lock) {
            aiFilings.putAll(choices)
            saveAi()
        }
    }

    /**
     * Forgets the AI's choices if they were made under different instructions
     * than [promptVersion] (a new folder, or a reworded description), so the
     * apps are sorted again under the new ones; the user's filings are kept.
     * Returns whether it forgot them.
     */
    fun forgetAiChoicesUnlessMadeBy(promptVersion: Int): Boolean = synchronized(lock) {
        val stored = sharedPreferences.getInt(KEY_AI_PROMPT_VERSION, 0)
        if (stored == promptVersion) return@synchronized false
        sharedPreferences.edit().putInt(KEY_AI_PROMPT_VERSION, promptVersion).apply()
        if (aiFilings.isEmpty()) return@synchronized false
        aiFilings.clear()
        saveAi()
        true
    }

    /** Forgets every choice the AI made, so it sorts again from scratch. */
    fun clearAiAssignments() {
        synchronized(lock) {
            if (aiFilings.isEmpty()) return
            aiFilings.clear()
            saveAi()
        }
    }

    /** Files [appId] into the folder [key]; null keeps it out of every folder. */
    fun assign(appId: String, key: String?) {
        synchronized(lock) {
            if (filings.containsKey(appId) && filings[appId] == key) return
            filings[appId] = key
            save()
        }
    }

    // Callers hold `lock`.
    private fun saveAi() {
        val raw = aiFilings.entries.joinToString(ENTRY_SEPARATOR) { (id, key) -> "$id$FIELD_SEPARATOR$key" }
        sharedPreferences.edit().putString(KEY_AI_ASSIGNMENTS, raw).apply()
    }

    // Callers hold `lock`.
    private fun save() {
        val raw = filings.entries.joinToString(ENTRY_SEPARATOR) { (id, key) ->
            "$id$FIELD_SEPARATOR${key ?: NO_FOLDER}"
        }
        sharedPreferences.edit().putString(KEY_ASSIGNMENTS, raw).apply()
    }

    // Callers hold `lock`. One line per folder: key, icon, then the name.
    private fun saveFolders() {
        val raw = customFolders.joinToString(ENTRY_SEPARATOR) { folder ->
            listOf(folder.key, folder.icon.storageKey, folder.name).joinToString(FIELD_SEPARATOR)
        }
        sharedPreferences.edit().putString(KEY_CUSTOM_FOLDERS, raw).apply()
    }

    internal companion object {
        const val PREFERENCES_NAME = "app_folders"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_OFFER_ANSWERED = "offer_answered"
        private const val KEY_ASSIGNMENTS = "assignments"
        private const val KEY_AI_ENABLED = "ai_enabled"
        private const val KEY_AI_OFFER_ANSWERED = "ai_offer_answered"
        private const val KEY_AI_ASSIGNMENTS = "ai_assignments"
        private const val KEY_AI_PROMPT_VERSION = "ai_prompt_version"
        private const val KEY_CUSTOM_FOLDERS = "custom_folders"
        private const val KEY_DELETED_FOLDERS = "deleted_folders"
        private const val KEY_NEXT_FOLDER_ID = "next_folder_id"
        private const val CUSTOM_KEY_PREFIX = "custom-"

        // A name is one line, so it can't break the stored format, and short.
        private fun cleanFolderName(name: String): String =
            name.replace(Regex("""\s+"""), " ").trim().take(MAX_FOLDER_NAME_CHARS)

        private const val MAX_FOLDER_NAME_CHARS = 40

        // App ids never contain a newline (other stores split on it too) or a
        // tab; they are user hash, component and package names.
        private const val ENTRY_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "\t"
        private const val NO_FOLDER = "none"

        /**
         * Reads the persisted filings. A damaged line is dropped rather than
         * misfiling the app; it is logged, without the app id, so a report
         * shows that it happened. A key naming no folder in use (one since
         * deleted, say) is kept and passed over when grouping.
         */
        fun parse(raw: String): LinkedHashMap<String, String?> {
            val result = LinkedHashMap<String, String?>()
            var skipped = 0
            for (line in raw.split(ENTRY_SEPARATOR)) {
                if (line.isBlank()) continue
                val id = line.substringBefore(FIELD_SEPARATOR, missingDelimiterValue = "")
                val key = line.substringAfter(FIELD_SEPARATOR, missingDelimiterValue = "")
                when {
                    id.isEmpty() || key.isEmpty() -> skipped++
                    key == NO_FOLDER -> result[id] = null
                    else -> result[id] = key
                }
            }
            if (skipped > 0) {
                LauncherDebugLog.event("AppFolderStore skipped unreadable filings count=%s", skipped)
            }
            return result
        }

        /** Reads the user's folders; a damaged line is dropped and logged as a count. */
        fun parseFolders(raw: String): MutableList<AppFolderDef> {
            val result = mutableListOf<AppFolderDef>()
            var skipped = 0
            for (line in raw.split(ENTRY_SEPARATOR)) {
                if (line.isBlank()) continue
                val fields = line.split(FIELD_SEPARATOR, limit = 3)
                val key = fields.getOrNull(0).orEmpty()
                val icon = fields.getOrNull(1)?.let(FolderIcon::fromStorageKey) ?: FolderIcon.Folder
                val name = fields.getOrNull(2).orEmpty()
                if (!key.startsWith(CUSTOM_KEY_PREFIX) || name.isBlank()) {
                    skipped++
                    continue
                }
                result += AppFolderDef(key, null, name, icon)
            }
            if (skipped > 0) {
                LauncherDebugLog.event("AppFolderStore skipped unreadable folders count=%s", skipped)
            }
            return result
        }
    }
}
