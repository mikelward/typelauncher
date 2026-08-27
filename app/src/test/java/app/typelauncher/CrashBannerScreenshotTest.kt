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
import androidx.compose.ui.test.onNodeWithTag
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
 *
 * The Analytics consent card ([TelemetryConsentCard]) is captured here rather
 * than in a class of its own: it is the other card in the same Settings slot,
 * and CI's screenshot allow-list comes from the base branch, so a brand-new
 * class records nothing on the PR that introduces it. One of its captures
 * ([telemetryConsent_longestLabels]) renders a translated locale, which is the
 * suite's only coverage of a non-English string in a fixed-width layout.
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

    // The state during a share: the button says so and stops taking taps, so a
    // second tap reads as the app working rather than as a dead button.
    @Test
    fun crashBanner_sharing() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                BannerAtTopOfHome(isSharing = true)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Sharing…").assertExists()

        capture("compose_crash_banner_sharing_robolectric.png")
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

    @Test
    fun telemetryConsent_light() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                ConsentCardOnPlainBackground()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Anonymous analytics").assertExists()

        capture("compose_telemetry_consent_light_robolectric.png")
    }

    @Test
    fun telemetryConsent_dark() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                ConsentCardOnPlainBackground()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Anonymous analytics").assertExists()

        capture("compose_telemetry_consent_dark_robolectric.png")
    }

    // The same card in the locale with the longest Allow/Deny pair of the 63,
    // because the button row is a fixed-width `SpaceBetween` and the two labels
    // grow toward each other in the middle of it. Greek rather than Odia, whose
    // pair is longer still: Robolectric has no Odia glyphs, so that capture
    // would be tofu boxes whose advance widths say nothing about the real
    // thing. This is the one screenshot in the suite that renders translated
    // strings, so it is what catches a label collision or an unexpected wrap
    // before it ships to a locale nobody here reads.
    @Test
    @Config(qualifiers = "+el-rGR")
    fun telemetryConsent_longestLabels() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                ConsentCardOnPlainBackground()
            }
        }
        composeRule.waitForIdle()
        // By tag, not by text: the text under test is whatever the locale says.
        composeRule.onNodeWithTag(TELEMETRY_CONSENT_TAG).assertExists()

        // At the full w411dp window width, unlike the English captures' 720:
        // this one is about whether the longest labels fit the row a phone
        // actually renders, so cropping the canvas narrower than the device
        // would manufacture a wrap that no user sees. Taller too, because the
        // Greek body runs to three lines.
        capture("compose_telemetry_consent_longest_labels_robolectric.png", widthPx = 1079, heightPx = 640)
    }

    // Both cards at once, which is the state a first launch after a crash
    // actually produces — and the one where their ordering and the gap between
    // them can go wrong without either card being wrong on its own.
    @Test
    fun telemetryConsent_atTopOfSettings() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Light, dynamicColor = false) {
                SettingsScreen(
                    state = LauncherUiState(isTelemetryConsentPending = true),
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
        composeRule.onNodeWithText("Anonymous analytics").assertExists()

        capture("compose_telemetry_consent_settings_placement_robolectric.png", heightPx = 1280)
    }

    @Composable
    private fun ConsentCardOnPlainBackground() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter,
        ) {
            TelemetryConsentCard(
                onAllow = {},
                onDeny = {},
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
    }

    @Composable
    private fun BannerAtTopOfHome(isSharing: Boolean = false) {
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
                isSharing = isSharing,
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
