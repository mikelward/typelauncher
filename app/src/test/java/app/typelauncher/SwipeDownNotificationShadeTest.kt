package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
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

    @Composable
    private fun Launcher(
        state: LauncherUiState,
        onShowWidgets: (Int) -> Unit = {},
        onSetRecentsOpen: (Boolean) -> Unit = {},
        onSwipeDown: () -> Unit = {},
    ) {
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
                onShowWidgets = onShowWidgets,
                onShowHome = {},
                onSetRecentsOpen = onSetRecentsOpen,
                appWidgetHost = null,
                appWidgetManager = null,
                onAddWidget = {},
                onDismissWidgetPicker = {},
                onSelectWidget = {},
                onRemoveWidget = {},
                onSwipeDown = onSwipeDown,
            )
        }
    }

    @Test
    fun swipingDownOnHome_invokesOnSwipeDownCallback() {
        // Pull-down on Home goes straight to the system notification shade.
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = emptyList()),
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(1, swipeDownCount)
    }

    @Test
    fun swipingDownOnHomeWithRecentsOpen_closesRecents() {
        var recentsTarget: Boolean? = null
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(
                    filteredApps = emptyList(),
                    isRecentsOpen = true,
                ),
                onSetRecentsOpen = { recentsTarget = it },
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // Opposite-direction gesture hides the open bar; recents does not fall
        // through to the system shade.
        assertEquals(false, recentsTarget)
        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingDownOnWidgets_invokesOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(
                    destination = LauncherDestination.Widgets(),
                ),
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(1, swipeDownCount)
    }

    @Test
    fun swipingUpOnCarousel_doesNotInvokeOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = emptyList()),
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingDownOnScrollableAppsList_doesNotInvokeOnSwipeDownCallback() {
        // Seed enough apps that the list is scrollable; vertical drags should scroll
        // the list rather than pull down the notification shade.
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = fakeScrollableApps()),
                onSwipeDown = { swipeDownCount += 1 },
            )
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

    @Test
    fun diagonalVerticalDragOnScrollableAppsList_doesNotNavigateCarousel() {
        var widgetsCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = fakeScrollableApps()),
                onShowWidgets = { widgetsCount += 1 },
            )
        }

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput {
            down(center + Offset(x = 180f, y = 250f))
            moveTo(center + Offset(x = -180f, y = -250f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("vertical list drag should not navigate the carousel", 0, widgetsCount)
    }

    @Test
    fun pullingDownPastTopOfAppsList_doesNotInvokeOnSwipeDownCallback() {
        // A drag that starts in the scrollable apps list stays an app-list
        // gesture even after the list reaches its top edge mid-drag.
        var swipeDownCount = 0
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = fakeScrollableApps()),
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
    }

    @Test
    fun pushingUpPastBottomOfAppsList_doesNotOpenRecents() {
        var recentsOpened = false
        composeRule.setContent {
            Launcher(
                state = LauncherUiState(filteredApps = fakeScrollableApps()),
                onSetRecentsOpen = { recentsOpened = it },
            )
        }
        repeat(8) {
            composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(false, recentsOpened)
    }

    @Test
    fun pullingDownOnShortNonScrollableAppsList_doesNotInvokeOnSwipeDownCallback() {
        // A handful of apps doesn't fill the apps card, so the LazyColumn has
        // no scrolling room. Drags that start in the app list still stay app-list
        // gestures so the keyboard does not jump while the user explores results.
        val apps = (0 until 3).map { index ->
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
            Launcher(
                state = LauncherUiState(filteredApps = apps),
                onSwipeDown = { swipeDownCount += 1 },
            )
        }

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
    }

    private fun fakeScrollableApps(): List<InstalledApp> =
        (0 until 40).map { index ->
            InstalledApp(
                name = "App %02d".format(index),
                packageName = "app.typelauncher.fake$index",
                launchIntent = Intent(),
                user = Process.myUserHandle(),
                isWorkApp = false,
                launchWithLauncherApps = false,
            )
        }
}
