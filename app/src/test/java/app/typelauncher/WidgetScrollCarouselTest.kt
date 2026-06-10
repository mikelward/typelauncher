package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression: scrolling a hosted widget used to bubble up to the launcher's
 * carousel. The widget's RemoteViews-backed scrollable consumes touches at
 * the View layer (via `requestDisallowInterceptTouchEvent(true)`), but the
 * carousel's `pointerInput` reads raw deltas via
 * `positionChangeIgnoreConsumed` and cannot see View-level consumption. The
 * symptoms were two-fold: a vertical drag inside the widget expanded the
 * system notification shade, and a horizontal drag inside a
 * horizontally-scrolling widget paged the carousel.
 *
 * The fix follows the dock's `isDockDraggingState` template: the launcher's
 * `LauncherAppWidgetHostView` forwards `requestDisallowInterceptTouchEvent`
 * to `LauncherAppWidgetHost.onChildScrollChange`, which the carousel
 * mirrors into a Compose state read by both pointer-input loops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class WidgetScrollCarouselTest {
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

    private class Scenario {
        var swipeDownCount = 0
        var showAgendaCount = 0
        var showWidgetsCount = 0
        var showHomeCount = 0
    }

    private fun runScenario(
        initialDestination: LauncherDestination,
        simulateWidgetClaim: Boolean,
        slopOffset: Offset,
        commitOffset: Offset,
    ): Scenario {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val host = LauncherAppWidgetHost(context, hostId = 0)
        val scenario = Scenario()
        var state by mutableStateOf(
            LauncherUiState(
                destination = initialDestination,
                filteredApps = emptyList(),
                dockedApps = emptyList(),
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
                    onShowAgenda = {
                        scenario.showAgendaCount += 1
                        state = state.copy(destination = LauncherDestination.Agenda)
                    },
                    onShowWidgets = {
                        scenario.showWidgetsCount += 1
                        state = state.copy(destination = LauncherDestination.Widgets())
                    },
                    onShowHome = {
                        scenario.showHomeCount += 1
                        state = state.copy(destination = LauncherDestination.Home)
                    },
                    appWidgetHost = host,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                    onSwipeDown = { scenario.swipeDownCount += 1 },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            // Cross touch slop in the test's dominant axis so the
            // carousel's owner-resolution picks the right pull (vertical
            // for the pull-down-shade test, horizontal for the page
            // carousel test) before the widget claim flips.
            moveBy(slopOffset)
            if (simulateWidgetClaim) {
                // Simulate a scrollable widget descendant calling
                // requestDisallowInterceptTouchEvent(true) mid-gesture.
                // The composition's DisposableEffect installs a listener
                // that mirrors this into the carousel's
                // isWidgetScrollingState.
                host.onChildScrollChange?.invoke(true)
            }
            // Travel well past the 96 dp launcher commit threshold (~252
            // px at 420 dpi) and the half-page horizontal threshold so
            // without the suppression the carousel would unambiguously
            // dispatch.
            moveBy(commitOffset)
            up()
        }
        composeRule.waitForIdle()
        return scenario
    }

    @Test
    fun verticalDragWithWidgetClaim_doesNotExpandShade_onWidgetsScreen() {
        val scenario = runScenario(
            initialDestination = LauncherDestination.Widgets(),
            simulateWidgetClaim = true,
            slopOffset = Offset(0f, 30f),
            commitOffset = Offset(0f, 400f),
        )
        assertEquals(
            "widget-claimed vertical drag must not expand the system notification shade",
            0,
            scenario.swipeDownCount,
        )
    }

    @Test
    fun verticalDragWithoutWidgetClaim_expandsShade_onWidgetsScreen() {
        // Sanity check: without the widget claim, the same vertical drag
        // commits as a launcher pull-down. Pinning this side of the
        // behaviour guards against the suppression accidentally becoming
        // a permanent disable.
        val scenario = runScenario(
            initialDestination = LauncherDestination.Widgets(),
            simulateWidgetClaim = false,
            slopOffset = Offset(0f, 30f),
            commitOffset = Offset(0f, 400f),
        )
        assertEquals(
            "without widget claim, vertical drag on Widgets dispatches swipeDown",
            1,
            scenario.swipeDownCount,
        )
    }

    @Test
    fun horizontalDragWithWidgetClaim_doesNotPageCarousel() {
        // Horizontal scroll inside a widget (e.g. a swipeable gallery)
        // must not page Home → Widgets/Agenda. The slop crossing is
        // horizontal, so the carousel resolves owner = HorizontalLauncher
        // *and arms `carouselClaimed = true` on the same event* — the
        // widget's disallow signal arrives between this and the next
        // event, after the claim. The fix has to cancel the post-claim
        // commit, not just the unclaimed-fallback dispatch, or the page
        // change still fires on release. This is the
        // claim-before-widget-signal race Codex caught on the first push.
        val scenario = runScenario(
            initialDestination = LauncherDestination.Home,
            simulateWidgetClaim = true,
            slopOffset = Offset(-30f, 0f),
            commitOffset = Offset(-700f, 0f),
        )
        assertEquals("widget-claimed horizontal drag must not page Widgets", 0, scenario.showWidgetsCount)
        assertEquals("widget-claimed horizontal drag must not page Agenda", 0, scenario.showAgendaCount)
    }

    @Test
    fun horizontalDragWithoutWidgetClaim_pagesCarousel() {
        // Sanity check on the horizontal pull, mirroring the vertical
        // sanity case: without the widget claim the same drag pages the
        // carousel, so the suppression above isn't a permanent disable.
        val scenario = runScenario(
            initialDestination = LauncherDestination.Home,
            simulateWidgetClaim = false,
            slopOffset = Offset(-30f, 0f),
            commitOffset = Offset(-700f, 0f),
        )
        assertEquals(
            "without widget claim, horizontal drag on Home pages off Home",
            1,
            scenario.showWidgetsCount + scenario.showAgendaCount,
        )
    }
}
