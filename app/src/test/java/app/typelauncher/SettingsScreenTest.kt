package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

    // Regression coverage for the crash prompt's relocation from a Home
    // push-down banner to the top of Settings: the card is now driven by
    // `showCrashBanner`, not a value HomeScreen computes.
    @Test
    fun settingsScreenShowsCrashBannerWhenPending() {
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
                    showCrashBanner = true,
                    onShareCrash = {},
                    onDismissCrash = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Did Type Launcher crash?").assertExists()
    }
}
