package app.typelauncher

import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The theme pins the text-field caret fully visible under Robolectric (blink
 * disabled) so screenshot recordings can't drift on caret blink phase — a
 * recording that lands mid-blink otherwise diffs against one that doesn't.
 * Guards the wiring in [TypeLauncherTheme]; the fingerprint gate keeps
 * on-device blinking untouched, which a Robolectric test can't observe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemeCursorBlinkTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theme_disablesCursorBlinkUnderRobolectric() {
        var blinkEnabled: Boolean? = null
        composeRule.setContent {
            TypeLauncherTheme {
                blinkEnabled = LocalCursorBlinkEnabled.current
            }
        }
        composeRule.waitForIdle()

        assertFalse(
            "TypeLauncherTheme must disable caret blink under Robolectric so " +
                "screenshot recordings are caret-deterministic",
            blinkEnabled!!,
        )
    }
}
