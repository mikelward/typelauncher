package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the drag-a-member-out-of-a-folder drop resolution against a *stale*
 * dock slot lattice. The dock's slot-center map is remembered and never
 * pruned, so after the dock shrinks (e.g. two rows to one) it still holds the
 * removed row's centers inside the card bounds. The drop search must ignore
 * them — before the `dockRowCount`/`dockColumnCount` filter, a release in the
 * card's lower padding resolved to the phantom row and moved the member onto
 * a dock row that no longer exists.
 *
 * Renders [DockFolderGrid] directly (same harness as
 * [DockFolderReorderInteractionTest]) with a hand-built dock lattice around
 * the grid's real on-screen position.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class DockFolderDragOutDropTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun member(name: String): InstalledApp {
        val component = ComponentName("com.example.$name", "com.example.$name.Launch")
        return InstalledApp(
            name = name,
            packageName = "com.example.$name",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    @Test
    fun dropNearAStaleRowCenterResolvesToTheLiveRow() {
        val apps = listOf("App01", "App02").map { member(it) }
        val folderId = DOCK_FOLDER_ID_PREFIX + "src"
        val folder = ResolvedDockFolder(id = folderId, members = apps)
        // Geometry is populated after first composition, once the dragged
        // tile's real root position is known.
        var dockBounds by mutableStateOf<Rect?>(null)
        var slotCenters by mutableStateOf(emptyMap<DockPosition, Offset>())
        var occupants by mutableStateOf(emptyMap<DockPosition, String>())
        val moves = mutableListOf<Triple<String, Int, Int>>()
        composeRule.setContent {
            TypeLauncherTheme {
                DockFolderGrid(
                    folder = folder,
                    dockIconSizeDp = 43,
                    dockIconCount = 4,
                    dockLayout = DockLayout.TitleBelow,
                    modifier = Modifier,
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
                    dockBoundsInRoot = dockBounds,
                    dockSlotCenters = slotCenters,
                    // The dock's live lattice is a single row of four columns;
                    // the row-1 entry below is a stale leftover.
                    dockRowCount = 1,
                    dockColumnCount = 4,
                    occupantByPosition = occupants,
                    onMoveMemberToDock = { appId, row, column ->
                        moves += Triple(appId, row, column)
                    },
                )
            }
        }
        composeRule.waitForIdle()

        val tileNode = composeRule.onNodeWithTag("$DOCK_FOLDER_POPUP_TAG:App01")
        val tileBounds = tileNode.fetchSemanticsNode().boundsInRoot
        val origin = tileBounds.center
        composeRule.runOnIdle {
            // Dock card around the tile, roomy enough that y = origin + 95 is
            // still inside it. Live row 0 sits at the tile's height; the stale
            // row-1 center sits 100px below — inside the card, exactly where
            // the shrunk-by-one-row dock's padding now is.
            dockBounds = Rect(
                left = origin.x - 150f,
                top = origin.y - 60f,
                right = origin.x + 300f,
                bottom = origin.y + 160f,
            )
            slotCenters = mapOf(
                DockPosition(0, 0) to Offset(origin.x, origin.y),
                DockPosition(0, 1) to Offset(origin.x + 100f, origin.y),
                // Stale: the dock is one row tall (dockRowCount = 1 above).
                DockPosition(1, 1) to Offset(origin.x + 100f, origin.y + 100f),
            )
            occupants = mapOf(DockPosition(0, 0) to folderId)
        }
        composeRule.waitForIdle()

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        tileNode.performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            // Leave the dock card entirely (arms the drag-out)…
            moveBy(Offset(0f, 400f))
            // …then come back to 5px from the stale row-1 center — 95px from
            // the live row-0 center — and release.
            moveBy(Offset(100f, -305f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(
            "drop must resolve to the live row-0 slot, not the stale phantom row",
            listOf(Triple(apps[0].id, 0, 1)),
            moves,
        )
    }
}
