package app.typelauncher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bound by the system once the user grants Type Launcher notification access in
 * Android settings. Each time a notification is posted, removed, or the listener
 * (re)connects, we refresh [ActiveNotifications] with the set of packages that
 * still have at least one user-visible notification, so the home-screen
 * notification bar can render the matching app icons.
 *
 * The launcher does not display notification content — only the fact that an app
 * has one — so we only need the package name set, not the per-notification
 * payload. If access is revoked the system unbinds this service and the bar
 * stops updating; the next time the user grants access [onListenerConnected]
 * fires and the snapshot refreshes.
 */
internal class TypeLauncherNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        LauncherDebugLog.event("NotificationListenerService.onListenerConnected")
        refreshSnapshot()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LauncherDebugLog.event("NotificationListenerService.onListenerDisconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSnapshot()
    }

    private fun refreshSnapshot() {
        val packages = try {
            // Reduce to one entry per package, keeping the most recent postTime
            // across that package's user-visible notifications. The map value is
            // what drives the bar's ordering — newest postTime on the right.
            activeNotifications
                ?.asSequence()
                ?.filter { isUserVisible(it) }
                ?.groupingBy { it.packageName }
                ?.fold(0L) { acc, sbn -> maxOf(acc, sbn.postTime) }
                ?: emptyMap()
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("NotificationListenerService.refreshSnapshot security", exception)
            return
        } catch (exception: RuntimeException) {
            // The system can throw IllegalStateException when the listener is
            // mid-disconnect; swallow rather than crashing the launcher.
            LauncherDebugLog.warning("NotificationListenerService.refreshSnapshot runtime", exception)
            return
        }
        ActiveNotifications.update(packages)
    }

    private fun isUserVisible(notification: StatusBarNotification): Boolean {
        // Skip foreground-service / ongoing notifications and group summaries —
        // they're system bookkeeping rather than something the user would think
        // of as "an unread notification on this app".
        if (notification.isOngoing) return false
        if ((notification.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0) return false
        return true
    }
}
