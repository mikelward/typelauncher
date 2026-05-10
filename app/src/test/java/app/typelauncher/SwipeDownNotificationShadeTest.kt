package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun swipingDownOnHomeWithNotificationBarClosed_opensBarInsteadOfShade() {
        var swipeDownCount = 0
        var notificationBarOpened = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        assertEquals(0, swipeDownCount)
        assertEquals(true, notificationBarOpened)
    }

    @Test
    fun swipingDownOnHomeWithBarAbove_opensBarInsteadOfShade() {
        var swipeDownCount = 0
        var notificationBarOpened = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarAbove,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        assertEquals(0, swipeDownCount)
        assertEquals(true, notificationBarOpened)
    }

    @Test
    fun swipingDownOnHomeWithLegacySystemBehavior_opensBarInsteadOfShade() {
        // The Pull down setting was removed; old persisted values no longer
        // change the first pull-down stage.
        var swipeDownCount = 0
        var notificationBarOpened = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notificationPullDownBehavior = NotificationPullDownBehavior.System,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        assertEquals(0, swipeDownCount)
        assertEquals(true, notificationBarOpened)
    }

    @Test
    fun swipingDownOnHomeWithLegacyNoneBehavior_opensBarInsteadOfDoingNothing() {
        var swipeDownCount = 0
        var notificationBarOpened = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notificationPullDownBehavior = NotificationPullDownBehavior.None,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        assertEquals(0, swipeDownCount)
        assertEquals(true, notificationBarOpened)
    }

    @Test
    fun swipingDownOnHomeWithNotificationBarClosed_hidesKeyboard() {
        var hideCalled = false
        val fakeKeyboard = object : SoftwareKeyboardController {
            override fun show() {}
            override fun hide() { hideCalled = true }
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides fakeKeyboard) {
                TypeLauncherTheme {
                    TypeLauncherApp(
                        state = LauncherUiState(
                            filteredApps = emptyList(),
                            notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                        ),
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
                        onShowAgenda = {},
                        onShowWidgets = {},
                        onShowHome = {},
                        onSetNotificationBarOpen = {},
                        appWidgetHost = null,
                        appWidgetManager = null,
                        onAddWidget = {},
                        onDismissWidgetPicker = {},
                        onSelectWidget = {},
                        onRemoveWidget = {},
                        onRequestCalendarPermission = {},
                        onOpenAgendaEvent = {},
                        onSwipeDown = {},
                    )
                }
            }
        }

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertTrue("keyboard hide was called when notification bar opened", hideCalled)
    }

    @Test
    fun swipingDownOnHomeWithNotificationBarOpen_invokesOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        isNotificationBarOpen = true,
                        keyboardReservation = KeyboardReservation(bottomPx = 900),
                    ),
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
    fun swipingDownOnAgenda_invokesOnSwipeDownCallback() {
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        destination = LauncherDestination.Agenda,
                        agenda = AgendaUiState.Empty,
                    ),
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

    @Test
    fun diagonalVerticalDragOnScrollableAppsList_doesNotNavigateCarousel() {
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
        var widgetsCount = 0
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
                    onShowWidgets = { widgetsCount += 1 },
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput {
            down(center + Offset(x = 180f, y = 250f))
            moveTo(center + Offset(x = -180f, y = -250f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("vertical list drag should not navigate the carousel", 0, widgetsCount)
    }

    @Test
    fun pullingDownPastTopOfAppsList_doesNotOpenNotificationBar() {
        val apps = fakeScrollableApps()
        var notificationBarOpened = false
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = apps,
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
        assertEquals(false, notificationBarOpened)
    }

    @Test
    fun pullingDownPastTopOfAppsListWithSystemBehavior_doesNotInvokeOnSwipeDownCallback() {
        val apps = fakeScrollableApps()
        var notificationBarOpened = false
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = apps,
                        notificationPullDownBehavior = NotificationPullDownBehavior.System,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
        assertEquals(false, notificationBarOpened)
    }

    @Test
    fun pushingUpPastBottomOfAppsList_doesNotHideNotificationBar() {
        val apps = fakeScrollableApps()
        var notificationBarOpen = true
        var recentsOpened = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = apps,
                        isNotificationBarOpen = true,
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpen = it },
                    onSetRecentsOpen = { recentsOpened = it },
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
        repeat(8) {
            composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
            composeRule.waitForIdle()
        }

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(true, notificationBarOpen)
        assertEquals(false, recentsOpened)
    }

    @Test
    fun pullingDownOnShortNonScrollableAppsList_doesNotOpenNotificationBar() {
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
        var notificationBarOpened = false
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = apps,
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                    ),
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetNotificationBarOpen = { notificationBarOpened = it },
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
        assertEquals(false, notificationBarOpened)
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
