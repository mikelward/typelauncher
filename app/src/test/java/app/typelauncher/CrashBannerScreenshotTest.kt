package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
 * Renders the post-crash banner ([CrashBannerCard]) at the top of a home-like
 * surface, so the `roborazzi-screenshots` artifact documents the push-down
 * treatment: the banner takes a strip at the top with the [Dismiss] [Share]
 * actions, and the launcher content would sit below it. Pinned in light and dark
 * so the error-container colors are reviewable in both themes.
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
        composeRule.onNodeWithText("Type Launcher crashed").assertExists()

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
        composeRule.onNodeWithText("Type Launcher crashed").assertExists()

        capture("compose_crash_banner_dark_robolectric.png")
    }

    @Test
    fun crashBanner_pushesHomeContentDown() {
        // Exercises the real push-down layout — the banner strip, the top-inset
        // remap, and the weighted shrink of the content below it — over a Home-like
        // stub, so a regression that overlays or clips Home is caught here rather
        // than only the isolated card (Codex on PR #593).
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                HomeContentWithCrashBanner(
                    innerPadding = PaddingValues(top = 24.dp),
                    showCrashBanner = true,
                    // Wallpaper off: the wrapper (and the banner's margins) are
                    // opaque, matching Home below.
                    wallpaperActive = false,
                    onShareCrash = {},
                    onDismissCrash = {},
                ) { homeInnerPadding ->
                    HomeContentStub(homeInnerPadding)
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Type Launcher crashed").assertExists()
        composeRule.onNodeWithText("Search apps").assertExists()

        capture("compose_crash_banner_pushdown_robolectric.png", heightPx = 900)
    }

    @Test
    fun crashBanner_pushdownOverWallpaper() {
        // Wallpaper active: the banner's strip and side gutters must be transparent
        // so the wallpaper shows through them just as it does around Home's cards —
        // the wrapper must not paint an opaque background over it (Codex on PR #593).
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                // A bright gradient stands in for the window wallpaper Robolectric
                // can't composite (as in WallpaperAllPagesScreenshotTest).
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF3A7BD5), Color(0xFF00D2FF))),
                        ),
                ) {
                    HomeContentWithCrashBanner(
                        innerPadding = PaddingValues(top = 24.dp),
                        showCrashBanner = true,
                        wallpaperActive = true,
                        onShareCrash = {},
                        onDismissCrash = {},
                    ) { homeInnerPadding ->
                        // Transparent stub so the gradient shows around the cards too.
                        Box(modifier = Modifier.fillMaxSize().padding(homeInnerPadding))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Type Launcher crashed").assertExists()

        capture("compose_crash_banner_pushdown_wallpaper_robolectric.png", heightPx = 900)
    }

    /** A minimal Home-like body (search field + app rows) so the push-down reads as Home. */
    @Composable
    private fun HomeContentStub(innerPadding: PaddingValues) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text("Search apps", modifier = Modifier.padding(horizontal = 16.dp))
            }
            repeat(4) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text("App ${index + 1}", modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
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

    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 460) {
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
