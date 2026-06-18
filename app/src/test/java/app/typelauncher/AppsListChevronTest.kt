package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home apps list mirrors the dock and recents-bar overflow
 * treatment: a chevron is overlaid on the bottom edge while more rows are
 * off-screen below, and on the top edge once the user has scrolled past the
 * first row. Visibility is driven by `LazyListState.canScrollForward` /
 * `canScrollBackward`, so a list that fits the viewport shows neither
 * chevron once the fresh app load is complete and the lazy viewport has been
 * measured.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class AppsListChevronTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(name: String, packageName: String = "app.typelauncher.fake.$name"): InstalledApp =
        InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    private fun renderHome(
        state: LauncherUiState,
        markFreshLoadComplete: Boolean = true,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = if (markFreshLoadComplete) state.copy(isFreshAppLoadComplete = true) else state,
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

    private fun renderMutableHome(initialState: LauncherUiState): androidx.compose.runtime.MutableState<LauncherUiState> {
        val stateHolder = mutableStateOf(initialState)
        composeRule.setContent {
            val state by stateHolder
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
        return stateHolder
    }

    @Test
    fun appsListOverflow_textRows_showsBottomChevronAndHidesTopAtRest() {
        // 60 entries comfortably exceed the apps-card viewport on a 411 × 914 dp
        // screen; the list starts scrolled to the top so only the bottom-edge
        // chevron should be visible.
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_warmCacheHidesChevronUntilFreshLoadCompletes() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val stateHolder = renderMutableHome(
            LauncherUiState(
                filteredApps = apps,
                appListLayout = AppListLayout.NameBeside,
                isLoadingApps = false,
                isFreshAppLoadComplete = false,
            ),
        )

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()

        stateHolder.value = stateHolder.value.copy(isFreshAppLoadComplete = true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_shrinkingAppSetHidesChevronAfterFreshLoad() {
        val cachedApps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val freshApps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        val stateHolder = renderMutableHome(
            LauncherUiState(
                filteredApps = cachedApps,
                appListLayout = AppListLayout.NameBeside,
                isLoadingApps = false,
                isFreshAppLoadComplete = true,
            ),
        )
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()

        stateHolder.value = stateHolder.value.copy(filteredApps = freshApps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_coldLoadHidesChevronUntilAppsPublish() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val stateHolder = renderMutableHome(
            LauncherUiState(
                filteredApps = emptyList(),
                appListLayout = AppListLayout.NameBeside,
                isLoadingApps = true,
                isFreshAppLoadComplete = false,
            ),
        )

        composeRule.onNodeWithTag(APPS_LOADING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()

        stateHolder.value = stateHolder.value.copy(
            filteredApps = apps,
            isLoadingApps = false,
            isFreshAppLoadComplete = true,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_placesBottomChevronAtOuterCardEdge() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val appsListBounds = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val chevronBounds = composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).getBoundsInRoot()
        assertTrue(
            "bottom chevron should sit past the scroll list edge (list=${appsListBounds.bottom}, chevron=${chevronBounds.bottom})",
            chevronBounds.bottom > appsListBounds.bottom,
        )
        assertTrue(
            "bottom chevron should sit on the apps card edge (card=${appsCardBounds.bottom}, chevron=${chevronBounds.bottom})",
            kotlin.math.abs((chevronBounds.bottom - appsCardBounds.bottom).value) <= 1f,
        )
        assertTrue(
            "bottom chevron tap target should stay compact",
            (chevronBounds.bottom - chevronBounds.top).value <= 33f,
        )
        assertTrue(
            "bottom chevron tap target should stay compact",
            (chevronBounds.right - chevronBounds.left).value <= 33f,
        )
    }

    @Test
    fun appsListWithoutOverflow_textRows_hidesBothChevrons() {
        val apps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_iconGrid_showsBottomChevronAndHidesTopAtRest() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        // Pin 4 icons per row (these chevron tests predate the 6-per-row
        // default). At the denser default this LazyVerticalGrid composes enough
        // cells that, under Robolectric, it leaves the shared Compose runtime in
        // a state where a later test in the same JVM never reaches idle; the
        // larger cells keep the cell count below that threshold.
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.IconOnly, dockIconSizeDp = dockIconSizeForSlotCount(411, 4)))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_textRows_topChevronAppearsAfterScrollingDown() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_bottomChevronTapScrollsOnePage() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App20").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App40").assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_topChevronTapScrollsOnePageBackUp() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        val firstAppBoundsAtTop = composeRule.onNodeWithTag("$APP_ROW_TAG:App01").getBoundsInRoot()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        val firstAppBoundsAfterReturn = composeRule.onNodeWithTag("$APP_ROW_TAG:App01").getBoundsInRoot()
        assertTrue(
            "top chevron tap should scroll back to the original top",
            kotlin.math.abs((firstAppBoundsAfterReturn.top - firstAppBoundsAtTop.top).value) <= 1f,
        )
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_iconGrid_bottomChevronTapScrollsOnePage() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.IconOnly, dockIconSizeDp = dockIconSizeForSlotCount(411, 4)))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:App01").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:App40").assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_reversedTextRows_topChevronTapScrollsOnePageVisuallyUp() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListSortOrder = AppListSortOrder.UsageReversed, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App20").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App40").assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_placesTopChevronAtOuterCardEdgeAfterScrollingDown() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val appsListBounds = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val chevronBounds = composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).getBoundsInRoot()
        assertTrue(
            "top chevron should sit past the scroll list edge (list=${appsListBounds.top}, chevron=${chevronBounds.top})",
            chevronBounds.top < appsListBounds.top,
        )
        assertTrue(
            "top chevron should sit on the apps card edge (card=${appsCardBounds.top}, chevron=${chevronBounds.top})",
            kotlin.math.abs((chevronBounds.top - appsCardBounds.top).value) <= 1f,
        )
    }

    @Test
    fun appsListShortList_reversedTextRows_anchorsFirstResultToCardBottom() {
        // A reversed sort puts the top match at index 0; it must render
        // visually nearest the bottom of the card so the user can find and
        // tap it next to the keyboard, even when there's plenty of empty
        // space above (short list / single search match).
        val apps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        renderHome(LauncherUiState(filteredApps = apps, appListSortOrder = AppListSortOrder.UsageReversed, appListLayout = AppListLayout.NameBeside))

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val firstAppBounds = composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").getBoundsInRoot()
        val secondAppBounds = composeRule.onNodeWithTag("$APP_ROW_TAG:Calendar").getBoundsInRoot()
        assertTrue(
            "top match should sit nearest the bottom of the card (card.bottom=${appsCardBounds.bottom}, row.bottom=${firstAppBounds.bottom})",
            (appsCardBounds.bottom - firstAppBounds.bottom).value <= 24f,
        )
        assertTrue(
            "top match should be below the second result under reversed sort (top=${firstAppBounds.top}, second.top=${secondAppBounds.top})",
            firstAppBounds.top > secondAppBounds.top,
        )
    }

    @Test
    fun appsListShortList_reversedIconGrid_anchorsFirstResultToCardBottom() {
        val apps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        renderHome(
            LauncherUiState(
                filteredApps = apps,
                appListSortOrder = AppListSortOrder.AlphabeticalReversed,
                appListLayout = AppListLayout.IconOnly,
            ),
        )

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val firstAppBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").getBoundsInRoot()
        assertTrue(
            "top match should sit nearest the bottom of the card (card.bottom=${appsCardBounds.bottom}, icon.bottom=${firstAppBounds.bottom})",
            (appsCardBounds.bottom - firstAppBounds.bottom).value <= 24f,
        )
    }

    @Test
    fun appsListShortList_forwardTextRows_anchorsFirstResultToCardTop() {
        val apps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.NameBeside))

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val firstAppBounds = composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").getBoundsInRoot()
        assertTrue(
            "forward sort should keep the first result at the top of the card (card.top=${appsCardBounds.top}, row.top=${firstAppBounds.top})",
            (firstAppBounds.top - appsCardBounds.top).value <= 24f,
        )
    }
}
