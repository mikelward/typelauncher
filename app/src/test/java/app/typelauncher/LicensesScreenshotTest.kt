package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.util.withJson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot + interaction cover for the Settings → About → Licenses page.
 * The library list is built synchronously from the committed
 * res/raw/aboutlibraries.json so the snapshot is deterministic — production
 * loads the same JSON asynchronously through `rememberLibraries`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun libraries(): Libs =
        Libs.Builder().withJson(composeRule.activity, R.raw.aboutlibraries).build()

    @Test
    fun licenses_listsBundledComponents() {
        composeRule.setContent {
            TypeLauncherTheme {
                LicensesContent(
                    libraries = libraries(),
                    innerPadding = PaddingValues(0.dp),
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_LICENSES_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Open source licenses").assertExists()

        captureSnapshot("compose_licenses_robolectric.png")
    }

    /**
     * Tapping a component opens its details, and Done closes them again.
     * Interaction-only: the details live in a `Dialog` popup window, which the
     * decorView snapshot helper below can't capture.
     */
    @Test
    fun licenseDetails_showVersionAndLinkOutToTheLicense() {
        var openedUrl: String? = null
        composeRule.setContent {
            TypeLauncherTheme {
                LicensesContent(
                    libraries = libraries(),
                    innerPadding = PaddingValues(0.dp),
                    onBack = {},
                    onOpenLicenseUrl = { openedUrl = it },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Activity").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()

        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.runOnIdle {
            assertEquals("https://spdx.org/licenses/Apache-2.0.html", openedUrl)
        }

        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
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
