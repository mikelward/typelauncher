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
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithText("Help make Type Launcher better?").assertExists()
        // Both actions, not just the title (Codex, PR #702). The family copy is
        // two lines of title over three of body, and at the old 560px capture
        // height the button row fell off the bottom — leaving a recorded
        // baseline of a card with no buttons that a title-only assertion was
        // happy to accept. A screenshot that cannot see the control it exists
        // to check is a disabled check wearing a green tick.
        composeRule.onNodeWithText("No thanks").assertExists()
        composeRule.onNodeWithText("Yes please").assertExists()

        // At the device's own width, for the reason
        // [telemetryConsent_longestLabels] gives: the button row is a
        // fixed-width `SpaceBetween`, so capturing narrower than w411dp wraps
        // "Yes please" inside its pill in the picture while it sits on one line
        // on a phone. A snapshot that invents a defect is as useless as one
        // that hides a real one.
        capture(
            "compose_telemetry_consent_light_robolectric.png",
            heightPx = 720,
            requireWholeContent = true,
        )
    }

    @Test
    fun telemetryConsent_dark() {
        composeRule.setContent {
            TypeLauncherTheme(themeMode = ThemeMode.Dark, dynamicColor = false) {
                ConsentCardOnPlainBackground()
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Help make Type Launcher better?").assertExists()
        // Both actions, not just the title (Codex, PR #702). The family copy is
        // two lines of title over three of body, and at the old 560px capture
        // height the button row fell off the bottom — leaving a recorded
        // baseline of a card with no buttons that a title-only assertion was
        // happy to accept. A screenshot that cannot see the control it exists
        // to check is a disabled check wearing a green tick.
        composeRule.onNodeWithText("No thanks").assertExists()
        composeRule.onNodeWithText("Yes please").assertExists()

        // At the device's own width, for the reason
        // [telemetryConsent_longestLabels] gives: the button row is a
        // fixed-width `SpaceBetween`, so capturing narrower than w411dp wraps
        // "Yes please" inside its pill in the picture while it sits on one line
        // on a phone. A snapshot that invents a defect is as useless as one
        // that hides a real one.
        capture(
            "compose_telemetry_consent_dark_robolectric.png",
            heightPx = 720,
            requireWholeContent = true,
        )
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
        composeRule.onNodeWithTag(TELEMETRY_CONSENT_DENY_TAG).assertExists()
        composeRule.onNodeWithTag(TELEMETRY_CONSENT_ALLOW_TAG).assertExists()

        // Taller than the default, because the Greek body runs to three lines.
        // The full-width capture this test used to specify explicitly is now
        // what every capture in the class does — see [capture].
        capture(
            "compose_telemetry_consent_longest_labels_robolectric.png",
            heightPx = 800,
            requireWholeContent = true,
        )
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
        composeRule.onNodeWithText("Help make Type Launcher better?").assertExists()

        // No whole-content requirement here, unlike the isolated captures:
        // this is the scrolling Settings screen, which is taller than any
        // frame, so cropping it is the intent rather than a defect. What this
        // capture is for is the two cards' ordering and the gap between them,
        // and both are inside the frame at this height.
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

    private fun capture(
        name: String,
        widthPx: Int = 1079,
        heightPx: Int = 560,
        /**
         * Whether the whole composable must fit the canvas. True for a capture
         * of one card on a background, where anything cropped is a defect;
         * false for a capture of a scrolling screen, which is taller than any
         * frame and is cropped on purpose.
         */
        requireWholeContent: Boolean = false,
    ) {
        val root = composeRule.activity.window.decorView.rootView
        val widthSpec =
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY)
        if (requireWholeContent) {
            // Ask what the content actually needs at this width, before
            // forcing it into the frame — the whole point of Codex's PR #702
            // finding. Asserting on the buttons' semantics bounds *looks* like
            // the right check and is vacuous: laid out at a height too small
            // for it, the card reflows and every node still reports a position
            // inside the frame, while the drawn output is clipped. The
            // unconstrained measurement is the honest question, and it is what
            // fails at the 720x560 this class used to capture at.
            root.measure(
                widthSpec,
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            assertTrue(
                "$name: the content needs ${root.measuredHeight}px at ${widthPx}px wide, " +
                    "so a ${heightPx}px capture crops it",
                root.measuredHeight <= heightPx,
            )
        }
        // Ahead of the record/verify gate, so the check runs under a plain
        // `./gradlew test` rather than only where a file gets written.
        root.measure(
            widthSpec,
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
