package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Deliberately does not compose this page with `isWallpaperShown = true`. That
// state renders through a transparent window, and a test doing so here leaves
// state that breaks the *next* test's activity launch in this class —
// "lightZ must be a finite positive, given=Infinity" out of Robolectric's
// renderer setup, only when the whole suite runs, bisected to exactly that
// test. The wallpaper-backed page is covered by the recorded screenshots
// instead (compose_settings_preview_wallpaper), which render under NATIVE
// graphics with a pinned device geometry.
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

    // The launcher can only reveal the wallpaper, never set it, so the row's
    // whole job is reaching the handler that can — this covers the row being
    // wired to it at all, which is the part a refactor of the settings card
    // silently drops.
    @Test
    fun changeWallpaperRowInvokesTheHandler() {
        var changeWallpaperTaps = 0
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
                    onChangeWallpaper = { changeWallpaperTaps++ },
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onCompletePlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }
        composeRule.waitForIdle()

        // "Change" alone is only unambiguous next to the row's label, which is
        // a separate semantics node — a screen reader landing on the button
        // needs the whole action on the button itself.
        composeRule.onNodeWithContentDescription("Change wallpaper").assertExists()

        composeRule.onNodeWithTag(CHANGE_WALLPAPER_BUTTON_TAG).performScrollTo().performClick()

        assertEquals("Tapping Change opens the system wallpaper picker", 1, changeWallpaperTaps)
    }
}
