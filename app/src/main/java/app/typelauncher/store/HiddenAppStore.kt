package app.typelauncher

import android.content.Context
import java.util.LinkedHashSet

internal class HiddenAppStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Guarded by `lock`: `contains` / `hiddenAppIds` run on the IO dispatcher
    // during every installed-apps reload while `hide` / `unhide` run on the
    // main thread, so an unguarded set is a data race (same main-vs-IO
    // pattern the other stores in this package lock against).
    private val lock = Any()
    private val hiddenIds = sharedPreferences.getString(KEY_HIDDEN_APP_IDS, "").orEmpty()
        .split(HIDDEN_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())

    val hiddenAppIds: List<String>
        get() = synchronized(lock) { hiddenIds.toList() }

    fun contains(appId: String): Boolean = synchronized(lock) { appId in hiddenIds }

    fun hide(appId: String) {
        synchronized(lock) {
            if (hiddenIds.add(appId)) {
                save()
            }
        }
    }

    fun unhide(appId: String) {
        synchronized(lock) {
            if (hiddenIds.remove(appId)) {
                save()
            }
        }
    }

    // Callers hold `lock`, so the iteration never races a concurrent mutation.
    private fun save() {
        sharedPreferences.edit()
            .putString(KEY_HIDDEN_APP_IDS, hiddenIds.joinToString(HIDDEN_APP_ID_SEPARATOR))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "hidden_apps"
        const val KEY_HIDDEN_APP_IDS = "hidden_app_ids"
        const val HIDDEN_APP_ID_SEPARATOR = "\n"
    }
}
