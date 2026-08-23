package app.typelauncher

import android.app.Activity
import android.os.Looper
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.common.IntentSenderForResultStarter
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The checker's failure-shaped edges: an install that never starts, the
 * install listener's lifetime against an activity that goes away mid-check,
 * and the single-use `updateInfo` token's consume/repopulate contract that
 * the update banner's cancel-recovery path depends on.
 */
@RunWith(RobolectricTestRunner::class)
class PlayUpdateCheckerTest {

    @Before
    fun setUp() = LauncherDebugLog.clearForTest()

    @After
    fun tearDown() = LauncherDebugLog.clearForTest()

    /**
     * Tapping Restart hands off to Play, and on success the app is restarted —
     * so the only outcome that comes back is a failure (a busy installer, a
     * transient Play error), where the tap visibly does nothing. That must not
     * be discarded silently (AGENTS "Error handling").
     */
    @Test
    fun `an install that fails to start is logged, not swallowed`() {
        val manager = FakeAppUpdateManager(
            completeUpdateResult = Tasks.forException(IllegalStateException("installer busy")),
        )
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.completeFlexibleUpdate()
        // Play's task callbacks are posted to the main looper.
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "the failed install must leave a trace: ${LauncherDebugLog.snapshot()}",
            LauncherDebugLog.snapshot().any { it.contains("Play update install failed to start") },
        )
    }

    @Test
    fun `a successful hand-off logs no failure`() {
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.completeFlexibleUpdate()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            LauncherDebugLog.snapshot().none { it.contains("Play update install failed to start") },
        )
    }

    /**
     * The banner's own visibility into the same failure — the tap that would
     * otherwise look like it did nothing.
     */
    @Test
    fun `an install that fails to start also notifies the caller`() {
        val manager = FakeAppUpdateManager(
            completeUpdateResult = Tasks.forException(IllegalStateException("installer busy")),
        )
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)
        var notified = false

        checker.completeFlexibleUpdate(onFailure = { notified = true })
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("the caller must hear about a failed install", notified)
    }

    @Test
    fun `a successful hand-off does not notify a failure`() {
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)
        var notified = false

        checker.completeFlexibleUpdate(onFailure = { notified = true })
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue("a successful install must not notify a failure", !notified)
    }

    @Test
    fun `a check landing after the activity is gone registers no listener`() {
        // Play's check is asynchronous: a rotation while it is in flight runs
        // onDestroy's cleanup first, and the late success callback then reaches
        // registerInstallListener. Registering there would leave a listener
        // behind that nothing ever unregisters — one per recreation.
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.unregisterInstallListener()
        checker.registerInstallListener()

        assertEquals("a destroyed checker must register nothing", 0, manager.registrations)
    }

    @Test
    fun `a live checker registers its listener once`() {
        // The positive control: without the teardown the latch must not get in
        // the way, and a second call stays idempotent.
        val manager = FakeAppUpdateManager(completeUpdateResult = Tasks.forResult(null))
        val checker = PlayUpdateChecker(ApplicationProvider.getApplicationContext(), manager)

        checker.registerInstallListener()
        checker.registerInstallListener()

        assertEquals(1, manager.registrations)
    }

    /**
     * The invariant the update banner's Play-sheet-canceled recovery depends
     * on (`MainActivity.onResume`'s unconditional `checkPlayUpdate` call,
     * which is what recovers a canceled sheet — Codex review on PR #647):
     * `startUpdate` consumes the single-use `updateInfo` the moment the sheet
     * launches, whether the user accepts or cancels it, so a second
     * `startUpdate` fails until a fresh check repopulates it. Driven through
     * Play's own [FakeAppUpdateManager] test double rather than the
     * hand-rolled one below, so it exercises the same consume/repopulate
     * contract the real `AppUpdateManager` has.
     */
    @Test
    fun `startUpdate fails after a canceled sheet until the next check repopulates it`() {
        val fakeManager = FakeAppUpdateManager(ApplicationProvider.getApplicationContext())
        fakeManager.setUpdateAvailable(101, AppUpdateType.FLEXIBLE)
        val checker = PlayUpdateChecker(
            ApplicationProvider.getApplicationContext(),
            fakeManager,
            checksEnabled = true,
        )
        val launcher = NoOpActivityResultLauncher()

        var available = false
        checker.checkForUpdate(onAvailable = { _, _ -> available = true }, onUnavailable = {})
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("the fake update must be reported available", available)
        assertTrue("the sheet must launch", checker.startUpdate(launcher))

        // The user backs out of the sheet without accepting it.
        fakeManager.userRejectsUpdate()

        assertFalse(
            "a second tap must not silently succeed against the already-consumed info",
            checker.startUpdate(launcher),
        )

        // What MainActivity.onResume does on every resume, including the one
        // that follows returning from the sheet: recheck, which repopulates
        // updateInfo.
        checker.checkForUpdate(onAvailable = { _, _ -> }, onUnavailable = {})
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(
            "a recheck must repopulate updateInfo so Update works again",
            checker.startUpdate(launcher),
        )
    }

    /** Satisfies the [ActivityResultLauncher] contract without a real activity. */
    private class NoOpActivityResultLauncher : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) = Unit
        override fun unregister() = Unit
        override val contract: ActivityResultContract<IntentSenderRequest, *>
            get() = throw UnsupportedOperationException("not used")
    }

    /**
     * Only [completeUpdate] and the listener calls are exercised here; the rest
     * satisfies the interface.
     * Two of the request-code overloads are deprecated in Play's API — we don't
     * call them, but an implementation still has to declare them.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    private class FakeAppUpdateManager(
        private val completeUpdateResult: Task<Void>,
    ) : AppUpdateManager {
        /** Live registrations: incremented on register, decremented on unregister. */
        var registrations = 0
            private set

        override fun completeUpdate(): Task<Void> = completeUpdateResult

        override fun getAppUpdateInfo(): Task<AppUpdateInfo> =
            Tasks.forException(UnsupportedOperationException("not used"))

        override fun startUpdateFlow(
            info: AppUpdateInfo,
            activity: Activity,
            options: AppUpdateOptions,
        ): Task<Int> = Tasks.forException(UnsupportedOperationException("not used"))

        override fun registerListener(listener: InstallStateUpdatedListener) {
            registrations++
        }

        override fun unregisterListener(listener: InstallStateUpdatedListener) {
            registrations--
        }

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            launcher: ActivityResultLauncher<IntentSenderRequest>,
            options: AppUpdateOptions,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            appUpdateType: Int,
            activity: Activity,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            appUpdateType: Int,
            starter: IntentSenderForResultStarter,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            activity: Activity,
            options: AppUpdateOptions,
            requestCode: Int,
        ): Boolean = false

        override fun startUpdateFlowForResult(
            info: AppUpdateInfo,
            starter: IntentSenderForResultStarter,
            options: AppUpdateOptions,
            requestCode: Int,
        ): Boolean = false
    }
}
