package app.typelauncher

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The update banner's recovery from a Play sheet that never opened, and from
 * one that opened and was declined.
 *
 * AndroidX catches a `SendIntentException` from the `IntentSender` and redelivers
 * it through the activity result rather than throwing from the launch call, so no
 * external activity opens and there is no resume to recover the banner —
 * it would otherwise sit on "Updating…" with nothing to tap. An ordinary
 * decline is detected directly in the same callback (not inferred from a
 * resume-time recheck's ambiguous `UNKNOWN`, which can't tell a decline apart
 * from a genuine accept still registering), but recovering the *handle* that
 * decline consumed still needs a fresh check — so this callback stays busy
 * and triggers one rather than writing `Idle` straight back. These drive
 * `MainActivity.onPlayUpdateLaunchResult` itself, not just the predicate, so
 * deleting the recovery from that callback fails the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityPlayUpdateResultTest {

    @Before
    fun clearLog() = LauncherDebugLog.resetForTest()

    @After
    fun resetLog() = LauncherDebugLog.resetForTest()

    /** Exactly what `ComponentActivity`'s registry dispatches on a failed launch. */
    private fun redeliveredLaunchFailure(): ActivityResult = ActivityResult(
        Activity.RESULT_CANCELED,
        Intent()
            .setAction(ActivityResultContracts.StartIntentSenderForResult.ACTION_INTENT_SENDER_REQUEST)
            .putExtra(
                ActivityResultContracts.StartIntentSenderForResult.EXTRA_SEND_INTENT_EXCEPTION,
                IntentSender.SendIntentException("sender canceled"),
            ),
    )

    @Test
    fun failedLaunch_resetsBannerAndFallsBackToStoreListing() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            // The state a tap on Update leaves behind while the sheet opens.
            activity.viewModel.setPlayUpdateAvailable(101)
            activity.viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
            shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities()

            activity.onPlayUpdateLaunchResult(redeliveredLaunchFailure())

            val state = activity.viewModel.uiState.value.playUpdate as PlayUpdateState.Available
            assertEquals(
                "a sheet that never opened must not strand the banner on Updating…",
                UpdateProgress.Idle,
                state.progress,
            )
            val fallback = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
            assertNotNull("the store listing must be offered as a fallback", fallback)
            assertEquals(Intent.ACTION_VIEW, fallback.action)
            assertTrue(
                "fallback should target this app's listing, was ${fallback.data}",
                fallback.data.toString().contains(RuntimeEnvironment.getApplication().packageName),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun failedLaunch_recordsTheRedeliveredExceptionForDiagnosis() {
        // The redelivered exception is the only trace this failure leaves in the
        // field, so it has to reach the log rather than being dropped for a bare
        // message (AGENTS "Error handling").
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            controller.get().onPlayUpdateLaunchResult(redeliveredLaunchFailure())

            val snapshot = LauncherDebugLog.snapshot()
            assertTrue(
                "the failed launch must be logged: $snapshot",
                snapshot.any { it.contains("Play update sheet failed to launch") },
            )
            assertTrue(
                "the exception's own message must survive into the log: $snapshot",
                snapshot.any { it.contains("sender canceled") },
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun ordinaryCancel_triggersARecoveryCheckInsteadOfExposingAStaleUpdateButton() {
        // A sheet that did open and was declined fires no install event, and
        // Play's UNKNOWN install status can't distinguish "declined" from
        // "accepted, but the download hasn't registered yet" — so
        // progressForInstallStatus's UNKNOWN handling now always preserves
        // whatever was already showing (needed so a resume recheck that
        // lands before the install listener's first real event can't snap a
        // genuine download back to "Update"). But startUpdate() already
        // consumed the cached handle the moment the sheet opened, so setting
        // Idle straight back here — as an earlier version did — would expose
        // a tappable Update whose retry finds nothing to launch and falls
        // back to the external Play listing instead of the in-app flow
        // (Codex on PR #648). This must trigger a recovery check instead of
        // writing Idle directly.
        //
        // Debug builds disable Play update checks entirely, so
        // PlayUpdateChecker.checkForUpdate resolves synchronously to
        // "unavailable" here rather than actually contacting Play — which is
        // exactly what proves a check ran: a direct Idle write would have
        // left the banner Available(Idle), not cleared it to NotAvailable.
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.viewModel.setPlayUpdateAvailable(101)
            activity.viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
            shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities()

            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_CANCELED, null))

            assertEquals(
                "a decline must trigger a fresh check rather than writing Idle directly",
                PlayUpdateState.NotAvailable,
                activity.viewModel.uiState.value.playUpdate,
            )
            assertNull(
                "an ordinary decline must not launch the store listing",
                shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun ordinaryCancelWithAnEmptyIntent_alsoTriggersARecoveryCheck() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.viewModel.setPlayUpdateAvailable(101)
            activity.viewModel.setPlayUpdateProgress(UpdateProgress.Starting)

            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_CANCELED, Intent()))

            assertEquals(PlayUpdateState.NotAvailable, activity.viewModel.uiState.value.playUpdate)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun anAcceptedSheet_doesNotResetTheBanner() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.viewModel.setPlayUpdateAvailable(101)
            activity.viewModel.setPlayUpdateProgress(UpdateProgress.Starting)

            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_OK, null))

            val state = activity.viewModel.uiState.value.playUpdate as PlayUpdateState.Available
            assertEquals(
                "an accepted sheet must leave the in-flight state for the install listener",
                UpdateProgress.Starting,
                state.progress,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun onlyARedeliveredExceptionCountsAsALaunchFailure() {
        assertNotNull(intentSenderLaunchException(redeliveredLaunchFailure()))
        assertNull(intentSenderLaunchException(ActivityResult(Activity.RESULT_CANCELED, null)))
        assertNull(intentSenderLaunchException(ActivityResult(Activity.RESULT_CANCELED, Intent())))
        assertNull(intentSenderLaunchException(ActivityResult(Activity.RESULT_OK, null)))
    }
}
