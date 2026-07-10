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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = {
                        if (!holdWidgetsAck) {
                            state = state.copy(destination = LauncherDestination.Widgets())
                        } else {
                            heldWidgetsAck = LauncherScreen.Widgets
                        }
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        assertEquals(LauncherDestination.Home, state.destination)
        assertEquals(LauncherScreen.Widgets, heldWidgetsAck)

        composeRule.mainClock.autoAdvance = false
        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        // Pager has not moved past Widgets yet — the second swipe is queued
        // against the Widgets settle target, not consumed in flight.
        assertEquals(LauncherDestination.Home, state.destination)
        assertEquals(startPage + 1, carousel.carouselVirtualPage())

        holdWidgetsAck = false
        state = state.copy(destination = LauncherDestination.Widgets())
        composeRule.waitForIdle()

        assertEquals(
            "Once Widgets is acknowledged, the queued swipe replays one page from Widgets to Agenda",
            startPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Agenda, state.destination)
    }

    @Test
    fun secondSwipeBeforeAgendaAckQueuesAndWrapsToHomeAfterAck() {
        var state by mutableStateOf(LauncherUiState(destination = LauncherDestination.Widgets()))
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { heldAgendaAck = LauncherScreen.Agenda },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        assertEquals(LauncherDestination.Widgets(0), state.destination)
        assertEquals(LauncherScreen.Agenda, heldAgendaAck)

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // While Agenda is still pending ack, the queued swipe has not yet
        // replayed — the pager is still parked on the Agenda virtual page.
        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherDestination.Widgets(0), state.destination)

        state = state.copy(destination = LauncherDestination.Agenda)
        composeRule.waitForIdle()

        assertEquals(
            "Once Agenda is acknowledged, the queued swipe replays one page from Agenda and wraps to the next Home",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Home, state.destination)
    }

    @Test
    fun ackTimeoutUnlocksSwipeFromSettledPage() {
        var state by mutableStateOf(LauncherUiState(destination = LauncherDestination.Widgets()))
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    // Simulate a broken callback path: the pager asks for Agenda,
                    // but the model never acknowledges it.
                    onShowAgenda = {},
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        assertEquals(LauncherDestination.Widgets(0), state.destination)

        composeRule.mainClock.advanceTimeBy(1_600)
        composeRule.waitForIdle()

        carousel.performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(
            "After ack timeout, swipes should resume from the settled Agenda page",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Home, state.destination)
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        state = state.copy(destination = LauncherDestination.Widgets())
        composeRule.mainClock.advanceTimeByFrame()

        // Drive the swipe with explicit down/moveBy/up so the gesture
        // commits while the ExternalAnimating(Widgets) coroutine is still
        // running — `swipeLeft(durationMillis = 1)` has been observed not
        // to commit reliably with autoAdvance disabled.
        carousel.performTouchInput {
            down(center)
            moveBy(Offset(-700f, 0f))
            up()
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "A user swipe during external Home -> Widgets settle queues and replays one page from Widgets to Agenda",
            homePage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Agenda, state.destination)
    }

    @Test
    fun staleHomeUpdateBeforeAgendaAckDoesNotSnapAwayFromAgenda() {
        var state by mutableStateOf(LauncherUiState(destination = LauncherDestination.Widgets()))
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { heldAgendaAck = LauncherScreen.Agenda },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        assertEquals(LauncherDestination.Widgets(0), state.destination)
        assertEquals(LauncherScreen.Agenda, heldAgendaAck)

        state = state.copy(destination = LauncherDestination.Home)
        composeRule.waitForIdle()

        assertEquals(
            "A stale Home update while Agenda is pending must not animate past Agenda",
            widgetsPage + 1,
            carousel.carouselVirtualPage(),
        )

        state = state.copy(destination = LauncherDestination.Agenda)
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Agenda, state.destination)
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = {
                        if (!holdWidgetsAck) {
                            state = state.copy(destination = LauncherDestination.Widgets())
                        } else {
                            heldWidgetsAck = LauncherScreen.Widgets
                        }
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        assertEquals(LauncherDestination.Home, state.destination)
        assertEquals(LauncherScreen.Widgets, heldWidgetsAck)

        // Touch down while AwaitingAck — the previous behaviour latched
        // `canStartCarouselGesture = false` for the whole gesture here.
        carousel.performTouchInput { down(center) }

        // Release the ack mid-gesture so the carousel transitions to Idle
        // before the user's drag and release events arrive.
        holdWidgetsAck = false
        state = state.copy(destination = LauncherDestination.Widgets())
        composeRule.waitForIdle()
        assertEquals(LauncherDestination.Widgets(0), state.destination)

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
        assertEquals(LauncherDestination.Agenda, state.destination)
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
                destination = LauncherDestination.Widgets(),
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(destination = LauncherDestination.Agenda)
                    },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        state = state.copy(destination = LauncherDestination.Home)
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
        assertEquals(LauncherDestination.Widgets(0), state.destination)
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
                destination = LauncherDestination.Widgets(),
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(destination = LauncherDestination.Agenda)
                    },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = {
                        if (!holdHomeAck) {
                            state = state.copy(destination = LauncherDestination.Home)
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
        assertEquals(LauncherDestination.Widgets(0), state.destination)
        assertEquals(LauncherScreen.Home, heldHomeAck)

        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(center)
            moveBy(Offset(-700f, 0f))
            up()
        }

        holdHomeAck = false
        state = state.copy(destination = LauncherDestination.Home)
        composeRule.waitForIdle()

        assertEquals(
            "A dock swipe completed while Home ack is pending must advance exactly one page from Home",
            widgetsPage,
            carousel.carouselVirtualPage(),
        )
        assertEquals("queued Home swipe must not continue past Widgets to Agenda", 0, showAgendaCount)
        assertEquals(LauncherDestination.Widgets(0), state.destination)
    }

    @Test
    fun swipeAfterReturnToHomeWithStaleLastWidgetPageStillCommits() {
        // Regression: a non-zero `lastWidgetPage` (preserved when the user
        // navigates back to Home from Widgets[1] so `showWidgets()` can
        // restore the page) must not leak into the carousel claim check.
        // Pre-LauncherDestination, state stored screen + currentWidgetPage as
        // independent fields, so on Home with currentWidgetPage=1 the claim
        // check compared LauncherPage(Home, 1) against LauncherPage(Home, 0)
        // and every horizontal swipe was silently dropped after that point.
        // Now destination=Home builds LauncherPage(Home, 0) by construction
        // — the stale index lives in lastWidgetPage instead.
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Home,
                lastWidgetPage = 1,
                widgetPages = listOf(emptyList(), emptyList()),
                isAgendaEnabled = false,
            ),
        )
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = { pageIndex ->
                        state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(
            "Stale lastWidgetPage on Home must not block the claim check",
            homePage + 1,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Widgets(0), state.destination)
    }

    @Test
    fun navigationLandingDuringSnapBackStillSyncsTheCarousel() {
        // Regression: a destination change that lands while a *non-committed*
        // release is snapping back (transition stays Idle, but the animation
        // job is running and the offset is mid-flight) used to be dropped
        // forever — the sync effect bailed on the in-flight animation and
        // nothing re-ran it once the snap-back finished, leaving the carousel
        // on Widgets while state said Home. From there the claim gate's
        // currentLauncherPage == candidatePage check refused every swipe.
        // Reachable by pressing the home button inside the ~200 ms snap-back
        // window after an under-threshold drag.
        var state by mutableStateOf(LauncherUiState(destination = LauncherDestination.Widgets()))
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        // An under-threshold drag: ~210 px is well below the pageWidth/2
        // commit distance at this qualifier, and the trailing crawl decays
        // the release velocity below the fling bar, so the release snaps
        // back rather than committing.
        carousel.performTouchInput {
            down(center)
            moveBy(Offset(-200f, 0f))
            repeat(10) { moveBy(Offset(-1f, 0f)) }
            up()
        }
        composeRule.mainClock.advanceTimeByFrame()
        // The home press lands while the snap-back is still animating.
        state = state.copy(destination = LauncherDestination.Home)
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "A Home navigation landing during snap-back must sync the carousel once the snap-back ends",
            widgetsPage - 1,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Home, state.destination)
    }

    @Test
    fun navigationLandingDuringExternalSettleStillSyncsTheCarousel() {
        // Same dropped-window regression, external-animation flavor: a second
        // external destination change arriving while the first is still
        // animating used to be swallowed by the effect's ExternalAnimating
        // bail, with nothing re-running the sync when the settle finished.
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = { state = state.copy(destination = LauncherDestination.Agenda) },
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets()) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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
        state = state.copy(destination = LauncherDestination.Widgets())
        composeRule.mainClock.advanceTimeByFrame()
        // A second navigation lands while the Home -> Widgets settle is
        // still animating.
        state = state.copy(destination = LauncherDestination.Agenda)
        composeRule.mainClock.advanceTimeBy(2_000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        assertEquals(
            "An Agenda navigation landing during the Widgets settle must sync once the settle ends",
            homePage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Agenda, state.destination)
    }

    private fun SemanticsNodeInteraction.carouselVirtualPage(): Int =
        fetchSemanticsNode().config[CarouselVirtualPageKey]
}
