package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                        isNotificationsEnabled = true,
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
                        isNotificationsEnabled = true,
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
                        isNotificationsEnabled = true,
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
    fun firstSwipeDownOnHomeOpensBar_doesNotExpandShade() {
        var swipeDownCount = 0
        var notificationBarTarget: Boolean? = null
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        isNotificationsEnabled = true,
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
}
