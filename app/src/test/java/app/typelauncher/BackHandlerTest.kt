package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the launcher's back-press chain. Without an in-app handler the
 * system default for back is `Activity.finish()` — as the default home the
 * system immediately relaunches the finished activity, so backing out of
 * settings *looked* like it worked while actually tearing down and
 * rebuilding the whole activity + ViewModel; as a non-default app it simply
 * exited the launcher. The handler must close the topmost launcher surface
 * (settings → widget picker → recents tray) and stay unregistered when
 * nothing is open so the system default still applies on a bare Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class BackHandlerTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var closeSettingsCount = 0
    private var dismissPickerCount = 0
    private val recentsOpenChanges = mutableListOf<Boolean>()

    private fun setLauncherContent(initialState: LauncherUiState) {
        var state by mutableStateOf(initialState)
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state,
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {
                        closeSettingsCount += 1
                        state = state.copy(isSettingsOpen = false)
                    },
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetRecentsOpen = { open ->
                        recentsOpenChanges += open
                        state = state.copy(isRecentsOpen = open)
                    },
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {
                        dismissPickerCount += 1
                        state = state.copy(isAddingWidget = false)
                    },
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun pressBack() {
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun backClosesSettingsPage() {
        setLauncherContent(LauncherUiState(filteredApps = emptyList(), isSettingsOpen = true))

        pressBack()

        assertEquals(1, closeSettingsCount)
        assertEquals(0, dismissPickerCount)
        assertEquals(emptyList<Boolean>(), recentsOpenChanges)
    }

    @Test
    fun backClosesSettingsBeforeDismissingThePicker() {
        // Settings overlays the whole carousel, so it is the topmost surface
        // even while the widget picker is open underneath.
        setLauncherContent(
            LauncherUiState(
                filteredApps = emptyList(),
                isSettingsOpen = true,
                isAddingWidget = true,
            ),
        )

        pressBack()
        assertEquals(1, closeSettingsCount)
        assertEquals(0, dismissPickerCount)

        pressBack()
        assertEquals(1, closeSettingsCount)
        assertEquals(1, dismissPickerCount)
    }

    @Test
    fun backClosesRecentsTray() {
        setLauncherContent(LauncherUiState(filteredApps = emptyList(), isRecentsOpen = true))

        pressBack()

        assertEquals(listOf(false), recentsOpenChanges)
        assertEquals(0, closeSettingsCount)
    }

    @Test
    fun backFallsThroughToTheSystemWhenNothingIsOpen() {
        setLauncherContent(LauncherUiState(filteredApps = emptyList()))

        // The handler must not even be registered, so the system default
        // (no-op for the default home) applies instead of a swallowed event.
        composeRule.runOnUiThread {
            assertFalse(composeRule.activity.onBackPressedDispatcher.hasEnabledCallbacks())
        }
    }
}
