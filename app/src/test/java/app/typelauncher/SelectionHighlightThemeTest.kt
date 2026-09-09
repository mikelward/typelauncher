package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression tests for the search-result highlight ignoring the launcher's
 * `Theme` setting. TypeLauncherTheme applies a forced Light/Dark mode purely
 * by color-scheme choice — it never changes `Configuration.uiMode` — so a
 * highlight derived from `isSystemInDarkTheme()` followed the *device* night
 * mode and painted the light palette into a forced-dark app list (and vice
 * versa). The highlight must track the rendered scheme instead.
 *
 * `dynamicColor = false` pins the checked-in fallback schemes so the
 * assertion is deterministic across Robolectric's dynamic-color resources.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionHighlightThemeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun forcedDarkThemeUsesDarkHighlightOnLightModeDevice() {
        // Robolectric's default configuration is notnight, so before the fix
        // isSystemInDarkTheme() returned false here and the light palette
        // leaked into the forced-dark theme.
        var highlight: Color? = null
        var highlightOn: Color? = null
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                highlight = selectionHighlightColor()
                highlightOn = selectionHighlightOnColor()
            }
        }
        composeRule.waitForIdle()

        assertEquals(Color(0xFF274C7A), highlight)
        assertEquals(Color(0xFFE6EEFA), highlightOn)
    }

    @Test
    @Config(qualifiers = "+night")
    fun forcedLightThemeUsesLightHighlightOnDarkModeDevice() {
        var highlight: Color? = null
        var highlightOn: Color? = null
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                highlight = selectionHighlightColor()
                highlightOn = selectionHighlightOnColor()
            }
        }
        composeRule.waitForIdle()

        assertEquals(Color(0xFFCFE2FF), highlight)
        assertEquals(Color(0xFF0B2A5B), highlightOn)
    }

    @Test
    fun systemThemeStillFollowsDeviceLightMode() {
        var highlight: Color? = null
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.System, dynamicColor = false) {
                highlight = selectionHighlightColor()
            }
        }
        composeRule.waitForIdle()

        assertEquals(Color(0xFFCFE2FF), highlight)
    }
}
