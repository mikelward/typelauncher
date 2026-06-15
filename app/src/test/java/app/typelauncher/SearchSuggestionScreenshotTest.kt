package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-only screenshot test for [SearchSuggestionOverlay], the inline
 * autocomplete suggestion drawn right-aligned inside the search field. Production
 * overlays it on the text field inside a `Box`; rendering it directly over a
 * fixed-width surface here (the same pattern as [SearchAppNameHintScreenshotTest])
 * captures the three styling cases — bolded prefix, faint package-only match, and
 * end-ellipsis truncation — without needing the field's focus/IME machinery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchSuggestionScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchSuggestion_rendersHighlightStates() {
        composeRule.setContent {
            TypeLauncherTheme {
                Surface {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        // Prefix match: leading letters bold.
                        SuggestionRow(
                            suggestion = InlineSearchSuggestion(
                                name = "Calculator",
                                boldIndices = launcherMatchHighlightIndices("Calculator", "ca"),
                            ),
                            tag = SEARCH_SUGGESTION_TAG,
                        )
                        // Package-only match: faint, nothing bold.
                        SuggestionRow(
                            suggestion = InlineSearchSuggestion(
                                name = "Credit Card",
                                boldIndices = launcherMatchHighlightIndices("Credit Card", "virgin"),
                            ),
                        )
                        // Long title: end-ellipsis, matched prefix stays visible.
                        SuggestionRow(
                            suggestion = InlineSearchSuggestion(
                                name = "Settings and Privacy Dashboard",
                                boldIndices = launcherMatchHighlightIndices("Settings and Privacy Dashboard", "se"),
                            ),
                        )
                        // Full-name match: the whole title renders bold (e.g.
                        // typing "up" while the match is "Up").
                        SuggestionRow(
                            suggestion = InlineSearchSuggestion(
                                name = "Up",
                                boldIndices = launcherMatchHighlightIndices("Up", "up"),
                            ),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(SEARCH_SUGGESTION_TAG, useUnmergedTree = true)
            .assertIsDisplayed()

        captureSnapshot("compose_search_suggestion_robolectric.png")
    }

    @androidx.compose.runtime.Composable
    private fun SuggestionRow(suggestion: InlineSearchSuggestion, tag: String? = null) {
        Box(modifier = Modifier.width(320.dp)) {
            // Mirror production: the overlay is anchored to the field's right edge.
            val base = Modifier.align(Alignment.CenterEnd)
            SearchSuggestionOverlay(
                suggestion = suggestion,
                modifier = if (tag != null) base.testTag(tag) else base,
            )
        }
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
