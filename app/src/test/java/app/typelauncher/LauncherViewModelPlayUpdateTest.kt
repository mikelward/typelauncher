package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
    fun markPlayUpdateTappedHidesBannerForCurrentVersion() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        idle()
        assertTrue((viewModel.uiState.value.playUpdate as PlayUpdateState.Available).shouldPrompt)

        viewModel.markPlayUpdateTapped()
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("session-dismiss should mark isDismissed", state.isDismissed)
        assertFalse("banner should not prompt after tap", state.shouldPrompt)
    }

    @Test
    fun sessionDismissSurvivesRecheckOfSameVersion() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.markPlayUpdateTapped()
        idle()

        // Simulates onResume re-running checkForUpdate while the user is back
        // on the launcher without having actually updated.
        viewModel.setPlayUpdateAvailable(101)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertTrue("session-dismiss should survive a re-check of the same version", state.isDismissed)
    }

    @Test
    fun sessionDismissClearsWhenHigherVersionBecomesAvailable() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.markPlayUpdateTapped()
        idle()

        viewModel.setPlayUpdateAvailable(102)
        idle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertEquals(102, state.versionCode)
        assertFalse("a newer version must re-surface the banner", state.isDismissed)
    }

    @Test
    fun sessionDismissClearsWhenUpdateBecomesUnavailable() {
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)
        viewModel.markPlayUpdateTapped()
        idle()

        viewModel.setPlayUpdateUnavailable()
        idle()
        assertEquals(PlayUpdateState.NotAvailable, viewModel.uiState.value.playUpdate)

        // After the update goes away and comes back (e.g. a server-side rollout
        // pause/resume), the in-memory dismissal must not silently re-suppress.
        viewModel.setPlayUpdateAvailable(101)
        idle()
        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        assertFalse("session-dismiss must not survive a NotAvailable transition", state.isDismissed)
    }

    @Test
    fun markPlayUpdateTappedDoesNotPersistDismissal() {
        val store = PlayUpdateStore(context)
        val before = store.dismissedVersionCode
        val viewModel = newViewModel()
        viewModel.setPlayUpdateAvailable(101)

        viewModel.markPlayUpdateTapped()
        idle()

        assertEquals(
            "tap must not write to the persisted dismissed version code",
            before,
            store.dismissedVersionCode,
        )
    }

    @Test
    fun markPlayUpdateTappedIsNoopWhenNoUpdateAvailable() {
        val viewModel = newViewModel()
        viewModel.markPlayUpdateTapped()
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
