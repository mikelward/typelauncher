package app.typelauncher

import android.appwidget.AppWidgetManager

/**
 * Computes the widget IDs the [android.appwidget.AppWidgetHost] has allocated
 * that the launcher no longer tracks, so a startup pass can delete them and
 * release their bound instances in `AppWidgetService`.
 *
 * Two leak classes accumulate over a device's lifetime, both unswept until now:
 *  - **Crashes / process death mid-add.** [WidgetAddFlow] deletes the allocated
 *    ID on every abandoned path, but if the launcher is killed *during* the
 *    bind → configure → add handshake the in-flight ID is already allocated on
 *    the host yet never persisted to [WidgetStore].
 *  - **Configure-cancel predating the fix.** Widgets bound and left allocated
 *    by the historical configure-cancel bug stay allocated forever; nothing
 *    ever reconciles them away.
 *
 * The pass compares the host's allocated IDs against [knownWidgetIds] (the
 * persisted set the launcher renders) and returns the difference. Two in-flight
 * IDs are always excluded so a reconciliation that races an active add can't
 * delete a widget mid-handshake:
 *  - [pendingWidgetId] — the ID of a bind/configure currently in flight.
 *  - [bindingWidgetId] — an ID that has been allocated but whose bind IPC has
 *    not yet completed, so it isn't `pendingWidgetId` yet. `bindWidget` runs
 *    that IPC off the main thread and only marks the ID pending afterwards
 *    (marking it earlier would let a cancelled coroutine persist an
 *    unresolvable pending ID), which opens a window where the ID is allocated
 *    on the host but not yet tracked anywhere the sweep would spare.
 *
 * At cold start both are `INVALID_APPWIDGET_ID` and exclude nothing.
 */
internal fun orphanedAllocatedWidgetIds(
    allocatedIds: IntArray,
    knownWidgetIds: Collection<Int>,
    pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    bindingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
): List<Int> {
    val known = knownWidgetIds.toHashSet()
    return allocatedIds.filter { id ->
        id != AppWidgetManager.INVALID_APPWIDGET_ID &&
            id != pendingWidgetId &&
            id != bindingWidgetId &&
            id !in known
    }
}
