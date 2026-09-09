package app.typelauncher

import android.content.Context

internal object NotificationShade {
    private const val STATUS_BAR_SERVICE = "statusbar"
    private const val STATUS_BAR_MANAGER_CLASS = "android.app.StatusBarManager"
    private const val EXPAND_NOTIFICATIONS_PANEL_METHOD = "expandNotificationsPanel"

    fun expand(context: Context): Boolean {
        val service = context.getSystemService(STATUS_BAR_SERVICE)
        if (service == null) {
            LauncherDebugLog.warning("NotificationShade.expand: statusbar service unavailable")
            return false
        }
        return try {
            Class.forName(STATUS_BAR_MANAGER_CLASS)
                .getMethod(EXPAND_NOTIFICATIONS_PANEL_METHOD)
                .invoke(service)
            LauncherDebugLog.event("NotificationShade.expand invoked")
            true
        } catch (exception: ReflectiveOperationException) {
            LauncherDebugLog.failure(exception, "NotificationShade.expand reflective failure")
            false
        } catch (exception: SecurityException) {
            LauncherDebugLog.failure(exception, "NotificationShade.expand denied")
            false
        }
    }
}
