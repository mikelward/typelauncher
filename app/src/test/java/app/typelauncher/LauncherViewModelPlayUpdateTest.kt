package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.android.play.core.install.model.InstallStatus
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelPlayUpdateTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("play_update", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun availableUpdateStartsInIdleProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("banner should prompt when no in-flight progress", state.shouldPrompt)
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun setProgressStartingKeepsBannerVisibleWithInFlightState() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        // The banner now stays visible after tap and reflects the in-flight
        // download state instead of session-dismissing.
        assertTrue(state.shouldPrompt)
        assertFalse(state.isDismissed)
        assertEquals(UpdateProgress.Starting, state.progress)
    }

    @Test
    fun setProgressDoesNotPersistDismissal() {
        val store = PlayUpdateStore(context)
        val before = store.dismissedVersionCode
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)

        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        assertEquals(
            "in-flight progress must not write to the persisted dismissed version code",
            before,
            store.dismissedVersionCode,
        )
    }

    @Test
    fun setProgressIsNoopWhenNoUpdateAvailable() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)
    }

    @Test
    fun recheckPreservesInFlightProgressWhenInstallStatusUnknown() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        // onResume re-checks with the same version; Play often reports UNKNOWN
        // mid-download — we should preserve the in-flight state from the listener.
        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloading, state.progress)
    }

    @Test
    fun recheckRevertsStartingToIdleWhenPlaySheetWasCancelled() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        idle()

        // User cancels the Play sheet — no listener event fires, but the next
        // onResume recheck returns UNKNOWN. We revert to Idle so the user can
        // tap Update again.
        viewModel.setPlayUpdateAvailable(101, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun recheckPromotesProgressFromDownloadingToDownloaded() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateProgress(UpdateProgress.Downloaded)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloaded, state.progress)
        assertTrue("banner stays visible to surface the Restart action", state.shouldPrompt)
    }

    @Test
    fun installStatusFailedResetsProgressToIdle() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloading)
        idle()

        viewModel.onPlayUpdateInstallStatus(InstallStatus.FAILED)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun newerVersionResetsInFlightProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateAvailable(102, InstallStatus.UNKNOWN)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(102, state.versionCode)
        assertEquals(
            "a newer available version must reset in-flight progress to Idle",
            UpdateProgress.Idle,
            state.progress,
        )
    }

    @Test
    fun unavailableClearsInFlightProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        viewModel.setPlayUpdateUnavailable()
        idle()

        viewModel.setPlayUpdateAvailable(101)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Idle, state.progress)
    }

    @Test
    fun dismissPlayUpdateStillPersistsForCurrentVersion() {
        val store = PlayUpdateStore(context)
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()

        viewModel.dismissPlayUpdate()
        idle()

        assertEquals(101, store.dismissedVersionCode)
        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("X dismiss should persist for the current version", state.isDismissed)
        assertFalse(state.shouldPrompt)
    }

    @Test
    fun checkFailedPreservesDownloadedBanner() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        // The update finished downloading and the banner shows Restart. A
        // resume-time recheck then fails (flaky network / Play unavailable).
        viewModel.setPlayUpdateCheckFailed()
        idle()

        // Before the fix the failure routed to setPlayUpdateUnavailable and
        // wiped the banner to NotAvailable, hiding Restart and stranding the
        // already-downloaded update. It must survive a failed check.
        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloaded, state.progress)
        assertTrue("Restart banner must survive a failed recheck", state.shouldPrompt)
    }

    @Test
    fun checkFailedPreservesInFlightDownloadProgress() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADING)
        idle()

        viewModel.setPlayUpdateCheckFailed()
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(UpdateProgress.Downloading, state.progress)
    }

    @Test
    fun successfulUnavailableStillClearsBanner() {
        // A check that *succeeds* and reports no update still clears the banner,
        // so the fix distinguishes "check failed" from "no update available".
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101, InstallStatus.DOWNLOADED)
        idle()

        viewModel.setPlayUpdateUnavailable()
        idle()

        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
