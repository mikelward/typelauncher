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
    fun secondSwipeBeforeWidgetsAckQueuesAndAdvancesToNextWidgetPageAfterAck() {
        // Two widget pages keep the carousel at three visible pages so the
        // queued swipe has a distinct next page to replay onto.
        var state by mutableStateOf(LauncherUiState(widgetPages = listOf(emptyList(), emptyList())))
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
                    onShowWidgets = { pageIndex ->
                        if (!holdWidgetsAck) {
                            state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
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
            "Once Widgets is acknowledged, the queued swipe replays one page to the next widget page",
            startPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Widgets(1), state.destination)
    }

    @Test
    fun secondSwipeBeforeSecondWidgetPageAckQueuesAndWrapsToHomeAfterAck() {
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                widgetPages = listOf(emptyList(), emptyList()),
            ),
        )
        var heldWidgetPageAck: Int? = null
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
                    onShowWidgets = { pageIndex ->
                        if (pageIndex == 1) {
                            heldWidgetPageAck = pageIndex
                        } else {
                            state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
                        }
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
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
        assertEquals(1, heldWidgetPageAck)

        carousel.performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        // While the second widget page is still pending ack, the queued swipe
        // has not yet replayed — the pager is still parked on its virtual page.
        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherDestination.Widgets(0), state.destination)

        state = state.copy(destination = LauncherDestination.Widgets(1))
        composeRule.waitForIdle()

        assertEquals(
            "Once the second widget page is acknowledged, the queued swipe replays one page and wraps to the next Home",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Home, state.destination)
    }

    @Test
    fun ackTimeoutUnlocksSwipeFromSettledPage() {
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                widgetPages = listOf(emptyList(), emptyList()),
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
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    // Simulate a broken callback path: the pager asks for the
                    // second widget page, but the model never acknowledges it.
                    onShowWidgets = { pageIndex ->
                        if (pageIndex == 0) {
                            state = state.copy(destination = LauncherDestination.Widgets(0))
                        }
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
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
            "After ack timeout, swipes should resume from the settled page",
            widgetsPage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Home, state.destination)
    }

    @Test
    fun userSwipeDuringExternalAnimationQueuesAndAdvancesPastTarget() {
        var state by mutableStateOf(LauncherUiState(widgetPages = listOf(emptyList(), emptyList())))
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
            "A user swipe during external Home -> Widgets settle queues and replays one page to the next widget page",
            homePage + 2,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherDestination.Widgets(1), state.destination)
    }

    @Test
    fun staleHomeUpdateBeforeSecondWidgetPageAckDoesNotSnapAway() {
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                widgetPages = listOf(emptyList(), emptyList()),
            ),
        )
        var heldWidgetPageAck: Int? = null
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
                    onShowWidgets = { pageIndex ->
                        if (pageIndex == 1) {
                            heldWidgetPageAck = pageIndex
                        } else {
                            state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
                        }
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
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
        assertEquals(1, heldWidgetPageAck)

        state = state.copy(destination = LauncherDestination.Home)
        composeRule.waitForIdle()

        assertEquals(
            "A stale Home update while the second widget page is pending must not animate past it",
            widgetsPage + 1,
            carousel.carouselVirtualPage(),
        )

        state = state.copy(destination = LauncherDestination.Widgets(1))
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Widgets(1), state.destination)
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
        var state by mutableStateOf(LauncherUiState(widgetPages = listOf(emptyList(), emptyList())))
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
                    onShowWidgets = { pageIndex ->
                        if (!holdWidgetsAck) {
                            state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
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
        // commit to the next page (the second widget page) since the
        // carousel reached Idle before this drag arrived.
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
        assertEquals(LauncherDestination.Widgets(1), state.destination)
    }

    @Test
    fun dockSwipeCompletedDuringExternalHomeSettleCommitsAfterHomeSettles() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        // Two widget pages so a queued swipe that wrongly continued past
        // Widgets(0) would land on Widgets(1) and fail the assertions below.
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                filteredApps = emptyList(),
                dockedApps = docked,
                widgetPages = listOf(emptyList(), emptyList()),
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
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
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
        // Two widget pages so a queued swipe that wrongly continued past
        // Widgets(0) would land on Widgets(1) and fail the assertions below.
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                filteredApps = emptyList(),
                dockedApps = docked,
                widgetPages = listOf(emptyList(), emptyList()),
            ),
        )
        var holdHomeAck = true
        var heldHomeAck: LauncherScreen? = null
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
                    onShowWidgets = { pageIndex ->
                        state = state.copy(destination = LauncherDestination.Widgets(pageIndex))
                    },
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
                    onAppListIconOnlyChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
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

    private fun SemanticsNodeInteraction.carouselVirtualPage(): Int =
        fetchSemanticsNode().config[CarouselVirtualPageKey]
}
