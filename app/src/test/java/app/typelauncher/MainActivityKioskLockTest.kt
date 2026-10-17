package app.typelauncher

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowToast

/**
 * With "Lock screen automatically" on, kiosk mode locks the device whenever
 * its display comes on screen and when the screen goes off, and steps out when
 * the device is unlocked. The switch asks for the grant it needs as it turns
 * on, and turns on only once that's granted. Drives the real activity, since
 * those moments come from its lifecycle and from system broadcasts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityKioskLockTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val admin = ComponentName(context, KioskLockAdminReceiver::class.java)
    private val policy get() = context.getSystemService(DevicePolicyManager::class.java)
    private val keyguard get() = context.getSystemService(KeyguardManager::class.java)
    private val power get() = context.getSystemService(PowerManager::class.java)

    @Before
    fun forgetEchoes() {
        KioskLock(context).forgetOwnScreenOff()
        ShadowToast.reset()
    }

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun theDisplayLocksTheDeviceWhenItComesOnScreen() {
        withActivity { activity ->
            showLockedDisplay(activity)

            assertTrue(keyguard.isDeviceLocked)
            assertTrue("the screen comes straight back", ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
            assertEquals(
                "and by the wake activity, which needs no permission",
                KioskWakeActivity::class.java.name,
                shadowOf(activity).nextStartedActivity?.component?.className,
            )
        }
    }

    @Test
    fun withTheLockOffTheDisplayShowsUnlockedAsBefore() {
        // It's off by default (maintainer, 2026-10-17), whatever grant is held.
        withActivity { activity ->
            grantWithScreenLock()
            showDisplay(activity)

            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
            assertFalse(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun aFreshStartWithTheLockOnShowsTheDisplayOverTheLock() {
        // The lock runs from onCreate, before the display composes and allows
        // itself over the lock, so the activity allows it first (Codex on #814).
        withActivity { activity -> lockedKioskSettings(activity) }
        grantWithScreenLock()

        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().ioDispatcher = Dispatchers.Unconfined
        controller.create()
        try {
            assertTrue(keyguard.isDeviceLocked)
            assertTrue("over the new lock", shadowOf(controller.get()).showWhenLocked)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun theDisplayLeavingTakesTheLauncherBackOffTheLock() {
        withActivity { activity ->
            showLockedDisplay(activity)
            assertTrue(shadowOf(activity).showWhenLocked)

            activity.viewModel.openSettings()
            idle()

            assertFalse(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertFalse(shadowOf(activity).showWhenLocked)
        }
    }

    @Test
    fun unlockingWhileTheDisplayShowsStepsOutAndStaysUnlocked() {
        withActivity { activity ->
            showLockedDisplay(activity)

            unlock(activity)

            assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)
            assertFalse("no lock straight back", keyguard.isDeviceLocked)
        }
    }

    @Test
    fun withTheLockOffAnUnlockLeavesTheDisplayUp() {
        // With no screen lock, Android reports an unlock each time the screen
        // comes on; stepping out on it would leave the display every time.
        withActivity { activity ->
            showDisplay(activity)

            activity.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT).setPackage(activity.packageName))
            idle()

            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
        }
    }

    @Test
    fun aQuietMinuteOnTheSteppedOutHomeLocksAgain() {
        withActivity { activity ->
            showLockedDisplay(activity)
            unlock(activity)

            idleFor(KIOSK_RETURN_AFTER_MS - 1_000)
            assertFalse(keyguard.isDeviceLocked)
            idleFor(2_000)

            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun theScreenGoingOffLocks() {
        withActivity { controller, activity ->
            showLockedDisplay(activity)
            unlock(activity)

            screenOff(controller, activity)

            assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun theScreenGoingOffWithTheLockOffLeavesLockingToAndroid() {
        withActivity { controller, activity ->
            grantWithScreenLock()
            showDisplay(activity)
            activity.viewModel.stepOutOfKiosk()
            idle()

            screenOff(controller, activity)

            assertTrue("the display still comes back", activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertFalse(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun theScreenGoingOffWithNoDisplayToShowStillLocks() {
        // Mid-edit the display waits for Done, but the device still locks.
        withActivity { controller, activity ->
            showLockedDisplay(activity)
            unlock(activity)
            activity.viewModel.startEditingHomeWidgets()
            idle()

            screenOff(controller, activity)

            assertFalse(activity.viewModel.uiState.value.isKioskDisplayShowing)
            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun theScreenOffFromItsOwnLockDoesNotLockAgainOnceTheScreenIsBack() {
        // Locking turns the screen off too, and that broadcast can arrive after
        // the lock has woken the screen up again: locking then would leave it dark.
        withActivity { activity ->
            showLockedDisplay(activity)
            // The device not yet reporting the lock, with the screen back on.
            shadowOf(keyguard).setIsDeviceLocked(false)
            shadowOf(power).setIsInteractive(true)

            activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF).setPackage(activity.packageName))
            idle()

            assertFalse(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun aRealScreenOffLocksEvenWithTheScreenBackOnBeforeItsHandled() {
        // Power off then straight back on mid-edit: by the time the screen-off is
        // handled the screen is on again, but it wasn't the lock's own, so it locks.
        withActivity { controller, activity ->
            showLockedDisplay(activity)
            unlock(activity)
            activity.viewModel.startEditingHomeWidgets()
            idle()
            val io = QueueDispatcher()
            activity.ioDispatcher = io

            screenOff(controller, activity)
            shadowOf(power).setIsInteractive(true)
            repeat(3) {
                io.runAll()
                idle()
            }

            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun aCallDoesNotStopTheLock() {
        // A lock skipped for a call would have nothing to retry it when the call
        // ended, leaving the display unlocked.
        context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        withActivity { activity ->
            showLockedDisplay(activity)

            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun theScreenGoingOffInACallLocksAndBringsTheDisplayForward() {
        // Kiosk mode makes no exception for a call (maintainer, 2026-10-17).
        withActivity { controller, activity ->
            showLockedDisplay(activity)
            unlock(activity)
            context.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
            shadowOf(activity).clearNextStartedActivities()

            screenOff(controller, activity)

            assertTrue(keyguard.isDeviceLocked)
            val started = generateSequence { shadowOf(activity).nextStartedActivity }.toList()
            assertTrue("the display comes forward over the call's screen", started.any { it.hasCategory(Intent.CATEGORY_HOME) })
        }
    }

    @Test
    fun theScreenOffBroadcastStaysOpenUntilTheLockIsDone() {
        // The lock runs on past onReceive; going async keeps a stopped launcher's
        // process from being reclaimed before it lands.
        withActivity { controller, activity ->
            showLockedDisplay(activity)
            unlock(activity)
            val io = QueueDispatcher()
            activity.ioDispatcher = io

            screenOff(controller, activity)
            val receiver = screenOffReceiver()
            assertTrue(shadowOf(receiver).wentAsync())
            val finished = shadowOf(shadowOf(receiver).originalPendingResult).future
            assertFalse(keyguard.isDeviceLocked)
            assertFalse(finished.isDone)

            repeat(3) {
                io.runAll()
                idle()
            }
            assertTrue(keyguard.isDeviceLocked)
            assertTrue(finished.isDone)
        }
    }

    @Test
    fun turningKioskModeOnNeverAsksForTheGrant() {
        shadowOf(keyguard).setIsDeviceSecure(true)
        withActivity { activity ->
            activity.onKioskModeChanged(true)
            idle()

            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(activity.viewModel.uiState.value.isKioskMode)
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
        }
    }

    @Test
    fun turningTheLockOnAsksForTheGrantAndWaitsForIt() {
        shadowOf(keyguard).setIsDeviceSecure(true)
        withActivity { activity ->
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            idle()

            val asked = shadowOf(activity).nextStartedActivityForResult
            assertEquals(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN, asked?.intent?.action)
            assertFalse("off until granted", activity.viewModel.uiState.value.isKioskLock)

            shadowOf(policy).setActiveAdmin(admin)
            shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, null)
            idle()

            assertTrue(activity.viewModel.uiState.value.isKioskLock)
            assertTrue(policy.isAdminActive(admin))
        }
    }

    @Test
    fun aDeclinedGrantLeavesTheLockOff() {
        shadowOf(keyguard).setIsDeviceSecure(true)
        withActivity { activity ->
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            idle()
            val asked = shadowOf(activity).nextStartedActivityForResult

            shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_CANCELED, null)
            idle()

            assertFalse(activity.viewModel.uiState.value.isKioskLock)
            assertTrue("kiosk mode stays on", activity.viewModel.uiState.value.isKioskMode)
        }
    }

    @Test
    fun aGrantAcceptedWithTheDisplayShowingLocksAtOnce() {
        // Usually the display locks as Settings closes, after the grant. If it's
        // already up when the grant comes back, it locks then.
        shadowOf(keyguard).setIsDeviceSecure(true)
        shadowOf(power).setIsInteractive(true)
        withActivity { activity ->
            showDisplay(activity)
            activity.onKioskLockChanged(true)
            idle()
            val asked = shadowOf(activity).nextStartedActivityForResult
            assertFalse(keyguard.isDeviceLocked)

            shadowOf(policy).setActiveAdmin(admin)
            shadowOf(activity).receiveResult(asked.intent, Activity.RESULT_OK, null)
            idle()

            assertTrue(keyguard.isDeviceLocked)
        }
    }

    @Test
    fun turningTheLockOnWithoutAScreenLockSaysSoAndStaysOff() {
        shadowOf(keyguard).setIsDeviceSecure(false)
        withActivity { activity ->
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            idle()

            assertNull("nothing to lock with", shadowOf(activity).nextStartedActivityForResult)
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
            assertEquals(
                activity.getString(R.string.kiosk_lock_needs_screen_lock),
                ShadowToast.getTextOfLatestToast(),
            )
        }
    }

    @Test
    fun turningTheLockOnWhenAlreadyGrantedTurnsItOnWithoutAsking() {
        withActivity { activity ->
            grantWithScreenLock()
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            idle()

            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertTrue(activity.viewModel.uiState.value.isKioskLock)
        }
    }

    @Test
    fun turningTheLockOffGivesTheGrantBack() {
        withActivity { activity ->
            lockOn(activity)

            activity.onKioskLockChanged(false)
            idle()

            assertFalse(policy.isAdminActive(admin))
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
            assertTrue(activity.viewModel.uiState.value.isKioskMode)
        }
    }

    @Test
    fun turningKioskModeOffTurnsTheLockOffAndGivesTheGrantBack() {
        withActivity { activity ->
            lockOn(activity)

            activity.onKioskModeChanged(false)
            idle()

            assertFalse(policy.isAdminActive(admin))
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
            assertFalse(activity.viewModel.uiState.value.isKioskMode)
        }
    }

    @Test
    fun aQuickOffAndOnKeepsTheGrantWhenTheOnFindsItFirst() {
        // The two presses' work can finish in either order; the switch never
        // reads on without the grant either way.
        val io = QueueDispatcher()
        withActivity(io) { activity ->
            lockOn(activity, io)

            activity.onKioskLockChanged(false)
            activity.onKioskLockChanged(true)
            repeat(3) {
                io.runNewestFirst()
                idle()
            }

            assertTrue(activity.viewModel.uiState.value.isKioskLock)
            assertTrue(policy.isAdminActive(admin))
            assertNull("nothing to ask for", shadowOf(activity).nextStartedActivityForResult)
        }
    }

    @Test
    fun aQuickOffAndOnAsksAgainWhenTheOffGaveTheGrantBackFirst() {
        val io = QueueDispatcher()
        withActivity(io) { activity ->
            lockOn(activity, io)

            activity.onKioskLockChanged(false)
            activity.onKioskLockChanged(true)
            repeat(3) {
                io.runAll()
                idle()
            }

            assertFalse("off until the answer", activity.viewModel.uiState.value.isKioskLock)
            assertFalse(policy.isAdminActive(admin))
            assertEquals(
                DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
                shadowOf(activity).nextStartedActivityForResult?.intent?.action,
            )
        }
    }

    @Test
    fun aQuickOnAndOffNeitherAsksNorKeepsAGrant() {
        shadowOf(keyguard).setIsDeviceSecure(true)
        val io = QueueDispatcher()
        withActivity(io) { activity ->
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            activity.onKioskLockChanged(false)
            repeat(3) {
                io.runAll()
                idle()
            }

            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertFalse(policy.isAdminActive(admin))
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
        }
    }

    @Test
    fun aGrantLeftBehindWhenTheActivityWentAwayIsGivenBackAtTheNextStart() {
        // Switched off, then destroyed (a rotation, say) before the removal ran.
        val io = QueueDispatcher()
        withActivity(io) { activity ->
            lockOn(activity, io)
            activity.onKioskModeChanged(false)
        }
        assertTrue("the removal never ran", policy.isAdminActive(admin))

        withActivity { _ -> }

        assertFalse(policy.isAdminActive(admin))
    }

    @Test
    fun aStartWithTheLockOnKeepsTheGrantAndNeverAsks() {
        withActivity { activity -> lockOn(activity) }

        withActivity { activity ->
            assertTrue(activity.viewModel.uiState.value.isKioskLock)
            assertTrue(policy.isAdminActive(admin))
            assertNull("only the switch asks", shadowOf(activity).nextStartedActivityForResult)
        }
    }

    @Test
    fun aGrantRemovedInAndroidsSettingsTurnsTheLockOff() {
        withActivity { controller, activity ->
            lockOn(activity)
            controller.pause()

            policy.removeActiveAdmin(admin)
            controller.resume()
            idle()

            assertFalse("the switch reads what locks", activity.viewModel.uiState.value.isKioskLock)
        }
    }

    @Test
    fun aGrantAddedInAndroidsSettingsWithTheLockOffIsGivenBack() {
        // The switch is the only way in: a device admin turned on from
        // Android's own list would otherwise block uninstalling with nothing
        // in the launcher saying so (Codex on #814).
        shadowOf(keyguard).setIsDeviceSecure(true)
        withActivity { controller, activity ->
            activity.onKioskModeChanged(true)
            controller.pause()

            shadowOf(policy).setActiveAdmin(admin)
            controller.resume()
            idle()

            assertFalse(policy.isAdminActive(admin))
            assertFalse(activity.viewModel.uiState.value.isKioskLock)
        }
    }

    @Test
    fun aRecreationWhileTheGrantScreenIsUpLeavesTheNewGrantAlone() {
        // A rotation while Android's grant screen is up recreates the launcher
        // with the switch still off; its start mustn't take the grant the user
        // is giving, or turn anything off before the answer comes back.
        shadowOf(keyguard).setIsDeviceSecure(true)
        val saved = Bundle()
        withActivity { controller, activity ->
            activity.onKioskModeChanged(true)
            activity.onKioskLockChanged(true)
            idle()
            assertEquals(
                DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN,
                shadowOf(activity).nextStartedActivityForResult?.intent?.action,
            )
            shadowOf(policy).setActiveAdmin(admin)
            controller.pause().stop().saveInstanceState(saved)
        }

        val recreated = Robolectric.buildActivity(MainActivity::class.java)
        recreated.get().ioDispatcher = Dispatchers.Unconfined
        recreated.setup(saved)
        try {
            idle()
            assertTrue(policy.isAdminActive(admin))
        } finally {
            recreated.destroy()
        }
    }

    /**
     * Inside the activity, once it has started: a start gives back a grant
     * kiosk mode doesn't want, and kiosk mode starts off here.
     */
    private fun grantWithScreenLock() {
        shadowOf(policy).setActiveAdmin(admin)
        shadowOf(keyguard).setIsDeviceSecure(true)
        shadowOf(power).setIsInteractive(true)
    }

    private fun withActivity(
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        block: (MainActivity) -> Unit,
    ) = withActivity(io) { _, activity -> block(activity) }

    private fun withActivity(
        io: CoroutineDispatcher = Dispatchers.Unconfined,
        block: (ActivityController<MainActivity>, MainActivity) -> Unit,
    ) {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        // By default the lock's binder calls run inline, so each step has landed
        // before the next assertion.
        controller.get().ioDispatcher = io
        controller.setup()
        try {
            block(controller, controller.get())
        } finally {
            controller.destroy()
        }
    }

    /** Turns the lock on the way the switch does, with the grant already held. */
    private fun lockOn(activity: MainActivity, io: QueueDispatcher? = null) {
        // The start's own sync first, which gives back a grant the lock, off
        // until now, doesn't want.
        io?.runAll()
        idle()
        grantWithScreenLock()
        activity.onKioskModeChanged(true)
        activity.onKioskLockChanged(true)
        repeat(3) {
            io?.runAll()
            idle()
        }
        assertTrue(activity.viewModel.uiState.value.isKioskLock)
    }

    /** The display up, with the lock on and the grant held. */
    private fun showLockedDisplay(activity: MainActivity) {
        activity.viewModel.setHomeWidgetsShown(true)
        lockOn(activity)
        assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
    }

    /** The settings a locked display starts from, with no grant involved. */
    private fun lockedKioskSettings(activity: MainActivity) {
        activity.viewModel.setHomeWidgetsShown(true)
        activity.viewModel.setKioskMode(true)
        activity.viewModel.setKioskLock(true)
        idle()
    }

    private fun showDisplay(activity: MainActivity) {
        activity.viewModel.setHomeWidgetsShown(true)
        activity.viewModel.setKioskMode(true)
        idle()
        assertTrue(activity.viewModel.uiState.value.isKioskDisplayShowing)
    }

    /** What the system does when the hold's unlock (or a fingerprint) succeeds. */
    private fun unlock(activity: MainActivity) {
        shadowOf(keyguard).setIsDeviceLocked(false)
        activity.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT).setPackage(activity.packageName))
        idle()
        assertTrue(activity.viewModel.uiState.value.isKioskSteppedOutHome)
    }

    private fun screenOff(controller: ActivityController<MainActivity>, activity: MainActivity) {
        shadowOf(power).setIsInteractive(false)
        controller.pause().stop()
        activity.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF).setPackage(activity.packageName))
        idle()
    }

    private fun screenOffReceiver(): BroadcastReceiver =
        shadowOf(context as Application).registeredReceivers
            .single { it.intentFilter.hasAction(Intent.ACTION_SCREEN_OFF) }
            .broadcastReceiver

    /** Holds dispatched work until [runAll], to show what waits on it. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued += block
        }

        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }

        /** Runs what's queued in reverse, as work on a thread pool can finish. */
        fun runNewestFirst() {
            while (queued.isNotEmpty()) queued.removeLast().run()
        }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
