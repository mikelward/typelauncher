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
 * "Widgets on home screen": the setting persists, edit mode is entered and
 * left through its own actions, and a widget picked from Home's picker joins
 * Home's own set while Home stays the destination — never a carousel page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelHomeWidgetsTest {
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
        newViewModel().setHomeWidgetsShown(true)
        idle()

        assertTrue(newViewModel().uiState.value.isHomeWidgetsShown)
    }

    @Test
    fun widgetPickedFromHomeJoinsHomeSetAndStaysOnHome() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        idle()
        assertTrue(viewModel.uiState.value.isEditingHomeWidgets)
        assertTrue(viewModel.uiState.value.isAddingWidget)
        assertEquals(LauncherDestination.Home, viewModel.uiState.value.destination)

        viewModel.addWidget(42)
        idle()

        val state = viewModel.uiState.value
        assertEquals(listOf(42), state.homeWidgetIds)
        assertEquals(listOf(emptyList<Int>()), state.widgetPages)
        assertTrue(42 in state.widgetIds)
        assertEquals(LauncherDestination.Home, state.destination)
        assertFalse(state.isAddingWidget)
        // Still editing, so the user can add another or reorder.
        assertTrue(state.isEditingHomeWidgets)
    }

    @Test
    fun widgetPagePickerStillAddsToAPage() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        viewModel.hideWidgetPicker()
        viewModel.showWidgetPicker(pageIndex = 0)
        idle()

        viewModel.addWidget(7)
        idle()

        assertEquals(listOf(listOf(7)), viewModel.uiState.value.widgetPages)
        assertEquals(emptyList<Int>(), viewModel.uiState.value.homeWidgetIds)
    }

    @Test
    fun movingOrRemovingAHomeWidgetKeepsHomeTheDestination() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        listOf(1, 2).forEach { id ->
            viewModel.showHomeWidgetPicker()
            viewModel.addWidget(id)
        }
        idle()

        viewModel.moveWidget(2, WidgetMoveDirection.UP)
        assertEquals(listOf(2, 1), viewModel.uiState.value.homeWidgetIds)
        assertEquals(LauncherDestination.Home, viewModel.uiState.value.destination)

        viewModel.removeWidget(2)
        assertEquals(listOf(1), viewModel.uiState.value.homeWidgetIds)
        assertEquals(LauncherDestination.Home, viewModel.uiState.value.destination)
    }

    @Test
    fun editFromSettingsClosesSettingsAndOpensEditMode() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.openSettings()

        viewModel.startEditingHomeWidgets()

        val state = viewModel.uiState.value
        assertFalse(state.isSettingsOpen)
        assertEquals(LauncherDestination.Home, state.destination)
        assertTrue(state.isEditingHomeWidgets)
    }

    @Test
    fun editFromSettingsClearsATypedQuery() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.setQuery("ma")
        viewModel.openSettings()

        viewModel.startEditingHomeWidgets()

        // A leftover query would keep the app list over the widget slot.
        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.isEditingHomeWidgets)
    }

    @Test
    fun openingSettingsEndsEditMode() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        idle()

        viewModel.openSettings()
        viewModel.closeSettings()

        assertFalse(viewModel.uiState.value.isEditingHomeWidgets)
        assertFalse(viewModel.uiState.value.isAddingWidget)
    }

    @Test
    fun stopEditingClosesAHomePicker() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        idle()

        viewModel.stopEditingHomeWidgets()

        assertFalse(viewModel.uiState.value.isEditingHomeWidgets)
        assertFalse(viewModel.uiState.value.isAddingWidget)
    }

    @Test
    fun turningTheSettingOffEndsEditMode() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        idle()

        viewModel.setHomeWidgetsShown(false)

        assertFalse(viewModel.uiState.value.isEditingHomeWidgets)
        assertFalse(viewModel.uiState.value.isAddingWidget)
    }

    @Test
    fun homePressEndsEditMode() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.startEditingHomeWidgets()

        viewModel.returnToLauncherHome()

        assertFalse(viewModel.uiState.value.isEditingHomeWidgets)
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
