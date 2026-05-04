package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
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

    private fun renderHome(initialState: LauncherUiState): androidx.compose.runtime.MutableState<LauncherUiState> {
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
        return stateHolder
    }

    @Test
    fun textRows_queryChange_resetsScrollToTop() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val stateHolder = renderHome(LauncherUiState(filteredApps = apps))

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertExists()

        stateHolder.value = stateHolder.value.copy(query = "", filteredApps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun iconGrid_queryChange_resetsScrollToTop() {
        val apps = (1..60).map { i -> fakeApp(name = "App%02d".format(i)) }
        val stateHolder = renderHome(LauncherUiState(filteredApps = apps, isAppListIconOnly = true))

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertExists()

        stateHolder.value = stateHolder.value.copy(query = "a", filteredApps = apps)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }
}
