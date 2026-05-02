package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SwipeUpRecentsTest {
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
    fun swipingUpOnHomeWithRecentsClosed_opensRecents() {
        var recentsTarget: Boolean? = null
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
                    onSetRecentsOpen = { recentsTarget = it },
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

        assertEquals(true, recentsTarget)
        // Pull-up must never trigger the pull-down dispatch path.
        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingUpOnHomeWithRecentsAlreadyOpen_doesNotReopenAndDoesNotFireSwipeDown() {
        var recentsTarget: Boolean? = null
        var swipeDownCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = emptyList(), isRecentsOpen = true),
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
                    onSetRecentsOpen = { recentsTarget = it },
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

        // Recents already open ⇒ pull-up is a no-op (no second-stage hand-off).
        assertNull(recentsTarget)
        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingUpOnAgenda_doesNotOpenRecents() {
        var recentsTarget: Boolean? = null
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        screen = LauncherScreen.Agenda,
                        agenda = AgendaUiState.Empty,
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
                    onSetRecentsOpen = { recentsTarget = it },
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

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        // Pull-up is Home-only — Widgets/Agenda ignore it.
        assertNull(recentsTarget)
    }

    @Test
    fun swipingHorizontallyOnOverflowingRecents_scrollsRecentsInsteadOfCarousel() {
        var widgetsCount = 0
        val recentApps = (1..8).map { i -> fakeApp(name = "App%02d".format(i)) }
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
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals("recents swipe should not navigate the carousel", 0, widgetsCount)
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
    }
}
