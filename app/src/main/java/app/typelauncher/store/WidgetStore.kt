package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.Context

internal class WidgetStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var ids = load()

    val widgetIds: List<Int>
        get() = ids.toList()

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
            save()
        }
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
    }
}
