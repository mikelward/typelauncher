package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The "No matches" app-store search action: it appears only when a non-blank
 * query matched nothing, and firing it hands the trimmed query to the store
 * search callback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppStoreSearchActionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun noMatchesWithQuery_showsStoreActionAndPassesQuery() {
        var searched: String? = null
        composeEmptyAppsCard(query = "notanapp") { searched = it }

        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).assertExists()
        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).performClick()

        assertEquals("notanapp", searched)
    }

    @Test
    fun shortSlot_storeActionStaysReachableByScrolling() {
        // The apps card is measured to a fixed height (short landscape with the
        // keyboard up can leave it a row or two). The empty state scrolls, so
        // the action below the ~150dp message must still scroll into view and
        // fire rather than being clipped out of reach (Codex on PR #739).
        var searched: String? = null
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(320.dp).height(96.dp)) {
                    AppsCard(
                        apps = emptyList(),
                        dockLimit = Int.MAX_VALUE,
                        layout = AppListLayout.NameBeside,
                        iconSizeDp = 43,
                        highlightFirst = true,
                        query = "notanapp",
                        storeSearchReady = true,
                        onSearchAppStore = { searched = it },
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUninstallApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).performScrollTo().performClick()

        assertEquals("notanapp", searched)
    }

    @Test
    fun blankQuery_hidesStoreAction() {
        // A blank query with no apps means the device has nothing to list, not a
        // failed search — a store search for "" would be meaningless.
        composeEmptyAppsCard(query = "") {}

        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).assertDoesNotExist()
    }

    @Test
    fun evidenceNotSettled_hidesStoreAction() {
        // Until every enabled search source has finished loading (fresh app
        // inventory, content indexes), a no-match isn't definitive — the query
        // may still match an app installed since the cache snapshot, or a
        // contact/event whose index is still loading — so the action stays
        // hidden rather than offering to search for something already present
        // (Codex on PR #739).
        composeEmptyAppsCard(query = "notanapp", storeSearchReady = false) {}

        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).assertDoesNotExist()
    }

    @Test
    fun resultsPresent_hideStoreAction() {
        // The action belongs to the empty state only; with any match the list
        // renders instead.
        var searched: String? = null
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                    AppsCard(
                        apps = listOf(installedApp("Maps")),
                        dockLimit = Int.MAX_VALUE,
                        layout = AppListLayout.NameBeside,
                        iconSizeDp = 43,
                        highlightFirst = true,
                        query = "ma",
                        storeSearchReady = true,
                        onSearchAppStore = { searched = it },
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUninstallApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SEARCH_APP_STORE_TAG).assertDoesNotExist()
        assertEquals(null, searched)
    }

    private fun composeEmptyAppsCard(
        query: String,
        storeSearchReady: Boolean = true,
        onSearchAppStore: (String) -> Unit,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                    AppsCard(
                        apps = emptyList(),
                        dockLimit = Int.MAX_VALUE,
                        layout = AppListLayout.NameBeside,
                        iconSizeDp = 43,
                        highlightFirst = query.isNotBlank(),
                        query = query,
                        storeSearchReady = storeSearchReady,
                        onSearchAppStore = onSearchAppStore,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUninstallApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun installedApp(name: String): InstalledApp = InstalledApp(
        name = name,
        packageName = "com.example.${name.lowercase()}",
        launchIntent = android.content.Intent.makeMainActivity(
            android.content.ComponentName("com.example.${name.lowercase()}", "Main"),
        ),
        user = android.os.Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    )
}
