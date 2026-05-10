package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
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
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }
        composeRule.waitForIdle()
    }
}
