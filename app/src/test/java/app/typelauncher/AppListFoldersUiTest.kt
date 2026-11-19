package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
    private val games = AppListFolder(AppFolderDef.builtIn(AppCategory.Games), listOf(chess, sudoku))

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
        // Drawn as its category icon, in the app's own icon footprint.
        composeRule.onNodeWithTag(APP_LIST_FOLDER_ICON_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performClick()
        assertEquals(games, opened)
    }

    @Test
    fun reversedGrid_keepsFoldersLeftToRightInFolderOrder() {
        // A reversed grid flips its rows but not the order within a row, so the
        // folders must still read Games, Social, Other (Codex on PR #850).
        val social = AppListFolder(AppFolderDef.builtIn(AppCategory.Social), listOf(app("Chat"), app("Zoom")))
        val other = AppListFolder(AppFolderDef.builtIn(AppCategory.Other), listOf(app("Notes")))
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
    fun openFolder_closeItemIsAtTheVisualBottom_inEveryLayoutAndDirection() {
        // Like a dock folder's close tile, and where a thumb is: below the
        // apps whichever way the list runs (maintainer, 2026-11-19).
        for (layout in listOf(AppListLayout.NameBeside, AppListLayout.NameBelow)) {
            for (reversed in listOf(false, true)) {
                assertCloseBelowApps(layout, reversed)
            }
        }
    }

    private fun assertCloseBelowApps(layout: AppListLayout, reversed: Boolean) {
        if (!composed) {
            composeRule.setContent {
                TypeLauncherTheme {
                    Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                        AppsCard(
                            apps = games.apps,
                            dockLimit = Int.MAX_VALUE,
                            layout = currentConfig.first,
                            iconSizeDp = 43,
                            highlightFirst = false,
                            reverseLayout = currentConfig.second,
                            onLaunchApp = {},
                            onOpenAppInfo = {},
                            onToggleDock = { _, _ -> },
                            onResetRank = {},
                            onRenameApp = { _, _ -> },
                            onHideApp = {},
                            onUninstallApp = {},
                            openFolder = games,
                            onCloseFolder = { closeCount++ },
                        )
                    }
                }
            }
            composed = true
        }
        currentConfig = layout to reversed
        composeRule.waitForIdle()

        val appTag = if (layout == AppListLayout.NameBeside) APP_ROW_TAG else APP_ICON_ONLY_BUTTON_TAG
        val close = composeRule.onNodeWithTag(APP_LIST_FOLDER_CLOSE_TAG).fetchSemanticsNode().boundsInRoot
        for (app in games.apps) {
            val bounds = composeRule.onNodeWithTag("$appTag:${app.name}").fetchSemanticsNode().boundsInRoot
            assertTrue("$layout reversed=$reversed: close $close not below ${app.name} $bounds", close.top >= bounds.top)
        }
        val before = closeCount
        composeRule.onNodeWithTag(APP_LIST_FOLDER_CLOSE_TAG).performClick()
        assertEquals(before + 1, closeCount)
    }

    // One composition, reconfigured per case: a compose rule takes setContent once.
    private var composed = false
    private var currentConfig by mutableStateOf(AppListLayout.NameBeside to false)
    private var closeCount = 0

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
    fun gridOffer_bothAnswersReachTheirCallbacks_andFoldersOfferGoesFirst() {
        var accepted = false
        var dismissed = false
        var foldersPending by mutableStateOf(true)
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.width(320.dp).height(480.dp)) {
                    AppsCard(
                        apps = listOf(mail),
                        dockLimit = Int.MAX_VALUE,
                        layout = AppListLayout.NameBeside,
                        iconSizeDp = 43,
                        highlightFirst = false,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUninstallApp = {},
                        showFoldersOffer = foldersPending,
                        showGridOffer = true,
                        onAcceptGridOffer = { accepted = true },
                        onDismissGridOffer = { dismissed = true },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(GRID_OFFER_TAG).assertDoesNotExist()

        foldersPending = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Show apps in a grid?").assertExists()
        composeRule.onNodeWithTag(GRID_OFFER_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(GRID_OFFER_ACCEPT_TAG).performClick()
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
        var moved: Pair<InstalledApp, String?>? = null
        compose(
            apps = listOf(mail),
            folderMenu = folderMenu(onMove = { app, key -> moved = app to key }),
        )

        composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Mail").performClick()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:productivity").performClick()

        assertEquals(mail to "productivity", moved)
    }

    @Test
    fun moveToFolder_absentWhileFoldersAreOff() {
        compose(apps = listOf(mail))

        composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Mail").assertDoesNotExist()
    }

    @Test
    fun openFolderNote_showsUnderTheHeader() {
        val other = AppListFolder(AppFolderDef.builtIn(AppCategory.Other), listOf(mail))
        compose(
            apps = other.apps,
            openFolder = other,
            openFolderNote = {
                OtherFolderAiNote(
                    progress = AiSortProgress(AiSortPhase.Sorting, sorted = 40, total = 120),
                    showOffer = false,
                    onAccept = {},
                    onDismiss = {},
                )
            },
        )

        val header = composeRule.onNodeWithTag(APP_LIST_FOLDER_HEADER_TAG).fetchSemanticsNode().boundsInRoot
        val note = composeRule.onNodeWithTag(AI_SORT_PROGRESS_TAG).fetchSemanticsNode().boundsInRoot
        val app = composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").fetchSemanticsNode().boundsInRoot
        assertTrue(header.bottom <= note.top && note.bottom <= app.top)
        composeRule.onNodeWithText("Sorting 40 of 120…").assertExists()
    }

    @Test
    fun aiOffer_bothAnswersReachTheirCallbacks() {
        var accepted = false
        var dismissed = false
        composeRule.setContent {
            TypeLauncherTheme {
                OtherFolderAiNote(
                    progress = AiSortProgress(),
                    showOffer = true,
                    onAccept = { accepted = true },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag(AI_SORT_OFFER_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(AI_SORT_OFFER_ACCEPT_TAG).performClick()
        assertTrue(dismissed)
        assertTrue(accepted)
    }

    @Test
    fun aiNote_progressReplacesTheOffer_andNothingShowsWhenIdle() {
        var progress by mutableStateOf(AiSortProgress(AiSortPhase.Downloading))
        var showOffer by mutableStateOf(true)
        composeRule.setContent {
            TypeLauncherTheme {
                OtherFolderAiNote(progress = progress, showOffer = showOffer, onAccept = {}, onDismiss = {})
            }
        }
        composeRule.onNodeWithTag(AI_SORT_PROGRESS_TAG).assertExists()
        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertDoesNotExist()
        composeRule.onNodeWithText("Downloading the AI model…").assertExists()

        progress = AiSortProgress(AiSortPhase.Done, sorted = 3, total = 3)
        showOffer = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AI_SORT_PROGRESS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(AI_SORT_OFFER_TAG).assertDoesNotExist()
    }

    @Test
    fun aiSettings_showWhatItsDoing_andReSortOnlyWhileOn() {
        var enabled by mutableStateOf(true)
        var resorted = false
        composeRule.setContent {
            TypeLauncherTheme {
                Column {
                    AiSortSettingsRows(
                        isEnabled = enabled,
                        progress = AiSortProgress(AiSortPhase.Done, sorted = 1, total = 1),
                        onEnabledChanged = { enabled = it },
                        onResort = { resorted = true },
                    )
                }
            }
        }
        composeRule.onNodeWithText("Sorted 1 app").assertExists()
        composeRule.onNodeWithTag(AI_SORT_RESORT_TAG).performClick()
        assertTrue(resorted)

        composeRule.onNodeWithTag(AI_SORT_SWITCH_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(AI_SORT_STATUS_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(AI_SORT_RESORT_TAG).assertDoesNotExist()
    }

    @Test
    fun moveToFolder_listsTheUsersFolders_andOffersANewOne() {
        val kids = AppFolderDef("custom-1", null, "Kids", FolderIcon.ChildCare)
        var moved: Pair<InstalledApp, String?>? = null
        compose(
            apps = listOf(mail),
            folderMenu = folderMenu(
                folders = activeAppFolders(listOf(kids), setOf("games")),
                onMove = { app, key -> moved = app to key },
            ),
        )

        composeRule.onNodeWithTag("$APP_ROW_TAG:Mail").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_TO_FOLDER_ACTION_TAG:Mail").performClick()
        // A deleted folder isn't offered.
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:games").assertDoesNotExist()
        composeRule.onNodeWithTag(APP_FOLDER_PICKER_NEW_TAG).assertExists()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:custom-1").performClick()

        assertEquals(mail to "custom-1", moved)
    }

    @Test
    fun folderEditor_needsAName_thenSavesItWithTheChosenIcon() {
        var saved: Pair<String, FolderIcon>? = null
        composeRule.setContent {
            TypeLauncherTheme {
                AppFolderEditDialogContent(initial = null, onSave = { name, icon -> saved = name to icon }, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag(APP_FOLDER_SAVE_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(APP_FOLDER_NAME_FIELD_TAG).performTextInput("Kids")
        composeRule.onNodeWithTag("$APP_FOLDER_ICON_OPTION_TAG:child_care").performScrollTo().performClick()
        composeRule.onNodeWithTag(APP_FOLDER_SAVE_TAG).performClick()

        assertEquals("Kids" to FolderIcon.ChildCare, saved)
    }

    @Test
    fun folderEditor_keepsSaveInView_onAShortScreen() {
        // Codex on PR #859: landscape, or the keyboard up.
        composeRule.setContent {
            TypeLauncherTheme {
                Box(modifier = Modifier.height(320.dp)) {
                    AppFolderEditDialogContent(initial = null, onSave = { _, _ -> }, onDismiss = {})
                }
            }
        }

        composeRule.onNodeWithTag(APP_FOLDER_SAVE_TAG).assertIsDisplayed()
    }

    @Test
    fun folderEditor_namesEachIcon_forTalkBack() {
        composeRule.setContent {
            TypeLauncherTheme {
                AppFolderEditDialogContent(initial = null, onSave = { _, _ -> }, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag("$APP_FOLDER_ICON_OPTION_TAG:folder").assertContentDescriptionEquals("Folder")
        composeRule.onNodeWithTag("$APP_FOLDER_ICON_OPTION_TAG:child_care").performScrollTo()
            .assertContentDescriptionEquals("Kids")
    }

    @Test
    fun folderEditor_startsFromTheFoldersOwnNameAndIcon() {
        var saved: Pair<String, FolderIcon>? = null
        val kids = AppFolderDef("custom-1", null, "Kids", FolderIcon.ChildCare)
        composeRule.setContent {
            TypeLauncherTheme {
                AppFolderEditDialogContent(initial = kids, onSave = { name, icon -> saved = name to icon }, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag(APP_FOLDER_SAVE_TAG).performClick()

        assertEquals("Kids" to FolderIcon.ChildCare, saved)
    }

    @Test
    fun longPressOnAFolder_deletesItOnceConfirmed() {
        var deleted: AppFolderDef? = null
        compose(apps = listOf(mail), folders = listOf(games), folderMenu = folderMenu(onDelete = { deleted = it }))

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:games").performTouchInput { longClick() }
        // Built-in folders keep their name and icon.
        composeRule.onNodeWithTag("$APP_FOLDER_EDIT_ACTION_TAG:games").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_FOLDER_DELETE_ACTION_TAG:games").performClick()
        composeRule.onNodeWithText("Delete Games?").assertExists()
        composeRule.onNodeWithTag(APP_FOLDER_DELETE_CONFIRM_TAG).performClick()

        assertEquals(games.folder, deleted)
    }

    @Test
    fun longPressOnTheUsersFolderTile_offersToEditIt() {
        val kids = AppListFolder(AppFolderDef("custom-1", null, "Kids", FolderIcon.ChildCare), listOf(chess, sudoku))
        compose(layout = AppListLayout.NameBelow, apps = listOf(mail), folders = listOf(kids), folderMenu = folderMenu())

        composeRule.onNodeWithText("Kids").assertExists()
        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:custom-1").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_FOLDER_EDIT_ACTION_TAG:custom-1").assertExists()
        composeRule.onNodeWithTag("$APP_FOLDER_DELETE_ACTION_TAG:custom-1").assertExists()
    }

    @Test
    fun other_hasNoMenu() {
        val other = AppListFolder(AppFolderDef.builtIn(AppCategory.Other), listOf(app("Notes")))
        compose(apps = listOf(mail), folders = listOf(games, other), folderMenu = folderMenu())

        composeRule.onNodeWithTag("$APP_LIST_FOLDER_TAG:other").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_FOLDER_DELETE_ACTION_TAG:other").assertDoesNotExist()
    }

    private fun folderMenu(
        folders: List<AppFolderDef> = activeAppFolders(emptyList(), emptySet()),
        onMove: (InstalledApp, String?) -> Unit = { _, _ -> },
        onDelete: (AppFolderDef) -> Unit = {},
    ) = AppFolderMenu(
        folders = folders,
        folderOf = { null },
        onMove = onMove,
        onCreate = { _, _, _ -> },
        onEdit = { _, _, _ -> },
        onDelete = onDelete,
    )

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
        openFolderNote: (@androidx.compose.runtime.Composable () -> Unit)? = null,
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
                        openFolderNote = openFolderNote,
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
