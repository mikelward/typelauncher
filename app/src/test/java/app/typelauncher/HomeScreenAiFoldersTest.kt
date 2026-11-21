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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
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

    // Video apps by their manifests: a manifest saying "game" beats the AI
    // (maintainer, 2026-11-19), so these use a category that doesn't.
    private val chess = app("Chess", AppCategory.Media)
    private val sudoku = app("Sudoku", AppCategory.Media)
    private val notes = app("Notes", AppCategory.Media)
    private val forecast = app("Forecast")

    @Test
    fun aiOn_aiChoiceBeatsTheManifest_andTheUsersFilingBeatsBoth() {
        compose(aiSortEnabled = true)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:finance").assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:media").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:tools").assertExists()
        assertMenuSelection(folderKey = "finance", appName = "Chess", selectedKey = "finance")
    }

    @Test
    fun aiOff_theManifestFilesAppsAgain_andTheUsersFilingStillWins() {
        compose(aiSortEnabled = false)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:media").assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:finance").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:tools").assertExists()
        assertMenuSelection(folderKey = "media", appName = "Chess", selectedKey = "media")
    }

    @Test
    fun aiOffer_showsAtTheTopOfEveryFolder_untilAnswered() {
        // The maintainer, 2026-11-19: not just in Other.
        compose(aiSortEnabled = false, aiOfferPending = true)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:media").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertExists()
    }

    @Test
    fun aiOffer_goneOnceAnswered() {
        compose(aiSortEnabled = false, aiOfferPending = false)

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:media").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertDoesNotExist()
    }

    @Test
    fun typedSearch_showsTheFoldersItNames_andOpeningOneClearsTheQuery() {
        var cleared = false
        val games = AppListFolder(AppFolderDef.builtIn(AppCategory.Media), listOf(chess, sudoku))
        compose(
            aiSortEnabled = false,
            query = "gam",
            searchFolders = listOf(games),
            onClearQuery = { cleared = true },
        )

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:media").performClick()

        assertTrue(cleared)
    }

    @Test
    fun dockedApp_showsAndChangesItsFolder_fromItsMenu() {
        // The maintainer, 2026-11-21: a docked app sits in no folder on the
        // list, so its menu and Edit dialog are the only places to see the
        // folder Google Play put it in. The Edit dialog's folder row reads the
        // same menu (see AppListFoldersUiTest).
        var moved: Pair<InstalledApp, String?>? = null
        compose(
            aiSortEnabled = false,
            dockedApps = listOf(forecast),
            playCategories = mapOf(forecast.packageName to "WEATHER"),
            onMoveAppToFolder = { app, key -> moved = app to key },
        )

        composeRule.onNodeWithTag("$DOCK_APP_TAG:Forecast").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Forecast").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:weather").assertIsSelected()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:tools").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertEquals(forecast to "tools", moved)
    }

    private fun assertMenuSelection(folderKey: String, appName: String, selectedKey: String) {
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:$folderKey").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$APP_ROW_TAG:$appName").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:$appName").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:$selectedKey").assertIsSelected()
        val other = if (selectedKey == "finance") "media" else "finance"
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:$other").assertIsNotSelected()
    }

    private fun compose(
        aiSortEnabled: Boolean,
        aiOfferPending: Boolean = false,
        query: String = "",
        searchFolders: List<AppListFolder> = emptyList(),
        onClearQuery: () -> Unit = {},
        dockedApps: List<InstalledApp> = emptyList(),
        playCategories: Map<String, String> = emptyMap(),
        onMoveAppToFolder: (InstalledApp, String?) -> Unit = { _, _ -> },
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
            dockedApps = dockedApps,
            playCategories = playCategories,
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
                    onMoveAppToFolder = onMoveAppToFolder,
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
