package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A package event evicts that app's icon and re-keys every live icon. A
 * permanently composed one (the dock) must keep painting its previous bitmap
 * while the fresh one decodes, rather than dropping to the empty placeholder:
 * an app that fires package events in bursts otherwise blinks in the dock on
 * every one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconReloadHoldsStillTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun evictionKeepsPaintingThePreviousBitmapUntilTheReloadLands() {
        val component = ComponentName(
            "app.typelauncher.holdstill",
            "app.typelauncher.holdstill.LaunchActivity",
        )
        val app = InstalledApp(
            name = "Hold Still",
            packageName = component.packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )
        val sizePx = with(composeRule.density) { ICON_SIZE.roundToPx() }
        val warm = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.RED) }
            .asImageBitmap()
        AppIconLoader.put(app.iconCacheId, sizePx, warm)
        val painted = mutableListOf<AppIconResolution>()

        composeRule.setContent {
            val resolution = rememberAppIconResolution(app, ICON_SIZE)
            SideEffect { painted += resolution }
        }
        composeRule.waitForIdle()
        assertSame("the warm cache entry paints on the first frame", warm, painted.first().bitmap)

        val before = painted.size
        composeRule.runOnIdle { AppIconLoader.evict(component.packageName, Process.myUserHandle()) }
        composeRule.waitForIdle()

        // Whatever the reload eventually resolves to, every frame painted
        // while it was still in flight must show the previous art.
        val reloading = painted.drop(before).filter { !it.isResolved }
        assertTrue("the eviction must recompose the icon into a reload", reloading.isNotEmpty())
        reloading.forEach { assertSame("a reloading frame dropped the previous art", warm, it.bitmap) }
    }

    private companion object {
        val ICON_SIZE = 48.dp
    }
}
