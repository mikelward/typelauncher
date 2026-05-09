package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class DockReorderCarouselTest {
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

    // Regression: long-press + horizontal drag on a docked icon used to also be
    // claimed by the carousel and page Home → Widgets/Agenda. The dock's
    // pointerInput consumes pointer changes, but the carousel reads raw deltas
    // via positionChangeIgnoreConsumed and does not see the consumption (raw
    // pointerInput.consume() does not dispatch nested scroll). The fix lets the
    // dock tell the launcher's gesture surface that a reorder is in flight so
    // the carousel does not double-claim the same horizontal motion.
    @Test
    fun dragReorderingDockedApp_doesNotPageCarousel() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), dockedApps = docked))
        var showAgendaCount = 0
        var showWidgetsCount = 0
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
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(screen = LauncherScreen.Agenda)
                    },
                    onShowWidgets = {
                        showWidgetsCount += 1
                        state = state.copy(screen = LauncherScreen.Widgets)
                    },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            // `move(delay)` (unlike `advanceEventTime`) advances the test
            // scheduler by `delay`, which is what fires the dock's long-press
            // timer. Same trick `longClick` uses internally — without it the
            // timer never fires and the moveBy below cancels the gesture
            // before drag mode arms, so onDragStart never runs.
            move(longPressMs + 100)
            // Drag well past the carousel's 96 dp commit threshold (~252 px at
            // this test's density). Without the fix this would page Home → an
            // adjacent screen because the dock's pointerInput consumption is
            // invisible to the carousel arbitrator.
            moveBy(Offset(700f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("dock reorder must not page the carousel to Widgets", 0, showWidgetsCount)
        assertEquals("dock reorder must not page the carousel to Agenda", 0, showAgendaCount)
        assertEquals(LauncherScreen.Home, state.screen)
    }

    @Test
    fun dragReorderingDockedApp_canMoveVerticallyToSparseSlot() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var positions by mutableStateOf(
            docked.mapIndexed { index, app -> app.id to DockPosition(row = 0, column = index) }.toMap(),
        )
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                dockedApps = docked,
                dockPositions = positions,
            ),
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
                    onReorderDock = { appId, row, column ->
                        positions = positions + (appId to DockPosition(row, column))
                        state = state.copy(dockPositions = positions)
                    },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
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

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            moveBy(Offset(0f, 220f))
            moveBy(Offset(0f, 220f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(DockPosition(row = 1, column = 0), positions.getValue(docked[0].id))
    }
}
