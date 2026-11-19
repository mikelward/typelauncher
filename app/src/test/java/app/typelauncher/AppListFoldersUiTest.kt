package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Folders in the app list: opening and closing one, the Home offer, and filing an app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppListFoldersUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val chess = app("Chess", AppCategory.Games)
    private val sudoku = app("Sudoku", AppCategory.Games)
    private val mail = app("Mail")
    private val games = AppListFolder(AppCategory.Games, listOf(chess, sudoku))

    @Test
    fun closedFolderRow_tapOpensIt() = assertClosedFolderOpens(AppListLayout.NameBeside)

    @Test
    fun closedFolderTile_tapOpensIt() = assertClosedFolderOpens(AppListLayout.NameBelow)

    private fun assertClosedFolderOpens(layout: AppListLayout) {
        var opened: AppListFolder? = null
        compose(layout = layout, apps = listOf(mail), folders = listOf(games), onOpenFolder = { opened = it })

        // Its members stay inside it until it opens.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chess").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Chess").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performClick()
        assertEquals(games, opened)
    }

    @Test
    fun reversedGrid_keepsFoldersLeftToRightInFolderOrder() {
        // A reversed grid flips its rows but not the order within a row, so the
        // folders must still read Games, Social, Other (Codex on PR #850).
        val social = AppListFolder(AppCategory.Social, listOf(app("Chat"), app("Zoom")))
        val other = AppListFolder(AppCategory.Other, listOf(app("Notes")))
        compose(
            layout = AppListLayout.IconOnly,
            apps = listOf(mail),
            folders = listOf(games, social, other),
            reverseLayout = true,
        )

        val bounds = listOf("games", "social", "other").map { key ->
            composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:$key").fetchSemanticsNode().boundsInRoot
        }
        assertEquals(1, bounds.map { it.top }.distinct().size)
        assertTrue("folders out of order: $bounds", bounds[0].left < bounds[1].left && bounds[1].left < bounds[2].left)
        // Above the apps, at the reversed list's visual top.
        val mail = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Mail").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds[0].top < mail.top)
        // On the grid's own columns: the first folder sits over the first app.
        assertEquals(mail.left, bounds[0].left, 1f)
    }

    @Test
    fun onlyFolders_isNotAnEmptyList() {
        // Every app filed away leaves no loose apps, but the folders are still
        // results; the "No matches" state would hide them.
        compose(apps = emptyList(), folders = listOf(games))

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").assertExists()
        composeRule.onNodeWithTag(APPS_LIST_TAG).assertExists()
    }

    @Test
    fun openFolder_showsItsAppsUnderAHeaderThatClosesIt() {
        var closed = false
        compose(apps = games.apps, openFolder = games, onCloseFolder = { closed = true })

        composeRule.onNodeWithTag(APP_LIST_FOLDER_HEADER_TAG).assertExists()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Chess").assertExists()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Sudoku").assertExists()
        composeRule.onNodeWithTag(APP_LIST_FOLDER_CLOSE_TAG).performClick()
        assertTrue(closed)
    }

    @Test
    fun offer_bothAnswersReachTheirCallbacks() {
        var accepted = false
        var dismissed = false
        compose(
            apps = listOf(mail),
            showFoldersOffer = true,
            onAcceptFoldersOffer = { accepted = true },
            onDismissFoldersOffer = { dismissed = true },
        )

        composeRule.onNodeWithTag(APP_FOLDERS_OFFER_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(APP_FOLDERS_OFFER_ACCEPT_TAG).performClick()
        assertTrue(dismissed)
        assertTrue(accepted)
    }

    @Test
    fun offer_hiddenUnlessPending() {
        compose(apps = listOf(mail))

        composeRule.onNodeWithTag(APP_FOLDERS_OFFER_TAG).assertDoesNotExist()
    }

    @Test
    fun moveToFolder_filesTheApp() {
        var moved: Pair<InstalledApp, AppCategory?>? = null
        compose(
            apps = listOf(mail),
            folderMenu = AppFolderMenu(folderOf = { null }, onMove = { app, category -> moved = app to category }),
        )

        composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Mail").performClick()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:productivity").performClick()

        assertEquals(mail to AppCategory.Productivity, moved)
    }

    @Test
    fun moveToFolder_absentWhileFoldersAreOff() {
        compose(apps = listOf(mail))

        composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Mail").assertDoesNotExist()
    }

    private fun compose(
        layout: AppListLayout = AppListLayout.NameBeside,
        apps: List<InstalledApp>,
        folders: List<AppListFolder> = emptyList(),
        openFolder: AppListFolder? = null,
        onOpenFolder: (AppListFolder) -> Unit = {},
        onCloseFolder: () -> Unit = {},
        folderMenu: AppFolderMenu? = null,
        showFoldersOffer: Boolean = false,
        onAcceptFoldersOffer: () -> Unit = {},
        onDismissFoldersOffer: () -> Unit = {},
        reverseLayout: Boolean = false,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                    AppsCard(
                        apps = apps,
                        dockLimit = Int.MAX_VALUE,
                        layout = layout,
                        iconSizeDp = 43,
                        highlightFirst = false,
                        reverseLayout = reverseLayout,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUninstallApp = {},
                        folders = folders,
                        openFolder = openFolder,
                        onOpenFolder = onOpenFolder,
                        onCloseFolder = onCloseFolder,
                        folderMenu = folderMenu,
                        showFoldersOffer = showFoldersOffer,
                        onAcceptFoldersOffer = onAcceptFoldersOffer,
                        onDismissFoldersOffer = onDismissFoldersOffer,
                    )
                }
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
