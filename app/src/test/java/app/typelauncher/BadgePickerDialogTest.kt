package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-only tests for the badge picker. Mirrors
 * [EditAppDialogScreenshotTest]'s setup: hosts the dialog content directly in
 * a stub [ComponentActivity] so the launcher's IME / keyboard plumbing
 * doesn't cascade with the dialog's recomposition under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgePickerDialogTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun rendersDefaultTileAndAllFourPresets() {
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = null,
                    onPickBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(BADGE_PICKER_DEFAULT_TILE_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        for (option in BUILT_IN_BADGE_OPTIONS) {
            composeRule
                .onNodeWithTag("$BADGE_PICKER_PRESET_TILE_TAG:${option.key}", useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }

    @Test
    fun tappingPresetInvokesCallbackWithGlyph() {
        var picked: String? = "sentinel"
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = null,
                    onPickBadge = { glyph -> picked = glyph },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag("$BADGE_PICKER_PRESET_TILE_TAG:home", useUnmergedTree = true)
            .performClick()

        val home = BUILT_IN_BADGE_OPTIONS.first { it.key == "home" }
        assertEquals(home.glyph, picked)
    }

    @Test
    fun tappingDefaultTileInvokesCallbackWithNull() {
        var picked: String? = "sentinel"
        var sawCallback = false
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = "🇺🇸",
                    onPickBadge = { glyph ->
                        sawCallback = true
                        picked = glyph
                    },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(BADGE_PICKER_DEFAULT_TILE_TAG, useUnmergedTree = true)
            .performClick()

        assertTrue(sawCallback)
        assertNull(picked)
    }

    @Test
    fun flagGridLeadsWithGlobeAndIncludesAtLeastOneCountry() {
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = null,
                    onPickBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(BADGE_PICKER_FLAG_GRID_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        // Globe sits at the top of the flag grid — same FlagCell layout
        // as a country tile, with the WORLD_BADGE_OPTION's "world" key.
        composeRule
            .onNodeWithTag("$BADGE_PICKER_FLAG_TILE_TAG:world", useUnmergedTree = true)
            .assertIsDisplayed()
        // The first country alphabetically in en-US — Afghanistan — is reliably
        // present in the JVM's ISO data on every platform we run tests on.
        composeRule
            .onNodeWithTag("$BADGE_PICKER_FLAG_TILE_TAG:country:AF", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    fun tappingGlobeInvokesCallbackWithIntlGlyph() {
        var picked: String? = "sentinel"
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = null,
                    onPickBadge = { glyph -> picked = glyph },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag("$BADGE_PICKER_FLAG_TILE_TAG:world", useUnmergedTree = true)
            .performClick()

        assertEquals(INTL_GLOBE, picked)
    }
}
