package app.typelauncher

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression test for the carousel's page slots having positional identity.
 * The three `[-1, 0, +1]` page Boxes used to match positionally across
 * recompositions, so when `currentPage` advanced at settle the slot contents
 * shifted by one and Compose rebuilt every page from scratch — the widgets
 * page the user just swiped to (already composed and its `AppWidgetHostView`s
 * inflated in the neighbor slot during the drag, exactly what the
 * `widgetsWarmed` design exists for) was discarded and re-created in the
 * center slot, re-paying host-view inflation on every settle. `key(page)`
 * gives each virtual page a movable identity so its subtree slides between
 * slots instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class CarouselPageKeyingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearPersistedCache() {
        context.getSharedPreferences("widget_size_cache", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun settlingOntoWidgetsKeepsTheHostViewInflatedDuringTheDrag() {
        val widgetId = 42
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
            minWidth = 200
            minHeight = 200
        }
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).addBoundWidget(widgetId, providerInfo)
        val host = LauncherAppWidgetHost(context, hostId = 0)

        // Two widget pages keep the carousel at three visible pages
        // (Home, Widgets(0), Widgets(1)) so the -1/+1 slots never alias and
        // the settle genuinely moves the widgets subtree between slots.
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                widgetPages = listOf(listOf(widgetId), emptyList()),
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
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets(it)) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
                    appWidgetHost = host,
                    appWidgetManager = manager,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(
            "widget host views must not inflate before the carousel warms widgets",
            emptyList<AppWidgetHostView>(),
            findHostViews(),
        )

        // First half of a Home -> Widgets swipe: cross slop and travel past
        // the half-page commit threshold, but do not release yet. The
        // carousel claim flips widgetsWarmed, so the widgets page composes
        // (and inflates its host view) in the neighbor slot mid-drag.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput {
            down(Offset(width * 0.85f, height / 2f))
            moveBy(Offset(-30f, 0f))
            moveBy(Offset(-width * 0.7f, 0f))
        }
        composeRule.waitForIdle()
        val inflatedMidDrag = findHostViews().single()

        // Release: the carousel commits and settles onto the widgets page.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { up() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$widgetId").assertIsDisplayed()
        assertSame(
            "settling must reuse the host view inflated during the drag, not re-create it",
            inflatedMidDrag,
            findHostViews().single(),
        )
    }

    @Test
    fun twoPageCarouselComposesEachDestinationExactlyOnce() {
        // With a single widget page, the visible carousel
        // is [Home, Widgets(0)] — floorMod aliases the -1 and +1 slots to the
        // SAME destination. Composing it in both slots created two live
        // copies: two AppWidgetHostViews per widget (the platform only
        // delivers updates to the most recently created one, so one
        // wraparound direction showed frozen widgets) and two HomeScreens
        // running duplicate keyboard-focus effects.
        val widgetId = 42
        val providerInfo = AppWidgetProviderInfo().apply {
            provider = ComponentName("app.typelauncher.fakewidget", "FakeProvider")
            minWidth = 200
            minHeight = 200
        }
        val manager = AppWidgetManager.getInstance(context)
        shadowOf(manager).addBoundWidget(widgetId, providerInfo)
        val host = LauncherAppWidgetHost(context, hostId = 0)

        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                widgetPages = listOf(listOf(widgetId)),
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
                    onShowWidgets = { state = state.copy(destination = LauncherDestination.Widgets(it)) },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
                    appWidgetHost = host,
                    appWidgetManager = manager,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Swipe Home -> Widgets and settle. Widgets is now the center slot;
        // both neighbor slots alias to Home.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput {
            down(Offset(width * 0.85f, height / 2f))
            moveBy(Offset(-30f, 0f))
            moveBy(Offset(-width * 0.7f, 0f))
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$widgetId").assertIsDisplayed()
        assertEquals(
            "Home must compose exactly once while both neighbor slots alias to it",
            1,
            composeRule.onAllNodesWithTag(SEARCH_FIELD_TAG).fetchSemanticsNodes().size,
        )
        assertEquals(
            "the widget must own exactly one host view",
            1,
            findHostViews().size,
        )

        // Swipe back to Home: now both neighbor slots alias to Widgets, which
        // must still be a single composition with a single host view.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput {
            down(Offset(width * 0.15f, height / 2f))
            moveBy(Offset(30f, 0f))
            moveBy(Offset(width * 0.7f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals(
            "the aliased widgets neighbor must compose exactly once",
            1,
            findHostViews().size,
        )
        assertEquals(
            1,
            composeRule.onAllNodesWithTag(SEARCH_FIELD_TAG).fetchSemanticsNodes().size,
        )
    }

    private fun findHostViews(): List<AppWidgetHostView> {
        val root = composeRule.activity.findViewById<ViewGroup>(android.R.id.content)
        val found = mutableListOf<AppWidgetHostView>()
        fun walk(view: View) {
            if (view is AppWidgetHostView) {
                found += view
                return
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    walk(view.getChildAt(index))
                }
            }
        }
        walk(root)
        return found
    }
}
