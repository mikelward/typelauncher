package app.typelauncher

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles the widget half of a backup restore. The launcher's other persisted
 * state (docked apps, hidden apps, renames, icon overrides) is keyed by stable
 * package / app IDs and survives Android's default auto-backup untouched, but
 * widgets are referenced by host-local integer IDs the platform reallocates on
 * the new device — so their [WidgetStore] entries alone would name IDs that
 * resolve to no bound widget.
 *
 * After restoring this app's data the platform reallocates the host's widget
 * bindings and broadcasts [AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]
 * with the old→new ID translation. This receiver applies it to [WidgetStore]
 * so the restored widgets reappear in their original pages and order under
 * their new IDs. It is declared in the manifest (not registered at runtime) so
 * it is delivered even though the launcher process isn't running when the
 * restore completes.
 *
 * The action is a framework `protected-broadcast`, so only the system can send
 * it; the receiver additionally checks [AppWidgetManager.EXTRA_HOST_ID] against
 * [APP_WIDGET_HOST_ID] and ignores anything for another host, and
 * [restoredWidgetIdMapping] discards a malformed payload — a bad broadcast is a
 * no-op, never a scramble of the user's layout.
 */
internal class WidgetRestoredReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED) return
        val hostId = intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1)
        if (hostId != APP_WIDGET_HOST_ID) {
            LauncherDebugLog.event("WidgetRestoredReceiver ignoring hostId=%s", hostId)
            return
        }
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS)
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        // null = malformed payload (ignore); an empty (but well-formed) map is a
        // real restore that brought no widget back — still reconciled below so
        // widgets that can't be re-bound are cleaned up rather than stranded.
        val mapping = restoredWidgetIdMapping(oldIds, newIds)
        if (mapping == null) {
            LauncherDebugLog.warning("WidgetRestoredReceiver ignoring malformed restore payload hostId=%s", hostId)
            return
        }
        LauncherDebugLog.event(
            "WidgetRestoredReceiver hostId=%s restored %s widget id(s) mapping=%s",
            hostId,
            mapping.size,
            mapping,
        )
        // Runs on the main thread; the store's constructor read and single
        // `apply()` are cheap, and the framework flushes pending writes before
        // tearing the receiver's process down (QueuedWork on receiver finish),
        // so the remap is durable even if the process dies right after restore.
        WidgetStore(context.applicationContext).applyRestoredIdMapping(mapping)
    }
}
