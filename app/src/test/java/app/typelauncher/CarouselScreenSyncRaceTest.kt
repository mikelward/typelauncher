package app.typelauncher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    @Test
    fun secondSwipeBeforeWidgetsAckDoesNotAdvancePastWidgets() {
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

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(
            "A second swipe before Widgets is acknowledged must not advance to Agenda",
            startPage + 1,
            carousel.carouselVirtualPage(),
        )

        holdWidgetsAck = false
        state = state.copy(screen = heldWidgetsAck!!)
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(
            "Acknowledging Widgets must keep the pager on Widgets",
            startPage + 1,
            carousel.carouselVirtualPage(),
        )
    }

    @Test
    fun secondSwipeBeforeAgendaAckDoesNotAdvanceToHome() {
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

        assertEquals(
            "A second swipe before Agenda is acknowledged must not advance to Home",
            widgetsPage + 1,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Widgets, state.screen)

        state = state.copy(screen = heldAgendaAck!!)
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, state.screen)
        assertEquals(widgetsPage + 1, carousel.carouselVirtualPage())
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
    fun userSwipeDuringExternalAnimationDoesNotChangeExternalTarget() {
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
            "A user swipe during external Home -> Widgets animation must not continue to Agenda",
            homePage + 1,
            carousel.carouselVirtualPage(),
        )
        assertEquals(LauncherScreen.Widgets, state.screen)
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

    private fun SemanticsNodeInteraction.carouselVirtualPage(): Int =
        fetchSemanticsNode().config[CarouselVirtualPageKey]
}
