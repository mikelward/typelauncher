package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
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
    fun settingsScreenRendersWithoutError() {
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    state = LauncherUiState(),
                    innerPadding = PaddingValues(),
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onCompletePlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun enableFoldersSwitchTogglesFromTheSettingsScreen() {
        var folderToggle: Boolean? = null
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    state = LauncherUiState(isDockEnabled = true),
                    innerPadding = PaddingValues(),
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onFoldersEnabledChanged = { folderToggle = it },
                    onAppListSortOrderChanged = {},
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onCompletePlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }
        composeRule.onNodeWithTag(DOCK_FOLDERS_ENABLED_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(true, folderToggle)
    }
}
