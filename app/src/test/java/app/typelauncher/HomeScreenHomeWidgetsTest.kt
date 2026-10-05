package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Widgets on home screen" in Home's app-list slot: the widgets show while the
 * query is empty, and typing lays the app list over them without taking them
 * out of composition (so a keystroke never re-binds a widget). They wait for
 * the home-ready signal, and stay off while the setting is off.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class HomeScreenHomeWidgetsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val baseState = LauncherUiState(
        isLoadingApps = false,
        isHomeWidgetsShown = true,
        isHomeReady = true,
        homeWidgetIds = listOf(WIDGET_ID),
        widgetIds = listOf(WIDGET_ID),
    )

    @Test
    fun widgetsShowWithAnEmptyQueryAndStayComposedUnderTyping() {
        var state by mutableStateOf(baseState)
        setHome { state }
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertExists()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()

        state = state.copy(query = "a")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_CARD_TAG).assertExists()
        // Still composed (its own node remains), but hidden from the user and
        // from accessibility under the app list.
        composeRule.onNodeWithTag(HOME_WIDGETS_HIDDEN_TAG).assertExists()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertDoesNotExist()

        state = state.copy(query = "")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertExists()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun widgetsWaitForHomeReady() {
        setHome { baseState.copy(isHomeReady = false) }

        composeRule.onNodeWithTag(HOME_WIDGETS_TAG).assertDoesNotExist()
        // Nor does the app list flash in their place.
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun settingOffKeepsTheAppList() {
        setHome { baseState.copy(isHomeWidgetsShown = false) }

        composeRule.onNodeWithTag(HOME_WIDGETS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertExists()
    }

    private fun setHome(state: () -> LauncherUiState) {
        composeRule.setContent {
            TypeLauncherTheme {
                HomeScreen(
                    state = state(),
                    innerPadding = PaddingValues(),
                    bodyReady = true,
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUninstallApp = {},
                    onDismissRecent = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val WIDGET_ID = 5
    }
}
