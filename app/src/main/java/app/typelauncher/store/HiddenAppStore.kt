package app.typelauncher

import android.content.Context
import java.util.LinkedHashSet

internal class HiddenAppStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var hiddenIds = sharedPreferences.getString(KEY_HIDDEN_APP_IDS, "").orEmpty()
        .split(HIDDEN_APP_ID_SEPARATOR)
        .filter { appId -> appId.isNotBlank() }
        .toCollection(LinkedHashSet())

    val hiddenAppIds: List<String>
        get() = hiddenIds.toList()

    fun contains(appId: String): Boolean = appId in hiddenIds

    fun hide(appId: String) {
        if (appId in hiddenIds) {
            return
        }
        hiddenIds.add(appId)
        save()
    }

    fun unhide(appId: String) {
        if (hiddenIds.remove(appId)) {
            save()
        }
    }

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
