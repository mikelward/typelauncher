package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

    // The analytics opt-out. Defaults on (PRIVACY.md has always declared crash
    // reporting), so the switch must render on for a default state and hand the
    // *new* value to the callback when tapped.
    @Test
    fun analyticsSwitchReflectsStateAndReportsChanges() {
        var reported: Boolean? = null
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    // Explicit rather than the state's default, which is now
                    // false: this test is about the switch reflecting what it
                    // is handed and reporting the flip, so reading the default
                    // only made it break when the default moved.
                    state = LauncherUiState(isTelemetryEnabled = true),
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
                    onTelemetryEnabledChanged = { reported = it },
                )
            }
        }
        composeRule.waitForIdle()

        // Settings is one long scrolling column and this row sits well below
        // the fold, so the tap needs the row on screen first — a click aimed at
        // an off-viewport center silently reaches nothing.
        composeRule.onNodeWithTag(ANALYTICS_SWITCH_TAG).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(ANALYTICS_SWITCH_TAG).performClick()

        assertEquals(false, reported)
    }

    @Test
    fun analyticsSwitchRendersOffWhenOptedOut() {
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    state = LauncherUiState(isTelemetryEnabled = false),
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

        composeRule.onNodeWithTag(ANALYTICS_SWITCH_TAG).performScrollTo().assertIsOff()
    }

}
