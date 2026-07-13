package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

internal enum class WidgetMoveDirection { UP, DOWN }

/**
 * The identity of the widget provider behind a hosted widget, remembered at add
 * time so a widget whose binding is lost in a backup restore can be re-bound to
 * the same provider later instead of dropped. [component] and [profileSerial]
 * (the [android.os.UserManager] serial for the widget's user, used because a
 * raw `UserHandle` can't be persisted and rebuilt) together locate the provider
 * for a fresh bind; [label] is the human name shown on the restore placeholder.
 */
internal data class WidgetProviderRecord(
    val component: ComponentName,
    val profileSerial: Long,
    val label: String,
) {
    // "<serial>|<component>|<label>" — serial is numeric and a flattened
    // ComponentName never contains '|', so a limit-3 split recovers the label
    // verbatim even if it (the only free-form field) contains a '|'.
    fun serialize(): String = "$profileSerial|${component.flattenToShortString()}|$label"

    companion object {
        fun parse(value: String): WidgetProviderRecord? {
            val parts = value.split('|', limit = 3)
            if (parts.size != 3) return null
            val serial = parts[0].toLongOrNull() ?: return null
            val component = ComponentName.unflattenFromString(parts[1]) ?: return null
            return WidgetProviderRecord(component, serial, parts[2])
        }
    }
}

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

    /** The provider name to show on the restore placeholder, per widget ID. */
    val providerLabels: Map<Int, String>
        get() = widgetIds.mapNotNull { id -> providerRecord(id)?.let { id to it.label } }.toMap()

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
            sharedPreferences.edit()
                .remove(heightKey(appWidgetId))
                .remove(providerKey(appWidgetId))
                .apply()
            save()
        }
    }

    fun setCustomHeight(appWidgetId: Int, heightDp: Int) {
        sharedPreferences.edit().putInt(heightKey(appWidgetId), heightDp).apply()
    }

    /** Remembers [record] as the provider behind [appWidgetId] for later restore. */
    fun setProvider(appWidgetId: Int, record: WidgetProviderRecord) {
        sharedPreferences.edit().putString(providerKey(appWidgetId), record.serialize()).apply()
    }

    fun providerRecord(appWidgetId: Int): WidgetProviderRecord? =
        sharedPreferences.getString(providerKey(appWidgetId), null)?.let(WidgetProviderRecord::parse)

    /**
     * Rewrites every persisted widget reference from its old, backed-up ID to
     * the new ID the platform reallocated on this device, applying the mapping
     * the restore broadcast supplied (see [restoredWidgetIdMapping]). Called
     * from [WidgetRestoredReceiver] on
     * [android.appwidget.AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED].
     *
     * Page order and grouping are preserved; each ID is replaced in place. An
     * old ID absent from [oldToNew] was not restored by the platform (its
     * provider opted out of backup, or its binding didn't survive) — it names
     * no widget on this device, so it is dropped rather than left as a dead
     * card, and a page emptied by the drop is removed. Custom heights follow
     * their widget to the new ID; a dropped widget's height entry is cleaned up
     * too so the prefs file doesn't accumulate references to IDs that will
     * never resolve.
     */
    fun applyRestoredIdMapping(oldToNew: Map<Int, Int>) {
        if (oldToNew.isEmpty()) return
        val previousIds = widgetIds
        pages = pages
            .map { ids -> ids.mapNotNull { id -> oldToNew[id] } }
            .filter { ids -> ids.isNotEmpty() }
            .ensureAtLeastOnePage()
        // Snapshot every old height before touching the store, then clear all
        // old keys and write the new ones. Widget IDs are host-local and the
        // old and new ID spaces can overlap (e.g. old 2 -> new 3 while old 3 ->
        // new 4): a single interleaved read/remove/write pass would remove
        // `height_3` for old widget 3 *after* it had just been written as old
        // widget 2's new key, silently dropping old widget 2's height. Two
        // phases — capture, then remove-all-then-write-all in one editor, where
        // a later put on a key wins over an earlier remove — keeps every height
        // with its widget across an overlapping remap.
        val oldHeights = previousIds.associateWith { id -> sharedPreferences.getInt(heightKey(id), -1) }
        val oldProviders = previousIds.associateWith { id -> sharedPreferences.getString(providerKey(id), null) }
        val editor = sharedPreferences.edit()
        previousIds.forEach { oldId ->
            editor.remove(heightKey(oldId))
            editor.remove(providerKey(oldId))
        }
        for (oldId in previousIds) {
            val newId = oldToNew[oldId] ?: continue
            oldHeights.getValue(oldId).let { height -> if (height != -1) editor.putInt(heightKey(newId), height) }
            oldProviders[oldId]?.let { record -> editor.putString(providerKey(newId), record) }
        }
        editor.apply()
        save()
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

        fun providerKey(appWidgetId: Int) = "provider_$appWidgetId"
    }
}

private fun List<List<Int>>.ensureAtLeastOnePage(): List<List<Int>> =
    if (isEmpty()) listOf(emptyList()) else this
