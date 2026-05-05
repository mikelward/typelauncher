package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun swipingUpOnHomeWithRecentsClosed_opensRecentsAndDoesNotShowKeyboard() {
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
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
                    onRequestShowKeyboard = { requestShowKeyboardCount += 1 },
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
        // First-stage pull-up must not also re-show the keyboard — the
        // keyboard ask is the second stage.
        assertEquals(0, requestShowKeyboardCount)
        // Pull-up must never trigger the pull-down dispatch path.
        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingUpOnHomeWithRecentsAlreadyOpen_requestsShowKeyboard() {
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
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
                    onRequestShowKeyboard = { requestShowKeyboardCount += 1 },
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

        // Second-stage pull-up: don't re-ack recents, just re-show the IME.
        assertNull(recentsTarget)
        assertEquals(1, requestShowKeyboardCount)
        // Pull-up must never trigger the pull-down dispatch path.
        assertEquals(0, swipeDownCount)
    }

    @Test
    fun swipingUpOnHomeWithRecentsAlwaysShown_requestsShowKeyboardOnFirstPull() {
        // Regression: with `Show recents` toggled on, recents is already visible
        // even though `isRecentsOpen` defaults to false. The pull-up dispatch
        // must treat the visible-recents predicate (`isRecentsAlwaysShown ||
        // isRecentsOpen`) as the gate for the keyboard stage, otherwise users
        // with the setting on need an extra gesture to re-show the IME.
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
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
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetRecentsOpen = { recentsTarget = it },
                    onRequestShowKeyboard = { requestShowKeyboardCount += 1 },
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

        // Recents is already visible via the persistent setting, so the first
        // pull-up should skip the recents stage and ask for the keyboard.
        assertNull(recentsTarget)
        assertEquals(1, requestShowKeyboardCount)
    }

    @Test
    fun swipingUpOnHomeWithNotificationBarOpen_closesBarAndDoesNotShowKeyboard() {
        var notificationBarOpened: Boolean? = null
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        isNotificationBarOpen = true,
                        // Even with recents already open, the bar takes priority.
                        isRecentsOpen = true,
                        notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
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
                    onSetNotificationBarOpen = { notificationBarOpened = it },
                    onSetRecentsOpen = { recentsTarget = it },
                    onRequestShowKeyboard = { requestShowKeyboardCount += 1 },
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

        // Bar takes priority over both recents and the keyboard stage.
        assertEquals(false, notificationBarOpened)
        assertNull(recentsTarget)
        assertEquals(0, requestShowKeyboardCount)
    }

    @Test
    fun swipingUpOnAgenda_doesNotOpenRecentsOrShowKeyboard() {
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
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
                    onRequestShowKeyboard = { requestShowKeyboardCount += 1 },
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
        assertEquals(0, requestShowKeyboardCount)
    }

    @Test
    fun emittingOnKeyboardShowRequests_invokesKeyboardShowOnSearchCard() {
        val keyboardShowRequests = MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        var showCalled = false
        val fakeKeyboard = object : SoftwareKeyboardController {
            override fun show() { showCalled = true }
            override fun hide() {}
        }
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides fakeKeyboard) {
                TypeLauncherTheme {
                    TypeLauncherApp(
                        state = LauncherUiState(
                            filteredApps = emptyList(),
                            // Disable cold-start auto-show so the show() call we
                            // observe is the result of the keyboardShowRequests
                            // emit, not the LaunchedEffect on isKeyboardAutoShown.
                            isKeyboardAutoShown = false,
                        ),
                        keyboardShowRequests = keyboardShowRequests,
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
        }
        composeRule.waitForIdle()
        // Pre-condition: with auto-show disabled the IME has not been shown yet.
        assertFalse(
            "keyboard.show should not run on cold start when isKeyboardAutoShown=false",
            showCalled,
        )

        assertTrue(keyboardShowRequests.tryEmit(Unit))
        composeRule.waitForIdle()

        // The collector inside SearchCard requests focus *and* calls show. We
        // can't easily probe focus from here without importing assertIsFocused
        // (no sibling test imports it; CLAUDE.md says don't add it), so we
        // assert the show() side of the pair — that's enough to prove the
        // request flowed end-to-end through TypeLauncherApp → HomeScreen →
        // SearchCard's LaunchedEffect.
        assertTrue("keyboard.show was called after keyboardShowRequests emit", showCalled)
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

    // Regression for the chevron sitting mostly outside the row's bounds: a real
    // user tap lands on the visible chevron icon (centered ~2.dp left of the row
    // start), which the row's own pointerInput cannot see. The chevron itself
    // must handle the tap.
    @Test
    fun recentsOverflow_tapDirectlyOnStartChevronPagesRow() {
        val recentApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(
            LauncherUiState(
                filteredApps = emptyList(),
                recentApps = recentApps,
                isRecentsAlwaysShown = true,
            ),
        )

        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertIsDisplayed()
        val oldestBefore = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()

        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        val oldestAfter = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()
        assertTrue(oldestAfter.left < oldestBefore.left)
    }

    @Test
    fun dockOverflow_tapDirectlyOnEndChevronPagesRow() {
        val dockedApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)).copy(isDocked = true) }
        renderHome(LauncherUiState(filteredApps = emptyList(), dockedApps = dockedApps))

        composeRule.onNodeWithTag(DOCK_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
        val firstBefore = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()

        composeRule.onNodeWithTag(DOCK_SCROLL_END_CHEVRON_TAG).performTouchInput {
            down(Offset(width / 2f, height / 2f))
            up()
        }
        composeRule.waitForIdle()

        val firstAfter = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").getBoundsInRoot()
        assertTrue(firstAfter.left < firstBefore.left)
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
