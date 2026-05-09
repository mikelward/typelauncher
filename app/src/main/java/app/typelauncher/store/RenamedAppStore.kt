package app.typelauncher

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists per-app display-name overrides so the user can rename a launcher
 * entry whose system label is misleading (e.g. a "ChatGPT" web PWA they use as
 * "Codex"). Keyed by [InstalledApp.id] so an override survives package
 * upgrades but not a fresh install (which produces a new component name and
 * therefore a new id). Names are stored as a single JSON object string in
 * SharedPreferences; the surface is small enough that the simpler one-blob
 * encoding outweighs the one-key-per-app alternative.
 */
internal class RenamedAppStore(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val customNames: MutableMap<String, String> = loadFromPrefs().toMutableMap()

    fun customNameFor(appId: String): String? = customNames[appId]

    fun rename(appId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            clear(appId)
            return
        }
        if (customNames[appId] == trimmed) return
        customNames[appId] = trimmed
        save()
    }

    fun clear(appId: String) {
        if (customNames.remove(appId) != null) {
            save()
        }
    }

    private fun save() {
        val json = JSONObject()
        for ((id, name) in customNames) {
            json.put(id, name)
        }
        sharedPreferences.edit()
            .putString(KEY_CUSTOM_NAMES, json.toString())
            .apply()
    }

    private fun loadFromPrefs(): Map<String, String> {
        val raw = sharedPreferences.getString(KEY_CUSTOM_NAMES, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap(obj.length()) {
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = obj.optString(key, "")
                    if (value.isNotEmpty()) put(key, value)
                }
            }
        } catch (_: JSONException) {
            emptyMap()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "renamed_apps"
        const val KEY_CUSTOM_NAMES = "custom_names"
    }
}
