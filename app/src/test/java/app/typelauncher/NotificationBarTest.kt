package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class NotificationBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun resetActiveNotifications() {
        ActiveNotifications.update(emptyMap())
    }

    @After
    fun clearActiveNotifications() {
        ActiveNotifications.update(emptyMap())
    }

    private fun fakeApp(name: String, packageName: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    @Test
    fun notificationBarHiddenByDefault() {
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
                )
            }
        }

        composeRule.onNodeWithTag(NOTIFICATION_BAR_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun notificationBarRendersAppsWithBadge_whenOpenAndPermissionGranted() {
        val notifying = listOf(
            fakeApp(name = "Mail", packageName = "com.example.mail"),
            fakeApp(name = "Chat", packageName = "com.example.chat"),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = notifying,
                        isNotificationBarOpen = true,
                        hasNotificationAccess = true,
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
                )
            }
        }

        composeRule.onNodeWithTag(NOTIFICATION_BAR_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(NOTIFICATION_BAR_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$NOTIFICATION_BAR_APP_TAG:Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$NOTIFICATION_BAR_APP_TAG:Chat").assertIsDisplayed()
        // Each app gets a badge dot — presence indicator, no count.
        composeRule.onNodeWithTag("$NOTIFICATION_BAR_BADGE_TAG:Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$NOTIFICATION_BAR_BADGE_TAG:Chat").assertIsDisplayed()
        composeRule.onNodeWithTag(NOTIFICATION_BAR_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(NOTIFICATION_BAR_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun notificationBarShowsEmptyHint_whenOpenWithPermissionButNoApps() {
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = emptyList(),
                        isNotificationBarOpen = true,
                        hasNotificationAccess = true,
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
                )
            }
        }

        composeRule.onNodeWithTag(NOTIFICATION_BAR_HINT_TAG).assertIsDisplayed()
    }

    @Test
    fun notificationBarShowsPermissionCta_whenAccessNotGranted() {
        var permissionRequested = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = emptyList(),
                        isNotificationBarOpen = true,
                        hasNotificationAccess = false,
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
                    onRequestNotificationAccess = { permissionRequested = true },
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

        composeRule.onNodeWithTag(NOTIFICATION_BAR_PERMISSION_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(NOTIFICATION_BAR_PERMISSION_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(true, permissionRequested)
    }

    @Test
    fun notificationBarLongPress_showsDismissAndSettingsActionsOnly() {
        val mail = fakeApp(name = "Mail", packageName = "com.example.mail")
        var dismissed: InstalledApp? = null
        var settingsOpened: InstalledApp? = null
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = listOf(mail),
                        isNotificationBarOpen = true,
                        hasNotificationAccess = true,
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
                    onDismissNotifications = { dismissed = it },
                    onOpenNotificationSettings = { settingsOpened = it },
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

        composeRule.onNodeWithTag("$NOTIFICATION_BAR_APP_TAG:Mail").performTouchInput { longClick() }
        composeRule.waitForIdle()

        // Notification-shaped actions only — no App info / Dock / Reset rank /
        // Hide; those live on the main app list.
        composeRule.onNodeWithTag("$DISMISS_NOTIFICATIONS_ACTION_TAG:Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$NOTIFICATION_SETTINGS_ACTION_TAG:Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Mail").assertDoesNotExist()
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Mail").assertDoesNotExist()
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:Mail").assertDoesNotExist()
        composeRule.onNodeWithTag("$HIDE_APP_ACTION_TAG:Mail").assertDoesNotExist()

        composeRule.onNodeWithTag("$DISMISS_NOTIFICATIONS_ACTION_TAG:Mail").performClick()
        composeRule.waitForIdle()
        assertEquals(mail, dismissed)
        assertEquals(null, settingsOpened)
    }

    @Test
    fun notificationBarSettingsAction_invokesOpenNotificationSettingsCallback() {
        val mail = fakeApp(name = "Mail", packageName = "com.example.mail")
        var settingsOpened: InstalledApp? = null
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        notifyingApps = listOf(mail),
                        isNotificationBarOpen = true,
                        hasNotificationAccess = true,
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
                    onOpenNotificationSettings = { settingsOpened = it },
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

        composeRule.onNodeWithTag("$NOTIFICATION_BAR_APP_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$NOTIFICATION_SETTINGS_ACTION_TAG:Mail").performClick()
        composeRule.waitForIdle()

        assertEquals(mail, settingsOpened)
    }

    @Test
    fun firstSwipeDownOnHomeOpensBar_doesNotExpandShade() {
        var swipeDownCount = 0
        var notificationBarTarget: Boolean? = null
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
                    onSetNotificationBarOpen = { notificationBarTarget = it },
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

        // First swipe-down: bar closed → open it instead of pulling down the shade.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, swipeDownCount)
        assertNotNull("Expected notification bar open call", notificationBarTarget)
        assertEquals(true, notificationBarTarget)
    }

    @Test
    fun openingNotificationBar_pushesDockUpAndLandsAtBottom() {
        val apps = (1..12).map { index ->
            fakeApp(name = "App%02d".format(index), packageName = "com.example.app$index")
        }
        val docked = listOf(fakeApp(name = "Docked", packageName = "com.example.docked").copy(isDocked = true))
        val notifying = listOf(fakeApp(name = "Mail", packageName = "com.example.mail"))
        val state = mutableStateOf(
            LauncherUiState(
                filteredApps = apps,
                dockedApps = docked,
                notifyingApps = notifying,
                hasNotificationAccess = true,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state.value,
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
                )
            }
        }
        composeRule.waitForIdle()
        val dockBefore = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        state.value = state.value.copy(isNotificationBarOpen = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_BOTTOM_BAR_TAG).assertIsDisplayed()
        val dockAfter = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        val barBounds = composeRule.onNodeWithTag(NOTIFICATION_BAR_CARD_TAG).getBoundsInRoot()
        // The notification bar takes real space at the bottom, so the dock
        // shifts up to make room and the bar sits below the dock.
        assertTrue(
            "opening the notification bar should push the dock up (dock.top ${dockAfter.top} < before ${dockBefore.top})",
            dockAfter.top < dockBefore.top,
        )
        assertTrue("notification bar should appear below the dock", barBounds.top >= dockAfter.bottom)
    }

    @Test
    fun notificationBarShows_whenOpenedAfterFirstComposition() {
        val apps = (1..12).map { index ->
            fakeApp(name = "App%02d".format(index), packageName = "com.example.app$index")
        }
        val docked = listOf(fakeApp(name = "Docked", packageName = "com.example.docked").copy(isDocked = true))
        val notifying = listOf(fakeApp(name = "Mail", packageName = "com.example.mail"))
        val state = mutableStateOf(
            LauncherUiState(
                filteredApps = apps,
                dockedApps = docked,
                notifyingApps = notifying,
                hasNotificationAccess = true,
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state.value,
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
                )
            }
        }
        composeRule.waitForIdle()

        state.value = state.value.copy(isNotificationBarOpen = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_BOTTOM_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(NOTIFICATION_BAR_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun closedBottomBar_showsNoCard() {
        // With neither bar opened by a gesture, the bottom-bar slot collapses
        // and no recents / notification card is composed — regardless of the
        // auto-shown keyboard, which no longer drives the bars.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        recentApps = listOf(fakeApp(name = "Mail", packageName = "com.example.mail")),
                        keyboardReservation = KeyboardReservation(bottomPx = 900),
                        isKeyboardAutoShown = true,
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
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertDoesNotExist()
        composeRule.mainClock.autoAdvance = true
    }

    @Test
    fun bottomBarHidesWhenSwipingAwayFromHome() {
        // The open bar belongs to Home: swiping to a sibling page clears
        // isRecentsOpen (via onShowWidgets), so once the page change settles the
        // recents card animates out and is dropped from composition.
        val screenState = mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                recentApps = listOf(fakeApp(name = "Mail", packageName = "com.example.mail")),
                keyboardReservation = KeyboardReservation(bottomPx = 900),
                isKeyboardAutoShown = true,
                isRecentsOpen = true,
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = screenState.value,
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
                    onShowWidgets = {
                        screenState.value = screenState.value.copy(
                            destination = LauncherDestination.Widgets(),
                            isRecentsOpen = false,
                        )
                    },
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
        // Precondition: the recents bar is visible while idle on Home.
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // Once the page change settles, recents is closed (isRecentsOpen=false)
        // and the card has animated out of composition.
        assertEquals(LauncherDestination.Widgets(0), screenState.value.destination)
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertDoesNotExist()
    }
}
