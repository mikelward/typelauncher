package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifies that app-list labels honor the system "Font size" setting. Labels
 * render with `MaterialTheme.typography.titleMedium` (an `.sp` style), so they
 * must grow with the user's font scale — the complement of
 * [AppIconBadgeFontScaleTest], which locks the corner badge to *not* scale.
 * Guards against a regression that pins label text to dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppLabelFontScaleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appLabelGrowsWithTheFontScaleSetting() {
        composeRule.setContent {
            TypeLauncherTheme {
                val base = LocalDensity.current
                Column {
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale = 1f),
                    ) {
                        Text(
                            "Calculator",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("label:ScaleOne"),
                        )
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale = 2f),
                    ) {
                        Text(
                            "Calculator",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.testTag("label:ScaleTwo"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val baseline = labelTextLayout("label:ScaleOne")
        val doubled = labelTextLayout("label:ScaleTwo")

        assertTrue(
            "label height must grow with the font-scale setting " +
                "(${baseline.size.height} -> ${doubled.size.height})",
            doubled.size.height > baseline.size.height,
        )
        assertTrue(
            "label glyphs must grow with the font-scale setting " +
                "(${baseline.multiParagraph.height} -> ${doubled.multiParagraph.height})",
            doubled.multiParagraph.height > baseline.multiParagraph.height,
        )
    }

    private fun labelTextLayout(tag: String): TextLayoutResult {
        val node = composeRule.onNode(
            hasTestTag(tag) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(
            "label text must expose its layout result",
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results),
        )
        return results.first()
    }
}
