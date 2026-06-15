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
}
