package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.LayoutDirection
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

    private fun renderHome(
        state: LauncherUiState,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                renderHomeContent(state)
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun renderHomeContent(state: LauncherUiState) {
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
    fun swipingUpOnHomeWithNothingOpen_opensRecents() {
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

        // First pull-up with nothing open reveals the recents bar.
        assertEquals(true, recentsTarget)
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
    fun swipingUpOnHomeWithNotificationBarOpen_closesNotificationBar() {
        var notificationBarOpened: Boolean? = null
        var recentsTarget: Boolean? = null
        var requestShowKeyboardCount = 0
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

        // Opposite-direction gesture hides the open bar — pull-up closes the
        // notification bar rather than re-showing the keyboard or opening recents.
        assertEquals(false, notificationBarOpened)
        assertNull(recentsTarget)
        assertEquals(0, requestShowKeyboardCount)
    }

    @Test
    fun pullingUpOnBottomBarRequestsShowKeyboard() {
        // The bottom bar (recents/notifications) is rendered inside the
        // carousel's pointerInput surface, so the existing vertical pull-up
        // detector should pick up gestures that start on the bar. Without
        // this hookup, a pull-up that starts on the recents row never reaches
        // the launcher. With recents already open, a pull-up re-shows the IME.
        var requestShowKeyboardCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        filteredApps = emptyList(),
                        recentApps = listOf(fakeApp(name = "Recent")),
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

        composeRule.onNodeWithTag(HOME_BOTTOM_BAR_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_BOTTOM_BAR_TAG).performTouchInput { swipeUp() }
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
    fun openingRecents_pushesDockUpAndLandsAtBottom() {
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
        val dockBefore = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        state.value = state.value.copy(isRecentsOpen = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_BOTTOM_BAR_TAG).assertIsDisplayed()
        val dockAfter = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        val recentsBounds = composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).getBoundsInRoot()
        // The recents bar takes real space at the bottom, so the dock shifts up
        // to make room — "everything moves up" — and the bar sits below the dock.
        assertTrue(
            "opening recents should push the dock up (dock.top ${dockAfter.top} < before ${dockBefore.top})",
            dockAfter.top < dockBefore.top,
        )
        assertTrue("recents bar should appear below the dock", recentsBounds.top >= dockAfter.bottom)
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
            // Lands in the 21 px gap between App8 (partially visible from
            // the left at viewport.x ≈ [-181, 29]) and App9 ([50, 260]) on
            // the pinned-to-end recents row, AND inside the chevron icon's
            // 14 dp (~37 px at the test's 2.625 density) overflow into the
            // row — which is the only band where the row's own pointerInput
            // still pages back. We need to avoid the app icons because their
            // own combinedClickable would consume the tap first, and we
            // need to stay under ~37 px so the new narrower heuristic still
            // fires. Past the heuristic the tap is on an app icon (or
            // gap between them), not on the chevron, and the row leaves it
            // alone — see `recentsOverflow_tapPastChevronOverhangDoesNotPageRow`
            // for the inverse coverage.
            val position = Offset(x = 33f, y = height / 2f)
            down(position)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
        val oldestAfter = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()
        assertTrue(oldestAfter.left < oldestBefore.left)
    }

    // Under RTL the start chevron renders at the *physical right*
    // (Alignment.CenterStart is logical), but pointer coordinates are
    // physical — the in-row tap band used to compare raw offset.x against
    // the logical chevron flags, so the band by the visible start chevron
    // did nothing and the band on the opposite edge paged the wrong way.
    @Test
    fun recentsOverflow_rtlStartChevronBandAtPhysicalRightPagesRow() {
        val recentApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(
            LauncherUiState(
                filteredApps = emptyList(),
                recentApps = recentApps,
                isRecentsOpen = true,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
            ),
            layoutDirection = LayoutDirection.Rtl,
        )

        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput {
            // The mirror image of the LTR x = 33 band tap in
            // recentsOverflow_startChevronTapPagesRowWithCompactTarget: the
            // start chevron's overhang band sits at the physical right edge
            // under RTL.
            val position = Offset(x = width - 33f, y = height / 2f)
            down(position)
            up()
        }
        composeRule.waitForIdle()

        // Paging back from the pinned end reveals newer entries, so the end
        // chevron appears.
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
    }

    // A tap inside the row but past the chevron icon's visual overhang lands
    // on an app icon — the row's first-N-dp page-back heuristic must NOT
    // fire there, otherwise the user can't actually launch the leftmost
    // visible app when the recents row has overflowed and is pinned to its
    // end.
    @Test
    fun recentsOverflow_tapPastChevronOverhangDoesNotPageRow() {
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
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
        val oldestBefore = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).performTouchInput {
            // x = 80 is well past the chevron icon's 14 dp (~37 px) overhang
            // and lands on the leftmost partially-visible app icon. The
            // row's heuristic must leave this tap alone so the app receives
            // its own click.
            val position = Offset(x = 80f, y = height / 2f)
            down(position)
            up()
        }
        composeRule.waitForIdle()

        // No page-back happened, so the rightmost app stays at the same
        // bounds and the end chevron is still hidden.
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
        val oldestAfter = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:App12").getBoundsInRoot()
        assertEquals(oldestBefore.left, oldestAfter.left)
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
        // Widgets page change once it crosses the 96 dp distance / 500 dp/s
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
