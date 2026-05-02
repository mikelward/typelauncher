package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that horizontal swipes on scrollable bars (dock, recents, notification
 * bar) scroll the bar contents rather than navigate the carousel to a different
 * screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SwipeScrollPriorityTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(index: Int) = InstalledApp(
        name = "App %02d".format(index),
        packageName = "app.typelauncher.fake$index",
        launchIntent = Intent(),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    )

    @Test
    fun swipingLeftOnOverflowingDock_doesNotNavigateCarousel() {
        // Enough apps to overflow the dock row width on a 411dp screen.
        val dockedApps = (0 until 20).map { fakeApp(it) }
        // Track both Widgets and Agenda: a left-swipe from Home advances to
        // Widgets first (not Agenda), so checking only onShowAgenda would
        // silently pass even if the carousel did navigate one page.
        var widgetsShown = false
        var agendaShown = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        dockedApps = dockedApps,
                        isDockEnabled = true,
                    ),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { agendaShown = true },
                    onShowWidgets = { widgetsShown = true },
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

        composeRule.onNodeWithTag(DOCK_LIST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertFalse(
            "Expected dock scroll to consume the gesture, not carousel navigation to Widgets",
            widgetsShown,
        )
        assertFalse(
            "Expected dock scroll to consume the gesture, not carousel navigation to Agenda",
            agendaShown,
        )
    }

    @Test
    fun swipingLeftOnOverflowingRecentsBar_doesNotNavigateCarousel() {
        val recentApps = (0 until 20).map { fakeApp(it) }
        var widgetsShown = false
        var agendaShown = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        recentApps = recentApps,
                        isRecentsAlwaysShown = true,
                    ),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { agendaShown = true },
                    onShowWidgets = { widgetsShown = true },
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

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertFalse(
            "Expected recents bar scroll to consume the gesture, not carousel navigation to Widgets",
            widgetsShown,
        )
        assertFalse(
            "Expected recents bar scroll to consume the gesture, not carousel navigation to Agenda",
            agendaShown,
        )
    }

    @Test
    fun swipingLeftOnOverflowingNotificationBar_doesNotNavigateCarousel() {
        val notifyingApps = (0 until 20).map { fakeApp(it) }
        var widgetsShown = false
        var agendaShown = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = notifyingApps,
                        isNotificationsEnabled = true,
                        isNotificationBarOpen = true,
                        hasNotificationAccess = true,
                    ),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { agendaShown = true },
                    onShowWidgets = { widgetsShown = true },
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

        composeRule.onNodeWithTag(NOTIFICATION_BAR_LIST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertFalse(
            "Expected notification bar scroll to consume the gesture, not carousel navigation to Widgets",
            widgetsShown,
        )
        assertFalse(
            "Expected notification bar scroll to consume the gesture, not carousel navigation to Agenda",
            agendaShown,
        )
    }
}
