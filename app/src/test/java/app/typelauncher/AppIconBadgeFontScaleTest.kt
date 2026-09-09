package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Regression test for the corner badge sizing its glyph with a bare `.sp` of
 * a dp-derived number. Font sizes in sp scale with the user's font-scale
 * accessibility setting while the badge box is dp-sized, so at fontScale 2.0
 * the flag/emoji resolved to twice the badge box and spilled over the app
 * icon. The glyph size must be converted through the density (`Dp.toSp()`)
 * so the rendered glyph is identical at every font scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconBadgeFontScaleTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun badgedApp(name: String): InstalledApp = InstalledApp(
        name = name,
        packageName = "app.typelauncher.fake.${name.lowercase()}",
        launchIntent = Intent(),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    ).copy(customBadge = "🇬🇧")

    @Test
    fun cornerBadgeGlyphLayoutIsIdenticalAtDoubleFontScale() {
        composeRule.setContent {
            TypeLauncherTheme {
                val base = LocalDensity.current
                Row {
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale = 1f),
                    ) {
                        AppIcon(app = badgedApp("ScaleOne"), size = 48.dp)
                    }
                    CompositionLocalProvider(
                        LocalDensity provides Density(base.density, fontScale = 2f),
                    ) {
                        AppIcon(app = badgedApp("ScaleTwo"), size = 48.dp)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val baseline = badgeTextLayout("ScaleOne")
        val doubled = badgeTextLayout("ScaleTwo")

        assertEquals(
            "badge glyph width must not scale with the font-scale setting",
            baseline.size.width,
            doubled.size.width,
        )
        assertEquals(
            "badge glyph height must not scale with the font-scale setting",
            baseline.size.height,
            doubled.size.height,
        )
        assertEquals(
            "badge glyph line height must not scale with the font-scale setting",
            baseline.multiParagraph.height,
            doubled.multiParagraph.height,
            1f,
        )
    }

    private fun badgeTextLayout(appName: String): TextLayoutResult {
        val node = composeRule.onNode(
            hasAnyAncestor(hasTestTag("$APP_ICON_DISAMBIGUATOR_TAG:$appName")) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        assertTrue(
            "badge text must expose its layout result",
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results),
        )
        return results.first()
    }
}
