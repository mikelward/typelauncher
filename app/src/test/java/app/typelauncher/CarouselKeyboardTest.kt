package app.typelauncher

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class CarouselKeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun swipingFromHomeToWidgets_hidesKeyboard() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList()))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingFromHomeToAgenda_hidesKeyboard() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList()))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingRightFromHome_whenAgendaDisabledWrapsToWidgets() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isAgendaEnabled = false))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingBackToHome_reShowsKeyboardWhenAutoShowEnabled() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isKeyboardAutoShown = true))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.waitForIdle()
        assertEquals("cold-start auto-show precondition", 1, keyboard.showCount)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(LauncherScreen.Widgets, state.screen)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(2, keyboard.showCount)
    }

    @Test
    fun swipingBackToHome_keepsKeyboardHiddenWhenAutoShowDisabled() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isKeyboardAutoShown = false))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.waitForIdle()
        assertEquals("auto-show disabled precondition", 0, keyboard.showCount)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(LauncherScreen.Widgets, state.screen)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(0, keyboard.showCount)
    }

    private fun renderWithKeyboard(
        keyboard: CountingKeyboardController,
        stateProvider: () -> LauncherUiState,
        onStateChanged: (LauncherUiState) -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                TypeLauncherTheme {
                    val state = stateProvider()
                    TypeLauncherApp(
                        state = state,
                        onQueryChanged = {},
                        onClearQuery = {},
                        onLaunchActiveApp = {},
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onHideApp = {},
                        onUnhideApp = {},
                        onOpenSettings = {},
                        onCloseSettings = {},
                        onRequestDefaultLauncher = {},
                        onDockEnabledChanged = {},
                        onAppListIconOnlyChanged = {},
                        onDockVisibleIconCountChanged = {},
                        onAppListSortOrderChanged = {},
                        onShowAgenda = { onStateChanged(state.copy(screen = LauncherScreen.Agenda)) },
                        onShowWidgets = { onStateChanged(state.copy(screen = LauncherScreen.Widgets)) },
                        onShowHome = { onStateChanged(state.copy(screen = LauncherScreen.Home)) },
                        appWidgetHost = null,
                        appWidgetManager = null,
                        onAddWidget = {},
                        onDismissWidgetPicker = {},
                        onSelectWidget = {},
                        onRemoveWidget = {},
                        onRequestCalendarPermission = {},
                        onOpenAgendaEvent = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private class CountingKeyboardController : SoftwareKeyboardController {
        var showCount = 0
            private set
        var hideCount = 0
            private set

        override fun show() {
            showCount += 1
        }

        override fun hide() {
            hideCount += 1
        }
    }
}
