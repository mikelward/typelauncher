package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Settings at the top of its scroll — where the page's bottom edge
 * fades out to show more follows — and again scrolled to the last row, where
 * the fade is gone because nothing does. `ScrollFadeTest` covers the rule
 * behind it; these are its pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScrollFadeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_fadesItsBottomEdgeWhileMoreContentFollows() {
        showSettings()
        composeRule.onNodeWithText("Settings").assertExists()

        capture("compose_settings_scroll_fade_top_robolectric.png")
    }

    @Test
    fun settings_dropsTheFadeAtTheEndOfTheScroll() {
        showSettings()
        // Scroll far past the end: the scrollable clamps to its own maximum, so
        // this lands exactly at the bottom without depending on the page's
        // height. (`performScrollTo` on the last row stops as soon as that row
        // is visible, which is not the same place.)
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()

        capture("compose_settings_scroll_fade_bottom_robolectric.png")
    }

    private fun showSettings() {
        composeRule.setContent {
            // Fixed scheme (no dynamic color) so the faded edge is compared
            // against deterministic colors rather than device-tinted ones.
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
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

    // 1080x2400 is the window the `qualifiers` above actually compose into
    // (411dp x 914dp at 420dpi). Capturing at any other height re-lays the
    // decor view out at a size Compose never scrolled against, which moves the
    // scroll position relative to the viewport and makes the end-of-scroll
    // capture show a page that is no longer at its end.
    private fun capture(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
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
