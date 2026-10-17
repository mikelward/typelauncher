package app.typelauncher

import android.app.KeyguardManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** What [KioskLock.lock] did, for the debug log. */
internal enum class KioskLockResult {
    /** Locked, and the screen woken back up if it was on. */
    Locked,

    /** Already locked: nothing to do. */
    AlreadyLocked,

    /** The user hasn't let kiosk mode lock the device, or took that back. */
    NotGranted,

    /** No PIN, pattern or password, so locking would only turn the screen off. */
    NoScreenLock,

    /** Android refused the lock even though the grant looked active. */
    Refused,
}

/**
 * Kiosk mode's "Lock screen automatically": the device's own lock screen and
 * its PIN, pattern or password, put back whenever the display comes back, so
 * a wall display never sits unlocked and stepping out always asks to unlock
 * (maintainer, 2026-10-16 and 2026-10-17). Android lets an app lock the device
 * only as a device admin with the force-lock policy, which the user grants as
 * the switch turns on ([grantIntent]) and which is given back as it, or kiosk
 * mode, turns off ([release]).
 *
 * Every call but [grantIntent] is a binder call, so callers run them off the
 * main thread.
 */
internal class KioskLock(
    context: Context,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    // Android's removal, swappable so a test can hold it pending as a device does.
    private val removeAdmin: (DevicePolicyManager, ComponentName) -> Unit = { policy, admin ->
        policy.removeActiveAdmin(admin)
    },
    // Starts KioskWakeActivity, the way to turn the screen back on that needs
    // no permission; the caller, an activity, can start it over the display.
    private val startWake: () -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val admin = ComponentName(appContext, KioskLockAdminReceiver::class.java)

    @WorkerThread
    fun isGranted(): Boolean = appContext.getSystemService(DevicePolicyManager::class.java)
        ?.isAdminActive(admin) == true

    /** Whether there's a PIN, pattern or password to lock with. */
    @WorkerThread
    fun hasScreenLock(): Boolean = appContext.getSystemService(KeyguardManager::class.java)
        ?.isDeviceSecure == true

    /** The system screen that asks the user to let kiosk mode lock the device. */
    fun grantIntent(): Intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)

    /**
     * Locks the device now. Locking turns the screen off, so a screen that was
     * on is woken straight back up, onto the display over the new lock: by a
     * wake lock, which newer releases honor only with TURN_SCREEN_ON, and by
     * [KioskWakeActivity], which needs nothing (Codex on #814).
     * Android asks for the PIN, pattern or password, not a fingerprint or
     * face, for the first unlock after an app locks the device.
     */
    @WorkerThread
    fun lock(): KioskLockResult {
        val policy = appContext.getSystemService(DevicePolicyManager::class.java)
        if (policy == null || !policy.isAdminActive(admin)) return KioskLockResult.NotGranted
        val keyguard = appContext.getSystemService(KeyguardManager::class.java)
        // With no PIN, pattern or password, lockNow() only turns the screen off.
        if (keyguard == null || !keyguard.isDeviceSecure) return KioskLockResult.NoScreenLock
        if (keyguard.isDeviceLocked) return KioskLockResult.AlreadyLocked
        val power = appContext.getSystemService(PowerManager::class.java)
        val screenWasOn = power?.isInteractive == true
        // The screen-off this lock causes is its own: marked before the lock,
        // so its broadcast can't arrive first ([takeOwnScreenOff]).
        if (screenWasOn) ownScreenOffAt.set(elapsedRealtime())
        try {
            policy.lockNow()
        } catch (e: SecurityException) {
            // The grant was taken back between the check above and this call.
            ownScreenOffAt.set(null)
            LauncherDebugLog.failure(e, "kiosk lock: lockNow refused")
            return KioskLockResult.Refused
        }
        if (screenWasOn) {
            if (power != null) wake(power)
            startWake()
        }
        return KioskLockResult.Locked
    }

    /**
     * Whether a screen-off is the one [lock] caused by locking a screen that
     * was on, before it woke the screen back up. Locking again on that one
     * would turn the display off with nothing to wake it, while any other
     * screen-off locks, even one whose screen is already back on by the time
     * it's handled (Codex on #814). Each lock's own is taken once, and only
     * within [OWN_SCREEN_OFF_WINDOW_MS] of it.
     */
    fun takeOwnScreenOff(): Boolean {
        val at = ownScreenOffAt.getAndSet(null) ?: return false
        return elapsedRealtime() - at <= OWN_SCREEN_OFF_WINDOW_MS
    }

    /** The device was unlocked: any screen-off from the last lock is long past. */
    fun forgetOwnScreenOff() {
        ownScreenOffAt.set(null)
    }

    /**
     * Gives the grant back, so nothing is left holding it once kiosk mode is
     * off, and waits until Android has finished: it removes a device admin
     * asynchronously, so a quick switch back on would otherwise find the grant
     * still there and not ask for it, just before it went (Codex on #814).
     * The wait checks the grant itself every [RELEASE_POLL_MS], up to
     * [RELEASE_WAIT_MS], said in the log if it runs out. Not on the receiver's
     * onDisabled, which Android calls before the removal takes effect.
     */
    @WorkerThread
    suspend fun release() {
        val policy = appContext.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!policy.isAdminActive(admin)) return
        removeAdmin(policy, admin)
        val gone = withTimeoutOrNull(RELEASE_WAIT_MS) {
            while (policy.isAdminActive(admin)) delay(RELEASE_POLL_MS)
        }
        LauncherDebugLog.event(if (gone != null) "kiosk lock: released" else "kiosk lock: release still pending")
    }

    private fun wake(power: PowerManager) {
        // The replacement Android suggests, an activity's turnScreenOn, applies
        // only as an activity resumes, and the launcher is the activity already
        // in front, so there is nothing to resume. A brief bright wake lock that
        // wakes the device on acquire does it instead; once the display is up,
        // its own keep-screen-on takes over.
        @Suppress("DEPRECATION")
        val wakeLock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            WAKE_LOCK_TAG,
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(WAKE_HOLD_MS)
        LauncherDebugLog.event("kiosk lock: screen back on=%s", power.isInteractive.toString())
    }

    companion object {
        private const val WAKE_LOCK_TAG = "typelauncher:kioskLock"

        // Long enough to wake the screen; after that the display's own
        // keep-screen-on, or the user's screen timeout, decides.
        private const val WAKE_HOLD_MS = 2_000L

        // A lock's own screen-off arrives within moments of it.
        @VisibleForTesting
        internal const val OWN_SCREEN_OFF_WINDOW_MS = 5_000L

        // Removing a device admin takes moments; this only bounds a wait that
        // never ends, so the switch isn't held up for good.
        @VisibleForTesting
        internal const val RELEASE_WAIT_MS = 5_000L

        @VisibleForTesting
        internal const val RELEASE_POLL_MS = 50L

        // Process-wide, like the lock itself: each activity has its own KioskLock.
        private val ownScreenOffAt = AtomicReference<Long?>(null)
    }
}

/**
 * Kiosk mode's device admin, holding only the force-lock policy that
 * [KioskLock] uses. Android shows its description on the grant screen and in
 * the device admin apps list.
 */
class KioskLockAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        LauncherDebugLog.event("kiosk lock: granted")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        LauncherDebugLog.event("kiosk lock: grant removed")
    }
}
