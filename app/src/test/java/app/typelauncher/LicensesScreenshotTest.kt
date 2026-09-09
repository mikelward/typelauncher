package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
        // Apache-2.0 §4 asks for attribution, and the license name alone does
        // not carry it — the dialog names who wrote the component too.
        composeRule.onNodeWithText("By The Android Open Source Project").assertIsDisplayed()

        composeRule.onNodeWithText("Apache License 2.0").performClick()
        composeRule.runOnIdle {
            assertEquals("https://spdx.org/licenses/Apache-2.0.html", openedUrl)
        }

        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("Version 1.13.0").assertDoesNotExist()
    }

    /**
     * The POM's declared developers are the usual source of an authors line,
     * but plenty of components name only the organization that published
     * them, and a few name nobody at all. All three shapes come from a
     * fixture rather than the bundled export, which today happens to carry a
     * developer for every component that names anyone.
     */
    @Test
    fun licenseDetails_nameEveryAuthor_orNoneWhenTheComponentNamesNobody() {
        composeRule.setContent {
            TypeLauncherTheme {
                LicensesContent(
                    libraries = Libs.Builder().withJson(ATTRIBUTION_FIXTURE).build(),
                    innerPadding = PaddingValues(0.dp),
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Organization only").performClick()
        composeRule.onNodeWithText("By Example Organization").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Two developers").performClick()
        composeRule.onNodeWithText("By Ada Example, Grace Example").assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()

        composeRule.onNodeWithText("Nobody named").performClick()
        composeRule.onNodeWithText("Version 3.0.0").assertIsDisplayed()
        composeRule.onNodeWithText("By ", substring = true).assertDoesNotExist()
    }

    /**
     * The details a component row opens — version, authors, and the license
     * link — as a snapshot. Rendered as `LibraryDetails` rather than through
     * the dialog: an `AlertDialog` lives in its own popup window, which the
     * decorView helper below can't reach, so the rows would otherwise have no
     * visual cover at all. Wrapped in a surface the way the dialog wraps it,
     * over a plain page background, so the capture reads as the dialog does.
     * Pinned to one fixed component rather than "the first in the list", so a
     * dependency change can't silently re-point the snapshot.
     */
    @Test
    fun licenseDetails_snapshot() {
        val library = libraries().libraries.first { it.uniqueId == "androidx.activity:activity" }
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 6.dp,
                    ) {
                        Box(modifier = Modifier.padding(24.dp)) {
                            LibraryDetails(library = library, onOpenLicenseUrl = {})
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Version 1.13.0").assertIsDisplayed()
        composeRule.onNodeWithText("By The Android Open Source Project").assertIsDisplayed()

        captureSnapshot("compose_license_details_robolectric.png", heightPx = 640)
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

/**
 * A hand-written export covering the attribution shapes the bundled one does
 * not: an organization with no developer, more than one developer, and no
 * attribution at all. Stock stand-in names throughout — nothing here is
 * anybody's.
 */
private const val ATTRIBUTION_FIXTURE = """
{
  "libraries": [
    {
      "uniqueId": "com.example:organization-only",
      "artifactVersion": "1.0.0",
      "name": "Organization only",
      "developers": [],
      "organization": { "name": "Example Organization" },
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:two-developers",
      "artifactVersion": "2.0.0",
      "name": "Two developers",
      "developers": [{ "name": "Ada Example" }, { "name": "Grace Example" }],
      "licenses": ["Apache-2.0"]
    },
    {
      "uniqueId": "com.example:nobody-named",
      "artifactVersion": "3.0.0",
      "name": "Nobody named",
      "developers": [],
      "licenses": ["Apache-2.0"]
    }
  ],
  "licenses": {
    "Apache-2.0": {
      "name": "Apache License 2.0",
      "url": "https://spdx.org/licenses/Apache-2.0.html",
      "hash": "Apache-2.0",
      "spdxId": "Apache-2.0"
    }
  }
}
"""
