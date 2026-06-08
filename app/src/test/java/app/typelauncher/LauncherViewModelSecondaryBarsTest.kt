package app.typelauncher

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelSecondaryBarsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun recentsEnabledDefaultsToTrue() {
        assertEquals(true, newViewModel().uiState.value.isRecentsEnabled)
    }

    @Test
    fun setRecentsEnabledUpdatesStateAndPersists() {
        val viewModel = newViewModel()

        viewModel.setRecentsEnabled(false)
        idle()

        assertEquals(false, viewModel.uiState.value.isRecentsEnabled)
        // A freshly constructed view model reads the persisted value back.
        assertEquals(false, newViewModel().uiState.value.isRecentsEnabled)
    }

    @Test
    fun notificationPullDownBehaviorDefaultsToBarBelow() {
        assertEquals(
            NotificationPullDownBehavior.BarBelow,
            newViewModel().uiState.value.notificationPullDownBehavior,
        )
    }

    @Test
    fun setNotificationPullDownBehaviorUpdatesStateAndPersists() {
        val viewModel = newViewModel()

        viewModel.setNotificationPullDownBehavior(NotificationPullDownBehavior.System)
        idle()

        assertEquals(
            NotificationPullDownBehavior.System,
            viewModel.uiState.value.notificationPullDownBehavior,
        )
        assertEquals(
            NotificationPullDownBehavior.System,
            newViewModel().uiState.value.notificationPullDownBehavior,
        )
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
