package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: dragging an app out of the list must lift it into the floating
 * drag overlay (the same `FolderMemberDragOverlay` the dock uses), so the icon
 * tracks the finger instead of sitting inertly in the list. Asserts the overlay
 * is composed once the drag crosses the pick-up slop, and absent before it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class AppListDragOverlayTest {
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

    @Test
    fun draggingAppFromList_liftsItIntoFloatingOverlay() {
        val apps = (1..30).map { fakeApp("App%02d".format(it)) }
        var state by mutableStateOf(
            LauncherUiState(filteredApps = apps, dockedApps = emptyList()),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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

        // No drag in flight: no floating overlay.
        composeRule.onNodeWithTag(DRAG_OVERLAY_TAG).assertDoesNotExist()

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        // Hold the drag mid-flight (no up) so the lifted overlay stays composed.
        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            // Past the 8 dp pick-up slop so the drag arms and the icon lifts.
            moveBy(Offset(0f, -200f))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DRAG_OVERLAY_TAG).assertExists()
    }
}
