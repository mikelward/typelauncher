package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.Context

internal enum class WidgetMoveDirection { UP, DOWN }

internal class WidgetStore(context: Context) {
    private val sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private var pages = loadPages()

    val widgetIds: List<Int>
        get() = pages.flatten()

    val widgetPages: List<List<Int>>
        get() = pages.map { ids -> ids.toList() }

    val customHeights: Map<Int, Int>
        get() = widgetIds.mapNotNull { id ->
            val h = sharedPreferences.getInt(heightKey(id), -1)
            if (h == -1) null else id to h
        }.toMap()

    fun add(appWidgetId: Int, pageIndex: Int = pages.lastIndex, addToNewPageAfter: Boolean = false) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID || appWidgetId in widgetIds) {
            return
        }
        val targetPage = pageIndex.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
        pages = if (addToNewPageAfter) {
            val insertionIndex = (targetPage + 1).coerceAtMost(pages.size)
            pages.toMutableList().apply { add(insertionIndex, listOf(appWidgetId)) }
        } else {
            pages.ensureAtLeastOnePage().mapIndexed { index, ids ->
                if (index == targetPage) ids + appWidgetId else ids
            }
        }
        save()
    }

    fun remove(appWidgetId: Int) {
        if (widgetIds.contains(appWidgetId)) {
            pages = pages
                .map { ids -> ids.filterNot { id -> id == appWidgetId } }
                .filter { ids -> ids.isNotEmpty() }
                .ensureAtLeastOnePage()
            sharedPreferences.edit().remove(heightKey(appWidgetId)).apply()
            save()
        }
    }

    fun setCustomHeight(appWidgetId: Int, heightDp: Int) {
        sharedPreferences.edit().putInt(heightKey(appWidgetId), heightDp).apply()
    }

    /**
     * Moves [appWidgetId] one slot up or down within the page it currently
     * lives on. No-op if the widget is unknown or already at the page edge in
     * the requested direction — moving widgets across pages is intentionally
     * out of scope, so a widget at the top of a page cannot leave it via
     * [WidgetMoveDirection.UP].
     */
    fun move(appWidgetId: Int, direction: WidgetMoveDirection) {
        val pageIndex = pages.indexOfFirst { ids -> ids.contains(appWidgetId) }
        if (pageIndex == -1) return
        val page = pages[pageIndex]
        val fromIndex = page.indexOf(appWidgetId)
        val toIndex = when (direction) {
            WidgetMoveDirection.UP -> fromIndex - 1
            WidgetMoveDirection.DOWN -> fromIndex + 1
        }
        if (toIndex !in page.indices) return
        val reorderedPage = page.toMutableList().apply {
            this[fromIndex] = set(toIndex, this[fromIndex])
        }
        pages = pages.toMutableList().apply { this[pageIndex] = reorderedPage }
        save()
    }

    private fun loadLegacyIds(): List<Int> =
        sharedPreferences.getString(KEY_APP_WIDGET_IDS, "").orEmpty()
            .split(APP_WIDGET_ID_SEPARATOR)
            .mapNotNull { value -> value.toIntOrNull() }

    private fun loadPages(): List<List<Int>> {
        val persistedPages = sharedPreferences.getString(KEY_APP_WIDGET_PAGES, null)
            ?.split(APP_WIDGET_PAGE_SEPARATOR)
            ?.map { page ->
                page.split(APP_WIDGET_ID_SEPARATOR)
                    .mapNotNull { value -> value.toIntOrNull() }
            }
            ?.filter { ids -> ids.isNotEmpty() }
        return (persistedPages ?: listOf(loadLegacyIds()))
            .filter { ids -> ids.isNotEmpty() }
            .ensureAtLeastOnePage()
    }

    private fun save() {
        val flatIds = widgetIds
        sharedPreferences.edit()
            .putString(KEY_APP_WIDGET_IDS, flatIds.joinToString(APP_WIDGET_ID_SEPARATOR))
            .putString(
                KEY_APP_WIDGET_PAGES,
                pages.joinToString(APP_WIDGET_PAGE_SEPARATOR) { ids ->
                    ids.joinToString(APP_WIDGET_ID_SEPARATOR)
                },
            )
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "widgets"
        const val KEY_APP_WIDGET_IDS = "app_widget_ids"
        const val KEY_APP_WIDGET_PAGES = "app_widget_pages"
        const val APP_WIDGET_ID_SEPARATOR = "\n"
        const val APP_WIDGET_PAGE_SEPARATOR = "\n\n"

        fun heightKey(appWidgetId: Int) = "height_$appWidgetId"
    }
}

private fun List<List<Int>>.ensureAtLeastOnePage(): List<List<Int>> =
    if (isEmpty()) listOf(emptyList()) else this
