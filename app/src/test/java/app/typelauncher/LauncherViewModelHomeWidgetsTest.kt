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
    fun homeAddResumedAfterProcessDeathStillJoinsHome() {
        val before = newViewModel()
        before.setHomeWidgetsShown(true)
        before.showHomeWidgetPicker()
        idle()
        assertTrue(before.isPendingWidgetForHome)

        // A process death drops the ViewModel; the activity's saved bundle
        // carries the Home flag back to a fresh one.
        val after = newViewModel()
        after.restoreHomeWidgetPlacement()
        after.addWidget(9)
        idle()

        assertEquals(listOf(9), after.uiState.value.homeWidgetIds)
        assertEquals(listOf(emptyList<Int>()), after.uiState.value.widgetPages)
        // Back in the edit mode the add started from.
        assertTrue(after.uiState.value.isEditingHomeWidgets)
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
    fun resizingAWidgetsWidthPublishesItsSpan() {
        val viewModel = newViewModel()
        viewModel.showWidgetPicker(pageIndex = 0)
        viewModel.addWidget(5)
        idle()

        viewModel.resizeWidgetWidth(5, 2)
        assertEquals(mapOf(5 to 2), viewModel.uiState.value.widgetSpans)

        // Back to a full row: no span entry.
        viewModel.resizeWidgetWidth(5, WIDGET_GRID_COLUMNS)
        assertEquals(emptyMap<Int, Int>(), viewModel.uiState.value.widgetSpans)
    }

    @Test
    fun aNewWidgetStartsAtThePickedProvidersTargetWidth() {
        val viewModel = newViewModel()
        viewModel.showWidgetPicker(pageIndex = 0)
        viewModel.rememberSelectedWidgetWidth(targetCellWidth = 2)

        viewModel.addWidget(8)

        // Already narrow in the state that first shows the widget, so it never
        // flashes full width, and nothing depends on a post-bind lookup.
        assertEquals(mapOf(8 to 2), viewModel.uiState.value.widgetSpans)
    }

    @Test
    fun aHomeWidgetStartsAtThePickedProvidersTargetWidth() {
        val viewModel = newViewModel()
        viewModel.setHomeWidgetsShown(true)
        viewModel.showHomeWidgetPicker()
        viewModel.rememberSelectedWidgetWidth(targetCellWidth = 1)

        viewModel.addWidget(9)

        assertEquals(mapOf(9 to 1), viewModel.uiState.value.widgetSpans)
    }

    @Test
    fun aPickedWidthSurvivesProcessDeath() {
        val before = newViewModel()
        before.showWidgetPicker(pageIndex = 0)
        before.rememberSelectedWidgetWidth(targetCellWidth = 2)
        assertEquals(2, before.pendingWidgetDefaultSpan)

        // The activity's saved bundle carries the span to a fresh ViewModel.
        val after = newViewModel()
        after.restorePendingWidgetPlacement(toHome = false, defaultSpan = 2)
        after.addWidget(12)

        assertEquals(mapOf(12 to 2), after.uiState.value.widgetSpans)
        assertEquals(listOf(listOf(12)), after.uiState.value.widgetPages)
    }

    @Test
    fun aProviderWithNoOrAFullRowTargetWidthStartsFullWidth() {
        val viewModel = newViewModel()
        viewModel.showWidgetPicker(pageIndex = 0)
        viewModel.rememberSelectedWidgetWidth(targetCellWidth = 0)
        viewModel.addWidget(10)
        viewModel.showWidgetPicker(pageIndex = 0)
        viewModel.rememberSelectedWidgetWidth(targetCellWidth = 5)
        viewModel.addWidget(11)

        assertEquals(emptyMap<Int, Int>(), viewModel.uiState.value.widgetSpans)
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
