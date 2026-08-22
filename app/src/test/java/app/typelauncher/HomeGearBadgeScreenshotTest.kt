package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for the Settings-gear badge slot on Home: nothing when
 * there's nothing pending, the Play-update dot when an update is available,
 * and the crash warning triangle — taking the slot over the update dot —
 * when a prior run crashed. Renders [HomeScreen] directly from a hand-built
 * [LauncherUiState], skipping the ViewModel/Activity/[DebugFileSink] chain:
 * the badge is pure state -> pixels, and the crash-detection mechanics that
 * would otherwise need seeding already have their own coverage
 * ([DebugFileSinkTest], `LauncherViewModelCrashBannerTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeGearBadgeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noBadgeWhenNothingPending() {
        setContent(LauncherUiState())

        composeRule.onNodeWithTag(CRASH_PENDING_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        capture("compose_home_gear_no_badge_robolectric.png")
    }

    @Test
    fun playUpdateBadgeWhenUpdateAvailable() {
        setContent(LauncherUiState(playUpdate = PlayUpdateState.Available(versionCode = 123)))

        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(CRASH_PENDING_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        capture("compose_home_gear_play_update_badge_robolectric.png")
    }

    @Test
    fun crashBadgeTakesPriorityOverPlayUpdateBadge() {
        setContent(
            LauncherUiState(
                isCrashBannerVisible = true,
                playUpdate = PlayUpdateState.Available(versionCode = 123),
            ),
        )

        composeRule.onNodeWithTag(CRASH_PENDING_BADGE_TAG, useUnmergedTree = true).assertExists()
        // One badge slot: the play-update dot yields to the crash triangle.
        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
        capture("compose_home_gear_crash_badge_robolectric.png")
    }

    private fun setContent(state: LauncherUiState) {
        composeRule.setContent {
            TypeLauncherTheme {
                HomeScreen(
                    state = state,
                    innerPadding = PaddingValues(),
                    bodyReady = true,
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onDismissRecent = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 1280) {
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
