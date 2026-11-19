package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home's one-time offer to show apps as a grid. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelGridOfferTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun freshInstall_offersTheGrid() {
        assertTrue(newViewModel().uiState.value.isGridOfferPending)
    }

    @Test
    fun accepting_switchesToNamesBelow_andRetiresTheOffer() {
        newViewModel().acceptGridOffer()

        val state = newViewModel().uiState.value
        assertEquals(AppListLayout.NameBelow, state.appListLayout)
        assertFalse(state.isGridOfferPending)
    }

    @Test
    fun dismissing_keepsTheList_andRetiresTheOffer() {
        newViewModel().dismissGridOffer()

        val state = newViewModel().uiState.value
        assertEquals(AppListLayout.NameBeside, state.appListLayout)
        assertFalse(state.isGridOfferPending)
    }

    @Test
    fun choosingAStyleInSettings_answersTheOffer() {
        // Even choosing the list again: the user has seen the choice.
        val viewModel = newViewModel()
        viewModel.setAppListLayout(AppListLayout.NameBeside)

        assertFalse(viewModel.uiState.value.isGridOfferPending)
        assertFalse(newViewModel().uiState.value.isGridOfferPending)
    }

    @Test
    fun alreadyAGrid_isNotOffered() {
        DockSettingsStore(context).appListLayout = AppListLayout.IconOnly

        assertFalse(newViewModel().uiState.value.isGridOfferPending)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )
}
