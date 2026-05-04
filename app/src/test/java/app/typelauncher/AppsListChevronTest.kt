package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home apps list mirrors the dock and notification-bar overflow
 * treatment: a chevron is overlaid on the bottom edge while more rows are
 * off-screen below, and on the top edge once the user has scrolled past the
 * first row. Visibility is driven by `LazyListState.canScrollForward` /
 * `canScrollBackward`, so a list that fits the viewport shows neither
 * chevron.
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
    }

    @Test
    fun appsListOverflow_textRows_showsBottomChevronAndHidesTopAtRest() {
        // 60 entries comfortably exceed the apps-card viewport on a 411 × 914 dp
        // screen; the list starts scrolled to the top so only the bottom-edge
        // chevron should be visible.
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_textRows_placesBottomChevronAtCardPaddingEdge() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps))

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val appsListBounds = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val chevronBounds = composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).getBoundsInRoot()
        assertTrue(
            "bottom chevron should sit at the card edge (card=${appsCardBounds.bottom}, chevron=${chevronBounds.bottom})",
            chevronBounds.bottom <= appsCardBounds.bottom && chevronBounds.bottom > appsCardBounds.bottom - 16.dp,
        )
        assertTrue(
            "bottom chevron should overlap only the list edge area (list=${appsListBounds.bottom}, chevron=${chevronBounds.top})",
            chevronBounds.top > appsListBounds.bottom - 48.dp,
        )
    }

    @Test
    fun appsListWithoutOverflow_textRows_hidesBothChevrons() {
        val apps = listOf(fakeApp(name = "Calculator"), fakeApp(name = "Calendar"))
        renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_iconGrid_showsBottomChevronAndHidesTopAtRest() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps, isAppListIconOnly = true))

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_textRows_topChevronAppearsAfterScrollingDown() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_bottomChevronClickScrollsOnePage() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App08").assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App08").assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertIsDisplayed()
    }

    @Test
    fun appsListOverflow_textRows_placesTopChevronAtCardPaddingEdgeAfterScrollingDown() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val appsListBounds = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val chevronBounds = composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).getBoundsInRoot()
        assertTrue(
            "top chevron should sit at the card edge (card=${appsCardBounds.top}, chevron=${chevronBounds.top})",
            chevronBounds.top >= appsCardBounds.top && chevronBounds.top < appsCardBounds.top + 16.dp,
        )
        assertTrue(
            "top chevron should overlap only the list edge area (list=${appsListBounds.top}, chevron=${chevronBounds.bottom})",
            chevronBounds.bottom < appsListBounds.top + 48.dp,
        )
    }
}
