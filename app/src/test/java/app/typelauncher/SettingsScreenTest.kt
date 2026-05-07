package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun removeWidgetPaddingSwitchReflectsStateAndEmitsChanges() {
        var removed by mutableStateOf(false)
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    state = LauncherUiState(isWidgetPaddingRemoved = removed),
                    innerPadding = PaddingValues(),
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onWidgetPaddingRemovedChanged = { removed = it },
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }

        composeRule.onNodeWithTag(REMOVE_WIDGET_PADDING_SWITCH_TAG).performScrollTo().assertIsOff()
        composeRule.onNodeWithTag(REMOVE_WIDGET_PADDING_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(true, removed)
        composeRule.onNodeWithTag(REMOVE_WIDGET_PADDING_SWITCH_TAG).performScrollTo().assertIsOn()
    }
}
