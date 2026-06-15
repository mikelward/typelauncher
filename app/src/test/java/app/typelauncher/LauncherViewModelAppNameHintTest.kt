package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coverage for the "Show top match name" setting. Off by default; the setter
 * flips the in-memory state and persists so a fresh launcher process reads the
 * choice back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelAppNameHintTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun defaultsOff() {
        val viewModel = newViewModel()
        idle()
        assertFalse(viewModel.uiState.value.isShowAppNameHint)
    }

    @Test
    fun setterUpdatesStateAndPersists() {
        val viewModel = newViewModel()
        idle()

        viewModel.setShowAppNameHint(true)
        idle()
        assertTrue(viewModel.uiState.value.isShowAppNameHint)

        // A fresh ViewModel (new process) must read the persisted choice back.
        val reloaded = newViewModel()
        idle()
        assertTrue(reloaded.uiState.value.isShowAppNameHint)
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
