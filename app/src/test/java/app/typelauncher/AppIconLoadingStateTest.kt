package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the contract behind [awaitAppIconsResolved]: an [AppIcon] carries
 * [APP_ICON_LOADING_TAG] only while its async bitmap load is in flight, and
 * the tag is dropped once the load resolves — including when it resolves to
 * "no icon" (the app keeps rendering the empty placeholder, but there is
 * nothing left to wait for). Without the second case, a single iconless app
 * would hang every screenshot capture at the wait's timeout; without the
 * first, the wait would be vacuous and CI recordings would go back to
 * freezing an arbitrary mid-load subset of icons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoadingStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadingTagClearsOnceTheIconResolves() {
        val component = ComponentName(
            "app.typelauncher.loadingstate.withicon",
            "app.typelauncher.loadingstate.withicon.LaunchActivity",
        )
        shadowOf(context.packageManager).addActivityIcon(component, ColorDrawable(Color.RED))

        composeRule.setContent {
            TypeLauncherTheme {
                AppIcon(app = fakeApp("With Icon", component), size = 48.dp)
            }
        }
        composeRule.awaitAppIconsResolved()

        assertEquals(
            "no icon may still be marked loading after the wait returns",
            0,
            composeRule.onAllNodesWithTag(APP_ICON_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun loadingTagClearsForAnAppWithNoResolvableIcon() {
        // Nothing registered for this component: the load resolves without a
        // bitmap. The placeholder keeps rendering, but the loading tag must
        // drop so the screenshot wait terminates instead of timing out.
        val component = ComponentName(
            "app.typelauncher.loadingstate.iconless",
            "app.typelauncher.loadingstate.iconless.LaunchActivity",
        )

        composeRule.setContent {
            TypeLauncherTheme {
                AppIcon(app = fakeApp("Iconless", component), size = 48.dp)
            }
        }
        composeRule.awaitAppIconsResolved()

        assertEquals(
            "a resolved-but-iconless app must not stay marked loading",
            0,
            composeRule.onAllNodesWithTag(APP_ICON_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    private fun fakeApp(name: String, component: ComponentName): InstalledApp =
        InstalledApp(
            name = name,
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )
}
