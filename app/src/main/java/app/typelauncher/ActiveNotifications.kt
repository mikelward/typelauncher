package app.typelauncher

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory snapshot of which app packages currently have at least one active
 * notification, populated by [TypeLauncherNotificationListenerService] while
 * the system has bound it (i.e. the user has granted notification access in
 * Android settings). The launcher's notification bar reads this to decide
 * which apps to surface above the dock and to order them by recency.
 *
 * The map key is `"${user.hashCode()}:$packageName"` — encoding both the
 * profile and the package name is required because the same package can be
 * installed in the personal and work profiles simultaneously, and each
 * profile's notifications must be tracked independently so that dismissing
 * one profile's icon doesn't affect the other's bar entry. The map value is
 * the most-recent `StatusBarNotification.postTime` (ms since
 * epoch) seen across that profile+package's user-visible notifications, so
 * the bar can place the freshest entry on the right (next to the keyboard)
 * — same convention as the recents row.
 *
 * Empty until the listener connects; if the user revokes access the system
 * unbinds the service and we keep the last-known set rather than wiping it
 * (the listener will refresh on next connect). Updated only from the listener
 * service callbacks, read from any dispatcher.
 */
internal object ActiveNotifications {
    private val _packages = MutableStateFlow<Map<String, Long>>(emptyMap())
    val packages: StateFlow<Map<String, Long>> = _packages.asStateFlow()

    fun update(packages: Map<String, Long>) {
        _packages.value = packages
    }

    /**
     * Whether the user has granted Type Launcher notification listener access
     * in the system settings. Read on demand because the user can flip the
     * setting in another process while the launcher is paused; we pick the
     * fresh value up on resume rather than caching it.
     *
     * Wrapped in a defensive try/catch because the underlying call hits
     * `Settings.Secure.getString` and the system `ContentResolver`, which can
     * throw on some Robolectric configs and on locked-down OEM builds.
     * Returning `false` in that case is correct: without the grant, the bar
     * shows the access CTA — which is the right user-visible behaviour even
     * if we can't detect the grant state for sure.
     */
    fun hasListenerAccess(context: Context): Boolean =
        try {
            NotificationManagerCompat.getEnabledListenerPackages(context)
                .contains(context.packageName)
        } catch (exception: RuntimeException) {
            false
        }
}
