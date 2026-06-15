package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `rememberLazyListState` / `rememberLazyGridState` survive query changes, so
 * the apps list would otherwise hold its old scroll offset when the user
 * narrows the result set with another keystroke (often past the new end of the
 * shorter list, showing only blank space) — and would also stay scrolled when
 * the user returns home after launching an app, since `launchApp` clears the
 * query. The fix scrolls back to item 0 whenever the query changes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class AppsListScrollResetTest {
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
        initialState: LauncherUiState,
        searchPlaceholderSuffix: String = "",
    ): androidx.compose.runtime.MutableState<LauncherUiState> {
        val stateHolder = mutableStateOf(initialState.copy(isFreshAppLoadComplete = true))
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
                    searchPlaceholderSuffix = searchPlaceholderSuffix,
                )
            }
        }
        return stateHolder
    }

    @Test
    fun searchPlaceholder_appendsBuildSuffixWhenProvided() {
        renderHome(
            initialState = LauncherUiState(filteredApps = listOf(fakeApp("Calculator"))),
            searchPlaceholderSuffix = " (abc1234)",
        )

        composeRule.onNodeWithText("Type an app name (abc1234)").assertIsDisplayed()
    }

    @Test
    fun textRows_queryChange_resetsScrollToTop() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val stateHolder = renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertExists()

        // A query change rebuilds the LazyColumn's data set; without the reset
        // the LazyListState would keep the post-swipe offset and the top
        // chevron would still be displayed.
        stateHolder.value = stateHolder.value.copy(query = "a", filteredApps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun textRows_clearingQuery_resetsScrollToTop() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        // Mirror the post-launch state: user typed something, scrolled down to
        // pick a substring match, tapped it. `launchApp` clears the query —
        // simulate the resulting query transition from "a" back to "".
        val stateHolder = renderHome(LauncherUiState(query = "a", filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertExists()

        stateHolder.value = stateHolder.value.copy(query = "", filteredApps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun iconGrid_queryChange_resetsScrollToTop() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        // Pin 4 icons per row: at the 6-per-row default this icon grid composes
        // enough cells to leave Robolectric's shared Compose runtime unable to
        // reach idle in a later test in the same JVM (see AppsListChevronTest).
        val stateHolder = renderHome(LauncherUiState(filteredApps = apps, appListLayout = AppListLayout.IconOnly, dockIconSizeDp = dockIconSizeForSlotCount(411, 4)))

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertExists()

        stateHolder.value = stateHolder.value.copy(query = "a", filteredApps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }
}
