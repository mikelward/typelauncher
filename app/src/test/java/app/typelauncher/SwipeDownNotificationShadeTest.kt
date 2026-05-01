package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SwipeDownNotificationShadeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun swipingDownOnCarousel_invokesOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = emptyList()),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
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
                    onSwipeDown = { swipeDownCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(1, swipeDownCount)
    }

    @Test
    fun swipingUpOnCarousel_doesNotInvokeOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = emptyList()),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
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
                    onSwipeDown = { swipeDownCount += 1 },
                )
            }
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingDownOnScrollableAppsList_doesNotInvokeOnSwipeDownCallback() {
        // Seed enough apps that the list is scrollable; vertical drags should scroll
        // the list rather than pull down the notification shade.
        val apps = (0 until 40).map { index ->
            InstalledApp(
                name = "App %02d".format(index),
                packageName = "app.typelauncher.fake$index",
                launchIntent = Intent(),
                user = Process.myUserHandle(),
                isWorkApp = false,
                launchWithLauncherApps = false,
            )
        }
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = apps),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
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
                    onSwipeDown = { swipeDownCount += 1 },
                )
            }
        }

        // First scroll the list down so it can scroll back up when we drag down.
        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        // Now a downward drag on the apps list should be consumed by the list,
        // not by the launcher's swipe-down handler.
        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertFalse(
            "Expected list scroll to consume the gesture, got $swipeDownCount swipe-down callbacks",
            swipeDownCount > 0,
        )
    }
}
