package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
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
 * Compose-only screenshot test for [SearchAppNameHint], the faint drop-down
 * hint that names the top match while typing in icon-only mode. Production wraps
 * it in a non-focusable `Popup`; rendering the content directly here (the same
 * pattern as [BadgePickerDialogScreenshotTest]) keeps it in the captured decor
 * view, since a `Popup`'s separate window isn't part of the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchAppNameHintScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appNameHint_showsTopMatchName() {
        composeRule.setContent {
            TypeLauncherTheme {
                SearchAppNameHint(
                    name = "Calculator",
                    modifier = Modifier
                        .padding(16.dp)
                        .testTag(APP_NAME_HINT_TAG),
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(APP_NAME_HINT_TAG, useUnmergedTree = true)
            .assertIsDisplayed()

        captureSnapshot("compose_search_app_name_hint_robolectric.png")
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
