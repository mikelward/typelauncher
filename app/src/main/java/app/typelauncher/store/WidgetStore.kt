package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.Context

internal class WidgetStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var ids = load()

    val widgetIds: List<Int>
        get() = ids.toList()

    val customHeights: Map<Int, Int>
        get() = ids.mapNotNull { id ->
            val h = sharedPreferences.getInt(heightKey(id), -1)
            if (h == -1) null else id to h
        }.toMap()

    fun add(appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || appWidgetId in ids) {
            return
        }
        ids += appWidgetId
        save()
    }

    fun remove(appWidgetId: Int) {
        if (ids.contains(appWidgetId)) {
            ids = ids.filterNot { id -> id == appWidgetId }
            sharedPreferences.edit().remove(heightKey(appWidgetId)).apply()
            save()
        }
    }

    fun setCustomHeight(appWidgetId: Int, heightDp: Int) {
        sharedPreferences.edit().putInt(heightKey(appWidgetId), heightDp).apply()
    }

    private fun load(): List<Int> =
        sharedPreferences.getString(KEY_APP_WIDGET_IDS, "").orEmpty()
            .split(APP_WIDGET_ID_SEPARATOR)
            .mapNotNull { value -> value.toIntOrNull() }

    private fun save() {
        sharedPreferences.edit()
            .putString(KEY_APP_WIDGET_IDS, ids.joinToString(APP_WIDGET_ID_SEPARATOR))
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "widgets"
        const val KEY_APP_WIDGET_IDS = "app_widget_ids"
        const val APP_WIDGET_ID_SEPARATOR = "\n"

        fun heightKey(appWidgetId: Int) = "height_$appWidgetId"
    }
}
