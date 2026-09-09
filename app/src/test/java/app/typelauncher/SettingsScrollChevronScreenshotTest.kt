package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders Settings at the top of its scroll — where the bottom chevron says
 * more of the page follows — and again at the end, where only the top chevron
 * is left. `SettingsScrollChevronTest` covers which chevron shows when and
 * what a tap does; these are its pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScrollChevronScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_showsTheBottomChevronWhileMorePageFollows() {
        showSettings()
        composeRule.onNodeWithTag(SETTINGS_SCROLL_BOTTOM_CHEVRON_TAG).assertExists()

        capture("compose_settings_scroll_chevron_top_robolectric.png")
    }

    @Test
    fun settings_showsOnlyTheTopChevronAtTheEndOfTheScroll() {
        showSettings()
        // Scroll far past the end: the scrollable clamps to its own maximum, so
        // this lands exactly at the bottom without depending on the page height.
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SETTINGS_SCROLL_TOP_CHEVRON_TAG).assertExists()

        capture("compose_settings_scroll_chevron_bottom_robolectric.png")
    }

    private fun showSettings() {
        composeRule.setContent {
            // Fixed scheme (no dynamic color) so the chevron's translucent plate
            // is compared against deterministic colors rather than device-tinted
            // ones.
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                SettingsScreen(
                    state = LauncherUiState(),
                    // Deliberately none: the chevrons must land on the page
                    // whatever the caller passes, since the band they overhang
                    // into is reserved inside the page rather than borrowed
                    // from the system-bar insets.
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
    // (411dp x 914dp at 420dpi). Capturing at another height re-lays the decor
    // view out at a size Compose never scrolled against, which moves the scroll
    // position relative to the viewport and leaves the end-of-scroll capture
    // showing a page no longer at its end.
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
