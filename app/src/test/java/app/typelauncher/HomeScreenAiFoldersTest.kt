package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's folders with AI sorting on and off: on, the AI's choice beats an
 * app's manifest; off, the manifest files it again. The user's own filing
 * wins either way (Codex on PR #854).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeScreenAiFoldersTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chess = app("Chess", AppCategory.Games)
    private val sudoku = app("Sudoku", AppCategory.Games)
    private val notes = app("Notes", AppCategory.Games)

    @Test
    fun aiOn_aiChoiceBeatsTheManifest_andTheUsersFilingBeatsBoth() {
        compose(aiSortEnabled = true)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:finance").assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:tools").assertExists()
        assertMenuSelection(folderKey = "finance", appName = "Chess", selectedKey = "finance")
    }

    @Test
    fun aiOff_theManifestFilesAppsAgain_andTheUsersFilingStillWins() {
        compose(aiSortEnabled = false)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:finance").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:tools").assertExists()
        assertMenuSelection(folderKey = "games", appName = "Chess", selectedKey = "games")
    }

    @Test
    fun aiOffer_showsAtTheTopOfEveryFolder_untilAnswered() {
        // The maintainer, 2026-11-19: not just in Other.
        compose(aiSortEnabled = false, aiOfferPending = true)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertExists()
    }

    @Test
    fun aiOffer_goneOnceAnswered() {
        compose(aiSortEnabled = false, aiOfferPending = false)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertDoesNotExist()
    }

    @Test
    fun typedSearch_showsTheFoldersItNames_andOpeningOneClearsTheQuery() {
        var cleared = false
        val games = AppListFolder(AppFolderDef.builtIn(AppCategory.Games), listOf(chess, sudoku))
        compose(
            aiSortEnabled = false,
            query = "gam",
            searchFolders = listOf(games),
            onClearQuery = { cleared = true },
        )

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performClick()

        assertTrue(cleared)
    }

    private fun assertMenuSelection(folderKey: String, appName: String, selectedKey: String) {
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:$folderKey").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$APP_ROW_TAG:$appName").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:$appName").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:$selectedKey").assertIsSelected()
        val other = if (selectedKey == "finance") "games" else "finance"
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:$other").assertIsNotSelected()
    }

    private fun compose(
        aiSortEnabled: Boolean,
        aiOfferPending: Boolean = false,
        query: String = "",
        searchFolders: List<AppListFolder> = emptyList(),
        onClearQuery: () -> Unit = {},
    ) {
        val state = LauncherUiState(
            filteredApps = listOf(chess, notes, sudoku),
            isLoadingApps = false,
            isFreshAppLoadComplete = true,
            isAppFoldersEnabled = true,
            appFolderAssignments = mapOf(notes.id to AppCategory.Tools.storageKey),
            appFolderAiAssignments = mapOf(
                chess.id to AppCategory.Finance.storageKey,
                sudoku.id to AppCategory.Finance.storageKey,
                notes.id to AppCategory.Social.storageKey,
            ),
            isAiSortSupported = true,
            isAiSortEnabled = aiSortEnabled,
            isAiSortOfferPending = aiOfferPending,
            query = query,
            searchFolders = searchFolders,
        )
        composeRule.setContent {
            TypeLauncherTheme {
                HomeScreen(
                    state = state,
                    innerPadding = PaddingValues(),
                    bodyReady = true,
                    onQueryChanged = {},
                    onClearQuery = onClearQuery,
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUninstallApp = {},
                    onDismissRecent = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun app(name: String, category: AppCategory? = null): InstalledApp = InstalledApp(
        name = name,
        packageName = "com.example.${name.lowercase()}",
        launchIntent = Intent.makeMainActivity(ComponentName("com.example.${name.lowercase()}", "Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
        declaredCategory = category,
    )
}
