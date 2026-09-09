package app.typelauncher

import android.content.Context

/**
 * Persists per-app display-name overrides so the user can rename a launcher
 * entry whose system label is misleading (e.g. a "ChatGPT" web PWA they use as
 * "Codex"). Storage scheme and threading are documented on
 * [StringOverrideStore].
 */
internal class RenamedAppStore(context: Context) :
    StringOverrideStore(context, PREFERENCES_NAME, KEY_CUSTOM_NAMES) {

    fun customNameFor(appId: String): String? = overrideFor(appId)

    fun rename(appId: String, newName: String) = setOverride(appId, newName)

    private companion object {
        const val PREFERENCES_NAME = "renamed_apps"
        const val KEY_CUSTOM_NAMES = "custom_names"
    }
}
