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
 * Verifies the icon-only app grid's rank order matches the dock.
 *
 * Under a reversed sort the grid flips its vertical axis (index 0 on the bottom
 * row, nearest the keyboard) and fills each row left-to-right, so the
 * highest-rank app (index 0) lands at the bottom-*left* — the same corner the
 * dock's highest-rank icon (its bottom-left) surfaces into when the dock is
 * hidden. Forward sorts are unchanged: index 0 stays top-left. Both are checked
 * by measuring the rendered tile positions, with a screenshot for the visual
 * diff.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconOnlyGridOrderScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Six apps at a 3-column width give two full rows, so the test can assert
    // both axes: index 0 vs. its row neighbour (cross axis) and vs. the row
    // above (main axis).
    private val apps = (0 until 6).map { index ->
        installedApp(name = "App$index", badge = "${index + 1}")
    }

    @Test
    fun reversedSort_placesHighestRankAtBottomLeft() {
        renderGrid(reverseLayout = true)

        val first = boundsOf("App0")
        val rowNeighbour = boundsOf("App1")
        val rowAbove = boundsOf("App3")

        assertTrue(
            "index 0 should sit to the left of index 1 in its row " +
                "(App0.left=${first.left}, App1.left=${rowNeighbour.left})",
            first.left < rowNeighbour.left,
        )
        assertSameRow(first, rowNeighbour)
        assertTrue(
            "index 0 should sit below index 3 (App0.top=${first.top}, App3.top=${rowAbove.top})",
            first.top > rowAbove.top,
        )
        assertSameColumn(first, rowAbove)

        capture("compose_icon_grid_reversed_order_robolectric.png")
    }

    @Test
    fun forwardSort_keepsHighestRankAtTopLeft() {
        renderGrid(reverseLayout = false)

        val first = boundsOf("App0")
        val rowNeighbour = boundsOf("App1")
        val rowBelow = boundsOf("App3")

        assertTrue(
            "index 0 should sit to the left of index 1 in its row " +
                "(App0.left=${first.left}, App1.left=${rowNeighbour.left})",
            first.left < rowNeighbour.left,
        )
        assertSameRow(first, rowNeighbour)
        assertTrue(
            "index 0 should sit above index 3 (App0.top=${first.top}, App3.top=${rowBelow.top})",
            first.top < rowBelow.top,
        )
        assertSameColumn(first, rowBelow)

        capture("compose_icon_grid_forward_order_robolectric.png")
    }

    private fun renderGrid(reverseLayout: Boolean) {
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
                        reverseLayout = reverseLayout,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun boundsOf(name: String) =
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:$name", useUnmergedTree = true)
            .getBoundsInRoot()

    private fun assertSameRow(a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect) {
        assertTrue(
            "tiles should share a row (tops ${a.top} vs ${b.top})",
            kotlin.math.abs((a.top - b.top).value) <= 1f,
        )
    }

    private fun assertSameColumn(a: androidx.compose.ui.unit.DpRect, b: androidx.compose.ui.unit.DpRect) {
        assertTrue(
            "tiles should share a column (lefts ${a.left} vs ${b.left})",
            kotlin.math.abs((a.left - b.left).value) <= 1f,
        )
    }

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

    private companion object {
        val MaterialBackground = Color(0xFFEFEFEF)
    }
}
