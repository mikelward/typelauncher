package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelKeyboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun homeResumeKeyboardRequest_emitsOnHomeWhenAutoShowEnabled() {
        val viewModel = newViewModel()
        val requests = KeyboardRequestCounter(viewModel)

        viewModel.requestShowKeyboardOnHomeResume()
        idle()

        assertEquals(1, requests.count.get())
        requests.cancel()
    }

    @Test
    fun homeResumeKeyboardRequest_skipsWhenAutoShowDisabled() {
        val viewModel = newViewModel()
        val requests = KeyboardRequestCounter(viewModel)
        viewModel.setKeyboardAutoShown(false)
        idle()

        viewModel.requestShowKeyboardOnHomeResume()
        idle()

        assertEquals(0, requests.count.get())
        requests.cancel()
    }

    @Test
    fun homeResumeKeyboardRequest_skipsOffHomeAndSettings() {
        val viewModel = newViewModel()
        val requests = KeyboardRequestCounter(viewModel)

        viewModel.showWidgets()
        viewModel.requestShowKeyboardOnHomeResume()
        viewModel.showHome()
        viewModel.openSettings()
        viewModel.requestShowKeyboardOnHomeResume()
        idle()

        assertEquals(0, requests.count.get())
        requests.cancel()
    }

    @Test
    fun requestShowKeyboard_closesOpenSecondaryTrayAndReArmsWait() {
        val viewModel = newViewModel()
        viewModel.setRecentsOpen(true)
        viewModel.setNotificationBarOpen(true)
        val generationBefore = viewModel.uiState.value.autoKeyboardWaitGeneration

        viewModel.requestShowKeyboard()
        idle()

        // The keyboard owns the reserved slot, so requesting it must close a
        // user-opened tray immediately rather than letting it linger over the
        // keyboard's grow, and bump the generation so Home re-arms its auto-keyboard
        // wait and the tray does not flash in before the keyboard re-shows.
        assertEquals(false, viewModel.uiState.value.isRecentsOpen)
        assertEquals(false, viewModel.uiState.value.isNotificationBarOpen)
        assertEquals(generationBefore + 1, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    @Test
    fun reArmAutoKeyboardWaitOnResume_bumpsGenerationOnHome() {
        val viewModel = newViewModel()
        val generationBefore = viewModel.uiState.value.autoKeyboardWaitGeneration

        viewModel.reArmAutoKeyboardWaitOnResume()
        idle()

        // Resuming to Home (e.g. swipe-up-to-home) re-arms the wait synchronously
        // so the tray does not flash on the first resumed frame.
        assertEquals(generationBefore + 1, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    @Test
    fun reArmAutoKeyboardWaitOnResume_skipsWhenAutoShowDisabled() {
        val viewModel = newViewModel()
        viewModel.setKeyboardAutoShown(false)
        idle()
        val generationBefore = viewModel.uiState.value.autoKeyboardWaitGeneration

        viewModel.reArmAutoKeyboardWaitOnResume()
        idle()

        assertEquals(generationBefore, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    @Test
    fun reArmAutoKeyboardWaitOnResume_skipsOffHome() {
        val viewModel = newViewModel()
        viewModel.showWidgets()
        idle()
        val generationBefore = viewModel.uiState.value.autoKeyboardWaitGeneration

        viewModel.reArmAutoKeyboardWaitOnResume()
        idle()

        // Resuming onto a non-Home page must not re-arm Home's tray wait.
        assertEquals(generationBefore, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    @Test
    fun returnToLauncherHome_reArmsWaitOnlyWhenTransitioningToHome() {
        val viewModel = newViewModel()
        viewModel.showWidgets()
        idle()
        val generationOffHome = viewModel.uiState.value.autoKeyboardWaitGeneration

        // A launcher-entry intent while focused off-Home moves to Home and the
        // keyboard auto-shows, so the wait must re-arm even without onResume.
        viewModel.returnToLauncherHome()
        idle()
        val generationAfterReturn = viewModel.uiState.value.autoKeyboardWaitGeneration
        assertEquals(generationOffHome + 1, generationAfterReturn)

        // Re-selecting Home while already on Home re-shows no keyboard, so it
        // must not re-arm (and blank) the tray.
        viewModel.returnToLauncherHome()
        idle()
        assertEquals(generationAfterReturn, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    @Test
    fun showHome_reArmsWaitOnTransitionButNotWhenAlreadyHome() {
        val viewModel = newViewModel()
        viewModel.showWidgets()
        idle()
        val generationOnWidgets = viewModel.uiState.value.autoKeyboardWaitGeneration

        // Carousel swipe-back to Home re-shows the keyboard, so re-arm the wait.
        viewModel.showHome()
        idle()
        val generationAfterReturn = viewModel.uiState.value.autoKeyboardWaitGeneration
        assertEquals(generationOnWidgets + 1, generationAfterReturn)

        // Settling on Home again must not keep bumping the generation.
        viewModel.showHome()
        idle()
        assertEquals(generationAfterReturn, viewModel.uiState.value.autoKeyboardWaitGeneration)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class KeyboardRequestCounter(viewModel: LauncherViewModel) {
        val count = AtomicInteger(0)
        private val job: Job = CoroutineScope(Dispatchers.Unconfined).launch {
            viewModel.keyboardShowRequests.collect {
                count.incrementAndGet()
            }
        }

        fun cancel() {
            job.cancel()
        }
    }
}
