package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class CarouselScreenSyncRaceTest {
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
    fun secondSwipeBeforeWidgetsAckQueuesAndAdvancesToAgendaAfterAck() {
        var state by mutableStateOf(LauncherUiState())
        var holdWidgetsAck = true
        var heldWidgetsAck: LauncherScreen? = null
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
                    onShowAgenda = { state = state.copy(screen = LauncherScreen.Agenda) },
                    onShowWidgets = {
                        if (!holdWidgetsAck) {
                            state = state.copy(screen = LauncherScreen.Widgets)
                        } else {
                            heldWidgetsAck = LauncherScreen.Widgets
                        }
                    },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val startPage = carousel.carouselVirtualPage()

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(startPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(LauncherScreen.Widgets, heldWidgetsAck)

        composeRule.mainClock.autoAdvance = false
        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        // Pager has not moved past Widgets yet — the second swipe is queued
        // against the Widgets settle target, not consumed in flight.
        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(startPage + 1, carousel.carouselVirtualPage())

        holdWidgetsAck = false
        state = state.copy(screen = heldWidgetsAck!!)
        composeRule.waitForIdle()

        assertEquals(
            "Once Widgets is acknowledged, the queued swipe replays one page from Widgets to Agenda",
            startPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Agenda, state.screen)
    }

    @Test
    fun secondSwipeBeforeAgendaAckQueuesAndWrapsToHomeAfterAck() {
        var state by mutableStateOf(LauncherUiState(screen = LauncherScreen.Widgets))
        var heldAgendaAck: LauncherScreen? = null
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
                    onShowAgenda = { heldAgendaAck = LauncherScreen.Agenda },
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val widgetsPage = carousel.carouselVirtualPage()

        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(LauncherScreen.Agenda, heldAgendaAck)

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // While Agenda is still pending ack, the queued swipe has not yet
        // replayed — the pager is still parked on the Agenda virtual page.
        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, state.screen)

        state = state.copy(screen = heldAgendaAck!!)
        composeRule.waitForIdle()

        assertEquals(
            "Once Agenda is acknowledged, the queued swipe replays one page from Agenda and wraps to the next Home",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Home, state.screen)
    }

    @Test
    fun ackTimeoutUnlocksSwipeFromSettledPage() {
        var state by mutableStateOf(LauncherUiState(screen = LauncherScreen.Widgets))
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
                    // Simulate a broken callback path: the pager asks for Agenda,
                    // but the model never acknowledges it.
                    onShowAgenda = {},
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val widgetsPage = carousel.carouselVirtualPage()

        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, state.screen)

        composeRule.mainClock.advanceTimeBy(1_600)
        composeRule.waitForIdle()

        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(
            "After ack timeout, swipes should resume from the settled Agenda page",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Home, state.screen)
    }

    @Test
    fun userSwipeDuringExternalAnimationQueuesAndAdvancesPastTarget() {
        var state by mutableStateOf(LauncherUiState())
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
                    onShowAgenda = { state = state.copy(screen = LauncherScreen.Agenda) },
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val homePage = carousel.carouselVirtualPage()

        composeRule.mainClock.autoAdvance = false
        state = state.copy(screen = LauncherScreen.Widgets)
        composeRule.mainClock.advanceTimeByFrame()

        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "A user swipe during external Home -> Widgets settle queues and replays one page from Widgets to Agenda",
            homePage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Agenda, state.screen)
    }

    @Test
    fun staleHomeUpdateBeforeAgendaAckDoesNotSnapAwayFromAgenda() {
        var state by mutableStateOf(LauncherUiState(screen = LauncherScreen.Widgets))
        var heldAgendaAck: LauncherScreen? = null
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
                    onShowAgenda = { heldAgendaAck = LauncherScreen.Agenda },
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val widgetsPage = carousel.carouselVirtualPage()

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(LauncherScreen.Agenda, heldAgendaAck)

        state = state.copy(screen = LauncherScreen.Home)
        composeRule.waitForIdle()

        assertEquals(
            "A stale Home update while Agenda is pending must not animate past Agenda",
            widgetsPage + 1,
            carousel.carouselVirtualPage(),
        )

        state = state.copy(screen = heldAgendaAck!!)
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, state.screen)
        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
    }

    @Test
    fun swipeStartingDuringAckClaimsOncePageSettles() {
        // Regression: a finger that touches down while the carousel is still
        // settling from a prior swipe used to be ignored for the rest of that
        // gesture, even after the page reached Idle mid-gesture. Subsequent
        // moves were silently dropped because the claim check captured a
        // false `canStartCarouselGesture` at first-down and never
        // re-evaluated. Reported as: "scrolling sideways on the dock usually
        // doesn't scroll if I start scrolling before the home page settles —
        // it ignores subsequent scrolls even after the page settles."
        var state by mutableStateOf(LauncherUiState())
        var holdWidgetsAck = true
        var heldWidgetsAck: LauncherScreen? = null
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
                    onShowAgenda = { state = state.copy(screen = LauncherScreen.Agenda) },
                    onShowWidgets = {
                        if (!holdWidgetsAck) {
                            state = state.copy(screen = LauncherScreen.Widgets)
                        } else {
                            heldWidgetsAck = LauncherScreen.Widgets
                        }
                    },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val homePage = carousel.carouselVirtualPage()

        // First swipe: Home -> Widgets, with the parent withholding the ack
        // so the carousel parks in AwaitingAck.
        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(homePage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(LauncherScreen.Widgets, heldWidgetsAck)

        // Touch down while AwaitingAck — the previous behaviour latched
        // `canStartCarouselGesture = false` for the whole gesture here.
        carousel.performTouchInput { down(center) }

        // Release the ack mid-gesture so the carousel transitions to Idle
        // before the user's drag and release events arrive.
        holdWidgetsAck = false
        state = state.copy(screen = heldWidgetsAck!!)
        composeRule.waitForIdle()
        assertEquals(LauncherScreen.Widgets, state.screen)

        // Now drag past the 96 dp commit threshold (~252 px at this
        // qualifier) and release. The continuation of the swipe should
        // commit to the next page (Agenda) since the carousel reached Idle
        // before this drag arrived.
        carousel.performTouchInput {
            moveBy(Offset(-700f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(
            "A swipe started during ack must commit once the carousel reaches Idle mid-gesture",
            homePage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Agenda, state.screen)
    }

    @Test
    fun dockSwipeCompletedDuringExternalHomeSettleCommitsAfterHomeSettles() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var state by mutableStateOf(
            LauncherUiState(
                screen = LauncherScreen.Widgets,
                filteredApps = emptyList(),
                dockedApps = docked,
            ),
        )
        var showAgendaCount = 0
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
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(screen = LauncherScreen.Agenda)
                    },
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = { state = state.copy(screen = LauncherScreen.Home) },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val widgetsPage = carousel.carouselVirtualPage()

        composeRule.mainClock.autoAdvance = false
        state = state.copy(screen = LauncherScreen.Home)
        composeRule.mainClock.advanceTimeByFrame()

        val dockApp = composeRule.onNodeWithTag("$DOCK_APP_TAG:App01")
        dockApp.performTouchInput {
            down(center)
            moveBy(Offset(-700f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "A dock swipe completed during Home settle must advance exactly one page from Home",
            widgetsPage,
            carousel.carouselVirtualPage(),
        )
        assertEquals("queued Home swipe must not continue past Widgets to Agenda", 0, showAgendaCount)
        assertEquals(LauncherScreen.Widgets, state.screen)
    }

    @Test
    fun dockSwipeCompletedDuringHomeAckCommitsOnceHomeAckArrives() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var state by mutableStateOf(
            LauncherUiState(
                screen = LauncherScreen.Widgets,
                filteredApps = emptyList(),
                dockedApps = docked,
            ),
        )
        var holdHomeAck = true
        var heldHomeAck: LauncherScreen? = null
        var showAgendaCount = 0
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
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(screen = LauncherScreen.Agenda)
                    },
                    onShowWidgets = { state = state.copy(screen = LauncherScreen.Widgets) },
                    onShowHome = {
                        if (!holdHomeAck) {
                            state = state.copy(screen = LauncherScreen.Home)
                        } else {
                            heldHomeAck = LauncherScreen.Home
                        }
                    },
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
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val widgetsPage = carousel.carouselVirtualPage()

        carousel.performTouchInput {
            down(center)
            moveBy(Offset(700f, 0f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals(widgetsPage - 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(LauncherScreen.Home, heldHomeAck)

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(center)
            moveBy(Offset(-700f, 0f))
            up()
        }

        holdHomeAck = false
        state = state.copy(screen = heldHomeAck!!)
        composeRule.waitForIdle()

        assertEquals(
            "A dock swipe completed while Home ack is pending must advance exactly one page from Home",
            widgetsPage,
            carousel.carouselVirtualPage(),
        )
        assertEquals("queued Home swipe must not continue past Widgets to Agenda", 0, showAgendaCount)
        assertEquals(LauncherScreen.Widgets, state.screen)
    }

    private fun SemanticsNodeInteraction.carouselVirtualPage(): Int =
        fetchSemanticsNode().config[CarouselVirtualPageKey]
}
