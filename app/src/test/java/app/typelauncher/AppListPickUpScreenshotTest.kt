package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
 * When an app is dragged out of the list it is "picked up" into the floating
 * drag overlay, so its own list tile hides its content (alpha 0) while keeping
 * its layout slot — the list must not reflow to close the gap, otherwise the
 * remaining icons would shuffle under the finger mid-drag. The dragged tile's
 * neighbours therefore stay exactly where they were; the screenshot shows the
 * empty slot the picked-up icon leaves behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppListPickUpScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val apps = (0 until 6).map { index ->
        installedApp(name = "App$index", badge = "${index + 1}")
    }

    private val noOpDrag = AppDragHandlers(
        onDragStart = {},
        onDrag = { _, _ -> },
        onDragEnd = { _, _ -> },
        onReportCenter = { _, _ -> },
        onLongPressArmed = {},
    )

    @Test
    fun pickedUpTile_keepsLayoutSlotForNeighbours() {
        renderGrid(draggedAppId = apps[0].id)

        val first = boundsOf("App0")
        val rowNeighbour = boundsOf("App1")
        val rowBelow = boundsOf("App3")

        // The picked-up tile still occupies its top-left slot: index 1 stays to
        // its right and index 3 stays below it, so nothing reflowed to fill the
        // gap the hidden icon leaves.
        assertTrue(
            "index 0 should still sit left of index 1 " +
                "(App0.left=${first.left}, App1.left=${rowNeighbour.left})",
            first.left < rowNeighbour.left,
        )
        assertTrue(
            "tiles should share a row (tops ${first.top} vs ${rowNeighbour.top})",
            kotlin.math.abs((first.top - rowNeighbour.top).value) <= 1f,
        )
        assertTrue(
            "index 0 should still sit above index 3 " +
                "(App0.top=${first.top}, App3.top=${rowBelow.top})",
            first.top < rowBelow.top,
        )

        capture("compose_app_list_picked_up_robolectric.png")
    }

    private fun renderGrid(draggedAppId: String?) {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(208.dp)
                        .height(160.dp)
                        .background(MaterialBackground),
                ) {
                    IconOnlyAppGrid(
                        apps = apps,
                        dockLimit = Int.MAX_VALUE,
                        iconSizeDp = 56,
                        highlightFirst = false,
                        state = rememberLazyGridState(),
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        appDrag = noOpDrag,
                        draggedAppId = draggedAppId,
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun boundsOf(name: String) =
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:$name", useUnmergedTree = true)
            .getBoundsInRoot()

    private fun installedApp(name: String, badge: String): InstalledApp {
        val component = ComponentName("com.example.$name", "com.example.$name.Main")
        return InstalledApp(
            name = name,
            packageName = "com.example.$name",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
            customBadge = badge,
        )
    }

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

    private companion object {
        val MaterialBackground = Color(0xFFEFEFEF)
    }
}
