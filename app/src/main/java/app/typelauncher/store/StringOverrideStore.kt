package app.typelauncher

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Shared implementation for the small per-app string-override stores
 * ([RenamedAppStore], [CustomBadgeStore]). Each store keeps a single
 * appId → value map, persisted as one JSON object string in its own
 * SharedPreferences file; the surface is small enough that the simpler
 * one-blob encoding outweighs the one-key-per-app alternative.
 *
 * Entries are keyed by [InstalledApp.id] so an override survives package
 * upgrades but not a fresh install (which produces a new component name and
 * therefore a new id).
 *
 * Reads run on the IO dispatcher during every installed-apps reload while
 * writes run on the main thread from the Edit-app dialog, so every access
 * synchronizes on [lock] (same main-vs-IO pattern IconOverrideStore locks
 * against).
 */
internal abstract class StringOverrideStore(
    context: Context,
    preferencesName: String,
    private val key: String,
) {
    private val sharedPreferences =
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    private val lock = Any()
    private val overrides: MutableMap<String, String> = loadFromPrefs().toMutableMap()

    protected fun overrideFor(appId: String): String? = synchronized(lock) { overrides[appId] }

    /** Stores [value] trimmed; a blank [value] clears the override instead. */
    protected fun setOverride(appId: String, value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clear(appId)
            return
        }
        synchronized(lock) {
            if (overrides[appId] == trimmed) return
            overrides[appId] = trimmed
            save()
        }
    }

    fun clear(appId: String) {
        synchronized(lock) {
            if (overrides.remove(appId) != null) {
                save()
            }
        }
    }

    // Callers hold `lock`, so the iteration never races a concurrent mutation.
    private fun save() {
        val json = JSONObject()
        for ((id, value) in overrides) {
            json.put(id, value)
        }
        sharedPreferences.edit()
            .putString(key, json.toString())
            .apply()
    }

    private fun loadFromPrefs(): Map<String, String> {
        val raw = sharedPreferences.getString(key, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap(obj.length()) {
                val ids = obj.keys()
                while (ids.hasNext()) {
                    val id = ids.next()
                    val value = obj.optString(id, "")
                    if (value.isNotEmpty()) put(id, value)
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }
}
