package app.typelauncher

import android.content.Context
import org.json.JSONException
import org.json.JSONObject

/**
 * Persists per-app corner-badge overrides chosen by the user via the Edit-app
 * dialog. Stored as the literal glyph string (e.g. "🇺🇸"
 * for a US flag, "🏠" for a house) so the renderer can use it as-is
 * without re-resolving a code through the country-flag path. Keyed by
 * [InstalledApp.id], same scheme as [RenamedAppStore]: an override survives
 * package upgrades but a fresh install (which produces a new component name
 * and therefore a new id) starts unbadged again.
 *
 * Using SharedPreferences-with-a-single-JSON-blob mirrors the other small
 * per-app override stores in this package; the surface is small enough that
 * the simpler one-blob encoding outweighs the one-key-per-app alternative.
 */
internal class CustomBadgeStore(context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val customBadges: MutableMap<String, String> = loadFromPrefs().toMutableMap()

    fun customBadgeFor(appId: String): String? = customBadges[appId]

    fun setBadge(appId: String, glyph: String) {
        val trimmed = glyph.trim()
        if (trimmed.isEmpty()) {
            clear(appId)
            return
        }
        if (customBadges[appId] == trimmed) return
        customBadges[appId] = trimmed
        save()
    }

    fun clear(appId: String) {
        if (customBadges.remove(appId) != null) {
            save()
        }
    }

    private fun save() {
        val json = JSONObject()
        for ((id, glyph) in customBadges) {
            json.put(id, glyph)
        }
        sharedPreferences.edit()
            .putString(KEY_CUSTOM_BADGES, json.toString())
            .apply()
    }

    private fun loadFromPrefs(): Map<String, String> {
        val raw = sharedPreferences.getString(KEY_CUSTOM_BADGES, null) ?: return emptyMap()
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
        const val PREFERENCES_NAME = "custom_badges"
        const val KEY_CUSTOM_BADGES = "custom_badges"
    }
}
