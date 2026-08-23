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
 * The update banner's recovery from a Play sheet that never opened.
 *
 * AndroidX catches a `SendIntentException` from the `IntentSender` and redelivers
 * it through the activity result rather than throwing from the launch call, so no
 * external activity opens and the resume-time recheck that handles an ordinary
 * cancel never runs — the banner would sit on "Updating…" with nothing to tap.
 * These drive `MainActivity.onPlayUpdateLaunchResult` itself, not just the
 * predicate, so deleting the recovery from that callback fails the suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityPlayUpdateResultTest {

    @Before
    fun clearLog() = LauncherDebugLog.clearForTest()

    @After
    fun resetLog() = LauncherDebugLog.clearForTest()

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
    fun ordinaryCancel_isLeftToTheResumeTimeRecheck() {
        // A sheet that did open and was dismissed produces a plain canceled
        // result; recovering it here as well would race onResume's own recheck.
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.viewModel.setPlayUpdateAvailable(101)
            activity.viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
            shadowOf(RuntimeEnvironment.getApplication()).clearNextStartedActivities()

            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_CANCELED, null))
            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_CANCELED, Intent()))
            activity.onPlayUpdateLaunchResult(ActivityResult(Activity.RESULT_OK, null))

            val state = activity.viewModel.uiState.value.playUpdate as PlayUpdateState.Available
            assertEquals(
                "an ordinary result must leave the in-flight state for onResume",
                UpdateProgress.Starting,
                state.progress,
            )
            assertNull(
                "an ordinary result must not launch the store listing",
                shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity,
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
