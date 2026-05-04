package app.typelauncher

import android.content.Context

internal class PlayUpdateStore(context: Context) {
    private val prefs = context.getSharedPreferences("play_update", Context.MODE_PRIVATE)

    var dismissedVersionCode: Int
        get() = prefs.getInt(KEY_DISMISSED_VERSION_CODE, 0)
        set(value) {
            prefs.edit().putInt(KEY_DISMISSED_VERSION_CODE, value).apply()
        }

    companion object {
        private const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"
    }
}
