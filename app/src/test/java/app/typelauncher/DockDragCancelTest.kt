package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression coverage for dock-icon gestures that end abnormally
 * (`ACTION_CANCEL`: the system steals the pointer stream mid-gesture).
 * The dock's pointer loop used to handle only the clean lift: a canceled
 * long-press popped the actions menu as if the user had lifted, and a
 * canceled drag skipped `onDragEnd`, leaving the icon rendered "lifted" at
 * its last drag offset indefinitely.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class DockDragCancelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(name: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    private fun setLauncherContent() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        val state = LauncherUiState(filteredApps = emptyList(), dockedApps = docked)
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state,
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun canceledLongPressDoesNotOpenDockMenu() {
        setLauncherContent()
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            cancel()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("App info").assertDoesNotExist()
    }

    @Test
    fun canceledDragDropsIconBackIntoItsSlot() {
        setLauncherContent()
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val before: DpRect = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            // Past the dock's 8 dp drag slop (~21 px at 420 dpi) but well
            // inside the slot, so no reorder is committed mid-drag.
            moveBy(Offset(60f, 0f))
            cancel()
        }
        composeRule.waitForIdle()

        val after = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()
        assertEquals(
            "canceled drag must drop the icon back into its slot",
            before.left.value,
            after.left.value,
            1f,
        )
        assertEquals(before.top.value, after.top.value, 1f)
    }

    // Renders two loose docked apps and captures every (source, target) merge
    // the dock requests. App01 sits at column 0 and App02 at column 1, so
    // dragging App01 onto App02's center arms a folder merge that commits on a
    // clean release.
    private fun setFolderDockContent(merges: MutableList<Pair<String, String>>) {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
        )
        val state = LauncherUiState(
            filteredApps = emptyList(),
            dockedApps = docked,
            dockIconSizeDp = dockIconSizeForSlotCount(411, 4),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state,
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onMergeDock = { source, target -> merges.add(source to target) },
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    // Horizontal px offset that brings App01's center onto App02's center, so a
    // single moveBy lands the dragged icon squarely in the merge zone.
    private fun app01ToApp02CenterDeltaPx(): Float {
        val a1 = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()
        val a2 = composeRule.onNodeWithTag("$DOCK_APP_TAG:App02").getBoundsInRoot()
        val a1CenterX = (a1.left + a1.right) / 2
        val a2CenterX = (a2.left + a2.right) / 2
        return (a2CenterX - a1CenterX).value * composeRule.density.density
    }

    @Test
    fun cleanReleaseOnNeighborCenterCommitsMerge() {
        val merges = mutableListOf<Pair<String, String>>()
        setFolderDockContent(merges)
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val deltaPx = app01ToApp02CenterDeltaPx()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            moveBy(Offset(deltaPx, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(
            "a clean release on the neighbor's center must commit a merge (got $merges)",
            merges.size == 1,
        )
    }

    @Test
    fun canceledDragOnNeighborCenterDoesNotCommitMerge() {
        val merges = mutableListOf<Pair<String, String>>()
        setFolderDockContent(merges)
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        val deltaPx = app01ToApp02CenterDeltaPx()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            // Same drag that arms the merge above, but the system cancels the
            // gesture instead of a clean lift — no folder should be created.
            moveBy(Offset(deltaPx, 0f))
            cancel()
        }
        composeRule.waitForIdle()

        assertFalse(
            "a canceled drag must not commit a merge (got $merges)",
            merges.isNotEmpty(),
        )
    }
}
