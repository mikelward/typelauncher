package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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
 * Compose-only screenshot test for [BadgePickerDialog]. Hosts
 * [BadgePickerDialogContent] directly in a stub [ComponentActivity] — the
 * same pattern as [EditAppDialogScreenshotTest] — so the surrounding `Dialog`
 * popup window's measurement doesn't cascade with Compose's idle loop under
 * Robolectric. Guards the badge tiles' visual layout (in particular the
 * corner radius of the preset / flag tiles) so it can't silently drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BadgePickerDialogScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun badgePicker_showsDefaultPresetsAndFlagGrid() {
        composeRule.setContent {
            TypeLauncherTheme {
                BadgePickerDialogContent(
                    currentBadge = null,
                    onPickBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(BADGE_PICKER_DEFAULT_TILE_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(BADGE_PICKER_FLAG_GRID_TAG, useUnmergedTree = true)
            .assertIsDisplayed()

        captureSnapshot("compose_badge_picker_default_robolectric.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
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
