package app.typelauncher

import android.os.UserHandle
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
        NotificationDismisser.attach(this)
        refreshSnapshot()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LauncherDebugLog.event("NotificationListenerService.onListenerDisconnected")
        NotificationDismisser.detach(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        refreshSnapshot()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        refreshSnapshot()
    }

    /**
     * Cancels every user-visible active notification for [packageName] under
     * [user]. The bar uses this to back its "Dismiss" action — it removes the
     * notifications the user can actually see (matches what the system shade
     * would show), so the package drops out of [ActiveNotifications] and the
     * bar updates. Ongoing/foreground-service notifications and group summaries
     * are skipped for the same reason they're hidden in the bar (they're
     * system bookkeeping, not user-visible content).
     *
     * The [user] filter is required because the same package can be installed
     * in both the personal and work profiles, and the bar can surface both
     * — long-pressing the personal icon must not cancel work-profile
     * notifications (and vice versa).
     */
    fun dismissNotificationsFor(packageName: String, user: UserHandle) {
        val keys = try {
            activeNotifications
                ?.asSequence()
                ?.filter { it.packageName == packageName && it.user == user && isUserVisible(it) }
                ?.map { it.key }
                ?.toList()
                .orEmpty()
        } catch (exception: SecurityException) {
            LauncherDebugLog.warning("NotificationListenerService.dismissNotificationsFor security", exception)
            return
        } catch (exception: RuntimeException) {
            LauncherDebugLog.warning("NotificationListenerService.dismissNotificationsFor runtime", exception)
            return
        }
        for (key in keys) {
            try {
                cancelNotification(key)
            } catch (exception: RuntimeException) {
                LauncherDebugLog.warning("NotificationListenerService.cancelNotification failed key=$key", exception)
            }
        }
    }

    private fun refreshSnapshot() {
        val packages = try {
            // Reduce to one entry per (packageName, user) pair, keeping the
            // most recent postTime. Keying by both ensures personal and work
            // profile entries are tracked independently so each profile's icon
            // appears and disappears from the bar on its own notifications.
            activeNotifications
                ?.asSequence()
                ?.filter { isUserVisible(it) }
                ?.groupingBy { it.packageName to it.user }
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

/**
 * Bridges the launcher UI to the live [TypeLauncherNotificationListenerService]
 * instance the system has bound, so per-package dismiss can call back into the
 * service without holding a reference from the ViewModel. Attached/detached
 * from the listener's connect/disconnect callbacks; while no service is bound
 * (notification access not granted, or the system unbound the listener mid-
 * shutdown) dismiss is a no-op — there's nothing to cancel.
 */
internal object NotificationDismisser {
    @Volatile
    private var service: TypeLauncherNotificationListenerService? = null

    fun attach(listener: TypeLauncherNotificationListenerService) {
        service = listener
    }

    fun detach(listener: TypeLauncherNotificationListenerService) {
        if (service === listener) service = null
    }

    fun dismissNotificationsFor(packageName: String, user: UserHandle) {
        service?.dismissNotificationsFor(packageName, user)
    }
}
