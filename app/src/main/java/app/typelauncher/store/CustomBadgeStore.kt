package app.typelauncher

import android.content.Context

/**
 * Persists per-app corner-badge overrides chosen by the user via the Edit-app
 * dialog. Stored as the literal glyph string (e.g. "🇺🇸"
 * for a US flag, "🏠" for a house) so the renderer can use it as-is
 * without re-resolving a code through the country-flag path. Storage scheme
 * and threading are documented on [StringOverrideStore].
 */
internal class CustomBadgeStore(context: Context) :
    StringOverrideStore(context, PREFERENCES_NAME, KEY_CUSTOM_BADGES) {

    fun customBadgeFor(appId: String): String? = overrideFor(appId)

    fun setBadge(appId: String, glyph: String) = setOverride(appId, glyph)

    private companion object {
        const val PREFERENCES_NAME = "custom_badges"
        const val KEY_CUSTOM_BADGES = "custom_badges"
    }
}
