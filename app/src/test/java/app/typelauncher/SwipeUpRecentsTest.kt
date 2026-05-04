package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun renderHome(state: LauncherUiState) {
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
    }

    @Test
    fun homeReadyDoesNotWaitForImeWhenKeyboardAutoShowIsDisabled() {
        var homeReadyCount = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        isFreshAppLoadComplete = true,
                        isKeyboardAutoShown = false,
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
                    onHomeReady = { homeReadyCount += 1 },
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

        assertEquals(1, homeReadyCount)
    }

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
    fun dockOverflow_endChevronTapPagesRowWithCompactTarget() {
        val dockedApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)).copy(isDocked = true) }
        renderHome(LauncherUiState(filteredApps = emptyList(), dockedApps = dockedApps))

        val chevronBounds = composeRule.onNodeWithTag(DOCK_SCROLL_END_CHEVRON_TAG).getBoundsInRoot()
        assertTrue((chevronBounds.right - chevronBounds.left).value <= 33f)
        assertTrue((chevronBounds.bottom - chevronBounds.top).value <= 33f)
        val firstBefore = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()

        composeRule.onNodeWithTag(DOCK_LIST_TAG).performTouchInput {
            val position = Offset(x = width - 1f, y = height / 2f)
            down(position)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_SCROLL_START_CHEVRON_TAG).assertIsDisplayed()
        val firstAfter = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()
        assertTrue(firstAfter.left < firstBefore.left)
    }

    @Test
    fun recentsOverflow_startChevronTapPagesRowWithCompactTarget() {
        val recentApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(
            LauncherUiState(
                filteredApps = emptyList(),
                recentApps = recentApps,
                isRecentsAlwaysShown = true,
            ),
        )

        val chevronBounds = composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).getBoundsInRoot()
        assertTrue((chevronBounds.right - chevronBounds.left).value <= 33f)
        assertTrue((chevronBounds.bottom - chevronBounds.top).value <= 33f)
        val oldestBefore = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput {
            val position = Offset(x = 1f, y = height / 2f)
            down(position)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
        val oldestAfter = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()
        assertTrue(oldestAfter.left < oldestBefore.left)
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
    }

    @Test
    fun swipingPastRecentsEnd_navigatesCarousel() {
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

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals("recents end pull should advance the carousel", 1, widgetsCount)
    }
}
