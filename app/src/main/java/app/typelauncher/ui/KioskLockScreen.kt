package app.typelauncher

import android.app.Activity
import android.app.KeyguardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Lets the kiosk display show over the lock screen while composed, so the
 * widgets stay up after the power button or a lock. Only the display itself:
 * the moment it leaves composition (Settings opens, kiosk mode turns off) the
 * launcher goes back to waiting behind the lock screen like any other app.
 */
@Composable
internal fun KioskOverLockScreen() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        activity?.setShowWhenLocked(true)
        LauncherDebugLog.event("kioskShowWhenLocked=true")
        onDispose {
            activity?.setShowWhenLocked(false)
            LauncherDebugLog.event("kioskShowWhenLocked=false")
        }
    }
}

/**
 * Returns a function that runs its action straight away when the device is
 * unlocked, and otherwise asks the system to unlock first (PIN, pattern,
 * fingerprint or a swipe, whatever the user set) and runs it only once that
 * succeeds. A canceled or failed unlock leaves the display as it was.
 */
@Composable
internal fun rememberKioskUnlockThen(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { action ->
            val activity = context.findActivity()
            if (activity == null) {
                action()
            } else {
                unlockThen(activity, action)
            }
        }
    }
}

internal fun unlockThen(activity: Activity, action: () -> Unit) {
    val keyguard = activity.getSystemService(KeyguardManager::class.java)
    if (keyguard == null || !keyguard.isKeyguardLocked) {
        action()
        return
    }
    keyguard.requestDismissKeyguard(
        activity,
        object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = action()

            override fun onDismissCancelled() {
                LauncherDebugLog.event("kiosk unlock canceled")
            }

            override fun onDismissError() {
                LauncherDebugLog.event("kiosk unlock failed")
            }
        },
    )
}
