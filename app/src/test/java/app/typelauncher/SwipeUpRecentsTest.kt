package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
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
    }

    private class CountingKeyboardController : SoftwareKeyboardController {
        var hideCount = 0
            private set

        override fun show() {}

        override fun hide() {
            hideCount += 1
        }
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
    fun swipingUpOnHome_requestsShowKeyboardBecauseRecentsIsAlwaysSecondary() {
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

        assertNull(recentsTarget)
        assertEquals(1, requestShowKeyboardCount)
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
    fun swipingUpOnHomeWithSecondaryTrayVisible_requestsShowKeyboardOnFirstPull() {
        // Recents is now always a secondary bar, so pull-up always means
        // "bring the keyboard back" rather than first opening recents.
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
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
    fun swipingUpOnHomeWithNotificationBarOpen_requestsShowKeyboard() {
        var notificationBarOpened: Boolean? = null
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        isNotificationBarOpen = true,
                        isRecentsOpen = true,
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

        assertNull(notificationBarOpened)
        assertNull(recentsTarget)
        assertEquals(1, requestShowKeyboardCount)
    }

    @Test
    fun pullingUpOnKeyboardTrayRequestsShowKeyboard() {
        // The keyboard tray (recents/notifications) is rendered inside the
        // carousel's pointerInput surface, so the existing vertical pull-up
        // detector should pick up gestures that start on the tray. Without
        // this hookup, a pull-up that starts on a notification icon or the
        // recents row never reaches the launcher.
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        isNotificationBarOpen = true,
                        isRecentsOpen = true,
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

        composeRule.onNodeWithTag(HOME_KEYBOARD_TRAY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_KEYBOARD_TRAY_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(1, requestShowKeyboardCount)
    }

    @Test
    fun pullingDownOnAppListDoesNotHideKeyboardOrOpenNotificationBar() {
        val keyboard = CountingKeyboardController()
        var notificationBarOpened: Boolean? = null
        val apps = (1..20).map { i -> fakeApp(name = "App%02d".format(i)) }
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                TypeLauncherTheme {
                    TypeLauncherApp(
                        state = LauncherUiState(
                            filteredApps = apps,
                            notificationPullDownBehavior = NotificationPullDownBehavior.BarBelow,
                            isKeyboardAutoShown = false,
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
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertNull("app-list scroll gestures must not open the notification bar", notificationBarOpened)
        assertEquals("app-list scroll gestures must not hide the keyboard", 0, keyboard.hideCount)
    }

    @Test
    fun swipingUpOnAgenda_doesNotOpenRecentsOrShowKeyboard() {
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
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
    fun recentsUsesKeyboardTray_whenKeyboardReservationExists() {
        val apps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) }
        val dockedApps = listOf(fakeApp(name = "Docked").copy(isDocked = true))
        val recentApps = listOf(fakeApp(name = "Recent"))
        val state = mutableStateOf(
            LauncherUiState(
                filteredApps = apps,
                dockedApps = dockedApps,
                recentApps = recentApps,
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
        val appsBefore = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val dockBefore = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        state.value = state.value.copy(isRecentsOpen = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_KEYBOARD_TRAY_TAG).assertIsDisplayed()
        val appsAfter = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val dockAfter = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        val recentsBounds = composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).getBoundsInRoot()
        assertEquals(appsBefore, appsAfter)
        assertEquals(dockBefore, dockAfter)
        assertTrue("recents tray should appear below the fixed dock", recentsBounds.top >= dockAfter.bottom)
    }

    @Test
    fun recentsOverflow_startChevronTapPagesRowWithCompactTarget() {
        val recentApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(
            LauncherUiState(
                filteredApps = emptyList(),
                recentApps = recentApps,
                isRecentsOpen = true,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
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
                isRecentsOpen = true,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
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
    fun dockGrowsToFitDockedAppsWithoutHorizontalScroll() {
        val dockedApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)).copy(isDocked = true) }
        renderHome(LauncherUiState(filteredApps = emptyList(), dockedApps = dockedApps))

        // With 12 docked apps and the default 4 icons per row, every app
        // must be in the composition tree on initial render — the dock
        // wraps to multiple rows instead of hiding overflow behind a
        // sideways scroller.
        for (i in 1..12) {
            composeRule.onNodeWithTag("$DOCK_APP_TAG:App%02d".format(i)).assertIsDisplayed()
        }
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
                        isRecentsOpen = true,
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
    fun swipingPastRecentsEnd_advancesCarousel() {
        // Recents auto-scrolls to its end, so a further swipeLeft can't be
        // absorbed by the row's horizontalScroll. The leftover motion flows
        // up to the launcher's vertical/horizontal arbitrator (the tray now
        // lives inside the carousel's pointerInput) and commits a Home →
        // Widgets page change once it crosses the 96 dp distance / 700 dp/s
        // fling threshold — same rule that keeps an in-row scroll from
        // accidentally flipping pages.
        var widgetsCount = 0
        val recentApps = (1..8).map { i -> fakeApp(name = "App%02d".format(i)) }
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        recentApps = recentApps,
                        isRecentsOpen = true,
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

        assertEquals("over-scrolling past recents end should advance the carousel", 1, widgetsCount)
    }
}
