package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the crash prompt ([CrashBannerCard]) in isolation on a plain
 * background, so the `roborazzi-screenshots` artifact documents the
 * error-container treatment and the [Dismiss] [Share] actions in both themes,
 * plus [crashBanner_atTopOfSettings] rendering the card in place at the top
 * of the real [SettingsScreen] layout, so spacing against the title row and
 * the Play-update banner slot below it is covered too (`SettingsScreenTest`
 * covers the same placement's content, not its pixels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CrashBannerScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun crashBanner_light() {
        composeRule.setContent {
            // Fixed scheme (no dynamic color) so the error-container colors are
            // deterministic across runners rather than device-tinted.
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                BannerAtTopOfHome()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Did Type Launcher crash?").assertExists()

        capture("compose_crash_banner_light_robolectric.png")
    }

    @Test
    fun crashBanner_dark() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                BannerAtTopOfHome()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Did Type Launcher crash?").assertExists()

        capture("compose_crash_banner_dark_robolectric.png")
    }

    @Test
    fun crashBanner_atTopOfSettings() {
        composeRule.setContent {
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
                    showCrashBanner = true,
                    onShareCrash = {},
                    onDismissCrash = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Did Type Launcher crash?").assertExists()

        capture("compose_crash_banner_settings_placement_robolectric.png", heightPx = 1280)
    }

    @Composable
    private fun BannerAtTopOfHome() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            CrashBannerCard(
                onShare = {},
                onDismiss = {},
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }

    // 560, not the previously-fitting 460: this forces the decor view into a
    // much smaller layout than the one Compose already settled at the real
    // (qualifiers-driven) window size, and when the card's content is tall
    // enough to sit right at that shrunk canvas's bottom edge, the button
    // row's own background draws but its text glyphs silently don't — found
    // by bisecting heights after the body text grew from one line to two and
    // "Dismiss"/"Share" started rendering blank. Keep some margin below the
    // card here rather than cutting it exactly to fit.
    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 560) {
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
