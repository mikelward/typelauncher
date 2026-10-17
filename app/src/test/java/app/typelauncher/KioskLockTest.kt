package app.typelauncher

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

/** Kiosk mode's lock: when it locks, when it wakes the screen back up, and when it does neither. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val admin = ComponentName(context, KioskLockAdminReceiver::class.java)
    private val policy get() = context.getSystemService(DevicePolicyManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)
    private val lock = KioskLock(context)

    private fun grant() = shadowOf(policy).setActiveAdmin(admin)

    private fun setScreenLock(set: Boolean) = shadowOf(keyguard).setIsDeviceSecure(set)

    @Test
    fun withoutTheGrantNothingIsLocked() {
        setScreenLock(true)

        assertEquals(KioskLockResult.NotGranted, lock.lock())
        assertFalse(keyguard.isDeviceLocked)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun withoutAScreenLockNothingIsLocked() {
        // lockNow() would only turn the screen off, with nothing to unlock.
        grant()
        setScreenLock(false)

        assertEquals(KioskLockResult.NoScreenLock, lock.lock())
        assertFalse(keyguard.isDeviceLocked)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun aScreenThatWasOnIsWokenBackUpOverTheLock() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(true)

        assertEquals(KioskLockResult.Locked, lock.lock())

        assertTrue(keyguard.isDeviceLocked)
        val wakeLock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("the screen is woken back up", wakeLock != null && wakeLock.isHeld)
        assertTrue("the wake lock wakes the device", wakeLock!!.flags() and PowerManager.ACQUIRE_CAUSES_WAKEUP != 0)
    }

    @Test
    fun aScreenThatWasOnAlsoStartsTheWakeActivity() {
        // Newer releases honor the wake lock only with TURN_SCREEN_ON; the
        // activity needs nothing.
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(true)
        var woken = 0
        val waking = KioskLock(context, startWake = { woken++ })

        waking.lock()

        assertEquals(1, woken)
    }

    @Test
    fun aScreenThatWasOffStartsNoWake() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(false)
        var woken = 0
        val waking = KioskLock(context, startWake = { woken++ })

        waking.lock()

        assertEquals(0, woken)
    }

    @Test
    fun aScreenThatWasOffStaysOff() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(false)

        assertEquals(KioskLockResult.Locked, lock.lock())

        assertTrue(keyguard.isDeviceLocked)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun anAlreadyLockedDeviceIsLeftAlone() {
        grant()
        setScreenLock(true)
        shadowOf(keyguard).setIsDeviceLocked(true)

        assertEquals(KioskLockResult.AlreadyLocked, lock.lock())
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Before
    fun forgetEchoes() = lock.forgetOwnScreenOff()

    @Test
    fun releaseGivesTheGrantBack() = runTest {
        grant()
        assertTrue(lock.isGranted())

        lock.release()

        assertFalse(lock.isGranted())
        // Twice is harmless.
        lock.release()
        assertFalse(lock.isGranted())
    }

    @Test
    fun releaseWaitsForAndroidToFinishRemovingTheGrant() = runTest {
        // Android removes a device admin asynchronously: a quick switch back on
        // must not find the grant still there just before it goes.
        grant()
        val pending = KioskLock(context, removeAdmin = { _, _ -> })
        val release = launch { pending.release() }
        runCurrent()
        assertFalse("still waiting", release.isCompleted)

        // No signal: Android's onDisabled comes before the removal takes
        // effect, so the wait watches the grant itself.
        policy.removeActiveAdmin(admin)
        advanceTimeBy(KioskLock.RELEASE_POLL_MS + 1)

        assertTrue(release.isCompleted)
    }

    @Test
    fun releaseStopsWaitingAfterABound() = runTest {
        grant()
        val stuck = KioskLock(context, removeAdmin = { _, _ -> })
        val release = launch { stuck.release() }
        advanceTimeBy(KioskLock.RELEASE_WAIT_MS + 1)
        assertTrue(release.isCompleted)
    }

    @Test
    fun aLockWithTheScreenOnKnowsItsOwnScreenOffOnce() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(true)

        lock.lock()

        assertTrue(lock.takeOwnScreenOff())
        assertFalse("a second screen-off is a real one", lock.takeOwnScreenOff())
    }

    @Test
    fun aLockWithTheScreenOffCausesNoScreenOffOfItsOwn() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(false)

        lock.lock()

        assertFalse(lock.takeOwnScreenOff())
    }

    @Test
    fun aScreenOffLongAfterTheLockIsARealOne() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(true)
        var now = 1_000L
        val clocked = KioskLock(context, elapsedRealtime = { now })

        clocked.lock()
        now += KioskLock.OWN_SCREEN_OFF_WINDOW_MS + 1

        assertFalse(clocked.takeOwnScreenOff())
    }

    @Test
    fun anUnlockForgetsTheLocksOwnScreenOff() {
        grant()
        setScreenLock(true)
        shadowOf(power).setIsInteractive(true)
        lock.lock()

        lock.forgetOwnScreenOff()

        assertFalse(lock.takeOwnScreenOff())
    }

    @Test
    fun theGrantScreenNamesThisAdmin() {
        val intent = lock.grantIntent()

        assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(admin, intent.getParcelableExtra<ComponentName>(DevicePolicyManager.EXTRA_DEVICE_ADMIN))
    }

    private fun PowerManager.WakeLock.flags(): Int =
        PowerManager.WakeLock::class.java.getDeclaredField("mFlags").apply { isAccessible = true }.getInt(this)
}
