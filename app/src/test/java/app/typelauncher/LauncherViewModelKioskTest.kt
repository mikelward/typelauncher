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

/**
 * Kiosk mode: the setting persists, the display needs Home widgets on, and it
 * steps aside while those widgets are being edited.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelKioskTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun settingPersistsAcrossViewModels() {
        newViewModel().setKioskMode(true)
        idle()

        assertTrue(newViewModel().uiState.value.isKioskMode)
    }

    @Test
    fun displayNeedsHomeWidgetsOn() {
        val viewModel = newViewModel()
        viewModel.setKioskMode(true)
        idle()
        assertFalse(viewModel.uiState.value.isKioskActive)

        viewModel.setHomeWidgetsShown(true)
        idle()
        assertTrue(viewModel.uiState.value.isKioskActive)

        viewModel.setKioskMode(false)
        idle()
        assertFalse(viewModel.uiState.value.isKioskActive)
    }

    @Test
    fun editingHomeWidgetsStepsOutOfTheDisplayAndDoneReturnsToIt() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.setKioskMode(true)
        viewModel.openSettings()
        idle()

        viewModel.startEditingHomeWidgets()
        idle()
        assertFalse(viewModel.uiState.value.isKioskActive)

        viewModel.stopEditingHomeWidgets()
        idle()
        assertTrue(viewModel.uiState.value.isKioskActive)
    }

    @Test
    fun settingsOverTheDisplayReleasesTheScreenOnHold() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.setKioskMode(true)
        idle()
        assertTrue(viewModel.uiState.value.isKioskDisplayShowing)

        viewModel.openSettings()
        idle()
        assertFalse(viewModel.uiState.value.isKioskDisplayShowing)
        assertFalse(kioskKeepsScreenOn(viewModel.uiState.value.isKioskDisplayShowing, isPluggedIn = true))

        viewModel.closeSettings()
        idle()
        assertTrue(viewModel.uiState.value.isKioskDisplayShowing)
    }

    @Test
    fun enablingDropsAQueryAndOpenRecents() {
        val viewModel = newViewModel()
        viewModel.setQuery("ma")
        viewModel.setRecentsOpen(true)
        idle()

        viewModel.setKioskMode(true)
        idle()

        assertEquals("", viewModel.uiState.value.query)
        assertFalse(viewModel.uiState.value.isRecentsOpen)
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
