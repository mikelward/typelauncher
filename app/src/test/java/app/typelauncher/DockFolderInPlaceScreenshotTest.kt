package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot coverage for the open folder rendered *in place of the dock*: it
 * must occupy exactly the dock card's footprint with no reflow. A folder with
 * fewer apps than the dock had slots uses that space as-is; a folder with more
 * scrolls within it.
 *
 * Each scenario wraps [DockFolderInPlace] in a [SectionCard] sized to a fixed
 * dock height (one icon row), so the captures show the folder living inside the
 * dock's box rather than growing past it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderInPlaceScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val iconDp = 43
    private val columns = 5

    @Test
    fun fewerAppsThanSlots_usesDockSpaceWithoutReflow() {
        // 3 members + the close tile = 4 cells in a 5-wide dock → one row, fits
        // the one-row dock footprint with room to spare (no reflow, no scroll).
        captureInDockCard(
            name = "fewer_than_slots",
            title = "3 apps in a 5-slot, 1-row dock — fits in place",
            folder = sampleFolder(3),
            dockRows = 1,
        )
    }

    @Test
    fun moreAppsThanSlots_scrollsWithinDockSpace() {
        // 12 members + close = 13 cells → 3 rows of content inside a one-row dock
        // footprint, so the grid scrolls within that fixed box.
        captureInDockCard(
            name = "more_than_slots",
            title = "12 apps in a 5-slot, 1-row dock — scrolls in place",
            folder = sampleFolder(12),
            dockRows = 1,
        )
    }

    @Test
    fun twoRowDock_packsFromTopLeftWithCloseLast() {
        // 4 members + close on a 5-wide, 2-row dock: the members pack from the
        // top-left in rank order (App1 top-left, like the dock) with the close tile
        // last.
        captureInDockCard(
            name = "two_row_packed",
            title = "4 apps in a 5-slot, 2-row dock — top-left, close last",
            folder = sampleFolder(4),
            dockRows = 2,
        )
    }

    @Composable
    private fun NoopInPlace(
        folder: ResolvedDockFolder,
        modifier: Modifier,
    ) {
        DockFolderInPlace(
            folder = folder,
            dockIconSizeDp = iconDp,
            dockIconCount = columns,
            dockLayout = DockLayout.TitleBelow,
            appIconTag = DOCK_APP_ICON_TAG,
            onClose = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onRemoveFromFolder = {},
            onUndockFromFolder = {},
            onRenameApp = { _, _ -> },
            onResetRank = {},
            onSetAppIconOverride = {},
            onClearAppIconOverride = {},
            onSetAppBadge = { _, _ -> },
            onHideApp = {},
            modifier = modifier,
        )
    }

    private fun captureInDockCard(
        name: String,
        title: String,
        folder: ResolvedDockFolder,
        dockRows: Int,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                val rowHeight = dockSlotHeightDp(iconDp, DockLayout.TitleBelow, 1f)
                val dockContentHeight = (rowHeight * dockRows +
                    (dockRows - 1) * DOCK_ITEM_SPACING_DP).dp
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title)
                    // The dock card; its content is fixed to the dock's footprint
                    // so the open folder must live inside it.
                    SectionCard {
                        Box(modifier = Modifier.height(dockContentHeight)) {
                            NoopInPlace(
                                folder = folder,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("compose_dock_folder_in_place_${name}_robolectric.png")
    }

    private fun sampleFolder(memberCount: Int): ResolvedDockFolder {
        val members = (0 until memberCount).map { index ->
            installedApp(name = "App${index + 1}", packageName = "com.example.app${index + 1}")
        }
        return ResolvedDockFolder(
            id = DOCK_FOLDER_ID_PREFIX + "sample",
            members = members,
            name = null,
        )
    }

    private fun installedApp(name: String, packageName: String): InstalledApp {
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 700) {
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
