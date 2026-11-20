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
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A dock folder opened "Around your tap" keeps the close tile on the tapped row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderAroundTapUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyRowAboveTheApps_keepsADockRowsHeight() {
        // One app in a 2-row dock, folder in the bottom row: the top row is empty.
        val folder = ResolvedDockFolder(id = DOCK_FOLDER_ID_PREFIX + "f", members = listOf(app("Solo")))
        val slots = dockFolderOpenSlots(1, 5, FolderOpenStyle.AroundTap, DockPosition(1, 0), visibleRows = 2, isSortReversed = false)
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(400.dp).height(400.dp)) {
                    DockFolderInPlace(
                        folder = folder,
                        dockIconSizeDp = 43,
                        dockIconCount = 5,
                        dockLayout = DockLayout.IconOnly,
                        appIconTag = DOCK_APP_ICON_TAG,
                        onClose = {},
                        slots = slots,
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
        val close = composeRule.onNodeWithContentDescription("Close folder").fetchSemanticsNode().boundsInRoot
        val rowPx = with(composeRule.density) { dockSlotHeightDp(43, DockLayout.IconOnly).dp.toPx() }
        assertTrue("close $close sits on the top row (row height $rowPx)", close.top >= rowPx)
    }

    @Test
    fun onlyAnAroundTapFolderStartsAtItsBottom() {
        val tapped = DockPosition(0, 2)
        // 9 apps + close need 3 rows of 4 in a 2-row dock, reversed sort.
        assertTrue(dockFolderOpensScrolledToBottom(9, 4, FolderOpenStyle.AroundTap, tapped, 2, isSortReversed = true))
        assertFalse(dockFolderOpensScrolledToBottom(9, 4, FolderOpenStyle.InOrder, tapped, 2, isSortReversed = true))
        assertFalse(dockFolderOpensScrolledToBottom(9, 4, FolderOpenStyle.AroundTap, null, 2, isSortReversed = true))
        assertFalse(dockFolderOpensScrolledToBottom(9, 4, FolderOpenStyle.AroundTap, tapped, 2, isSortReversed = false))
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
