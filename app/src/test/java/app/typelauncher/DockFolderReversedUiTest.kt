package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Under a reversed sort a dock folder fills from the bottom, as the app list does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderReversedUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val rowDp = dockSlotHeightDp(43, DockLayout.IconOnly) + DOCK_ITEM_SPACING_DP

    @Test
    fun smallFolder_sitsOnTheBottomRow() {
        // One app in a 2-row dock: close and the app on the bottom row.
        show(memberCount = 1, dockRows = 2)
        val close = composeRule.onNodeWithContentDescription("Close folder").fetchSemanticsNode().boundsInRoot
        val rowPx = with(composeRule.density) { rowDp.dp.toPx() }
        assertTrue("close $close not on the bottom row", close.top >= rowPx - 1f)
    }

    @Test
    fun folderTallerThanTheDock_opensAtTheBottom_whereRankOneIs() {
        // 12 apps + close need 3 rows of 5 in a 1-row dock; the folder opens
        // scrolled to its bottom row, which holds close and rank 1.
        show(memberCount = 12, dockRows = 1)
        val close = composeRule.onNodeWithContentDescription("Close folder").fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val dockBottom = with(composeRule.density) { rowDp.dp.toPx() }
        assertTrue("close $close not in view (root $root)", close.top >= 0f && close.bottom <= dockBottom + 1f)
    }

    private fun show(memberCount: Int, dockRows: Int) {
        val folder = ResolvedDockFolder(
            id = DOCK_FOLDER_ID_PREFIX + "f",
            members = (1..memberCount).map { app("App$it") },
        )
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(400.dp).height((rowDp * dockRows - DOCK_ITEM_SPACING_DP).dp)) {
                    DockFolderInPlace(
                        folder = folder,
                        dockIconSizeDp = 43,
                        dockIconCount = 5,
                        dockLayout = DockLayout.IconOnly,
                        appIconTag = DOCK_APP_ICON_TAG,
                        onClose = {},
                        visibleRows = dockRows,
                        isSortReversed = true,
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
                        onUninstallApp = {},
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun app(name: String) = InstalledApp(
        name = name,
        packageName = "com.example.${name.lowercase()}",
        launchIntent = Intent.makeMainActivity(ComponentName("com.example.${name.lowercase()}", "Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    )
}
