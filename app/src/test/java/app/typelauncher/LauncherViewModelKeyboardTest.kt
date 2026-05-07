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
