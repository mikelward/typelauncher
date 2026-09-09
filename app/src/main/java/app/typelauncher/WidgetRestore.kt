package app.typelauncher

import android.appwidget.AppWidgetManager

/**
 * Stable ID this launcher registers its [android.appwidget.AppWidgetHost]
 * under. It must never change: the platform keys a host's backed-up widget
 * bindings by host ID, and the restore broadcast
 * ([AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]) is only honored when its
 * [AppWidgetManager.EXTRA_HOST_ID] matches the value the host was constructed
 * with. Shared between [MainActivity] (host construction, orphan sweep) and
 * [WidgetRestoredReceiver] (restore-broadcast filtering).
 */
internal const val APP_WIDGET_HOST_ID = 1024

/**
 * Builds the old-ID → new-ID mapping the platform hands us after a cloud
 * backup restore, from the parallel arrays carried by
 * [AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]
 * ([AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS] and
 * [AppWidgetManager.EXTRA_APPWIDGET_IDS]).
 *
 * Widget IDs are host-local integers the system reallocates on the new device,
 * so the launcher's persisted references (see [WidgetStore]) still name the
 * *old* device's IDs and would resolve to nothing until remapped. The two
 * extras are index-aligned — `old[i]` became `new[i]` — so the mapping is the
 * zip, minus any pair touching [AppWidgetManager.INVALID_APPWIDGET_ID].
 *
 * Returns `null` for a **malformed** broadcast — a null array or mismatched
 * lengths — so the caller ignores it entirely. A **well-formed but empty**
 * payload (both arrays present and same length, e.g. a restore where every
 * provider opted out so nothing came back) returns an empty map, which is
 * distinct: the caller still runs it through the restore reconciliation so
 * widgets that can't be re-bound are cleaned up. An invalid ID on either side
 * of an otherwise valid pair just drops that pair. A [LinkedHashMap] preserves
 * order purely for deterministic logging and tests.
 */
internal fun restoredWidgetIdMapping(oldIds: IntArray?, newIds: IntArray?): Map<Int, Int>? {
    if (oldIds == null || newIds == null || oldIds.size != newIds.size) return null
    val mapping = LinkedHashMap<Int, Int>(oldIds.size)
    for (index in oldIds.indices) {
        val oldId = oldIds[index]
        val newId = newIds[index]
        if (oldId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            newId == AppWidgetManager.INVALID_APPWIDGET_ID
        ) {
            continue
        }
        mapping[oldId] = newId
    }
    return mapping
}
