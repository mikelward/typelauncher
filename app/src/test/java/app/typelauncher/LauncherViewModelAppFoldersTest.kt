package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** App-list folders through the view model: the setting, the Home offer, and filing an app. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelAppFoldersTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf(
            AppFolderStore.PREFERENCES_NAME,
            "docked_apps",
            "work_docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun freshInstall_offersFolders_withFoldersOff() {
        val state = newViewModel().uiState.value

        assertFalse(state.isAppFoldersEnabled)
        assertTrue(state.isAppFoldersOfferPending)
    }

    @Test
    fun dismissingTheOffer_retiresItForGood() {
        newViewModel().dismissAppFoldersOffer()

        val state = newViewModel().uiState.value
        assertFalse(state.isAppFoldersOfferPending)
        assertFalse(state.isAppFoldersEnabled)
    }

    @Test
    fun turningFoldersOn_retiresTheOffer_andPersists() {
        val viewModel = newViewModel()
        viewModel.setAppFoldersEnabled(true)
        assertFalse(viewModel.uiState.value.isAppFoldersOfferPending)

        // Turning them back off in Settings doesn't bring the offer back.
        viewModel.setAppFoldersEnabled(false)
        val reopened = newViewModel().uiState.value
        assertFalse(reopened.isAppFoldersEnabled)
        assertFalse(reopened.isAppFoldersOfferPending)
    }

    @Test
    fun movingAnApp_filesItAndPersists() {
        seedApp("Bank", "com.example.bank")
        val viewModel = newViewModel()
        idle()
        val bank = viewModel.uiState.value.filteredApps.first { it.name == "Bank" }

        viewModel.moveAppToFolder(bank, "finance")

        assertEquals("finance", viewModel.uiState.value.appFolderAssignments[bank.id])
        assertEquals("finance", newViewModel().uiState.value.appFolderAssignments[bank.id])
    }

    @Test
    fun newFolder_takesTheApp_andDeletingIt_letsTheAppBeSortedAgain() {
        seedApp("Crayons", "com.example.crayons")
        val viewModel = newViewModel()
        idle()
        val crayons = viewModel.uiState.value.filteredApps.first { it.name == "Crayons" }

        viewModel.createAppFolder(crayons, "Kids", FolderIcon.ChildCare)

        val kids = newViewModel().uiState.value.appFolders.first { it.name == "Kids" }
        assertEquals(FolderIcon.ChildCare, kids.icon)
        assertEquals(kids.key, viewModel.uiState.value.appFolderAssignments[crayons.id])

        viewModel.editAppFolder(kids, "Family", FolderIcon.FamilyRestroom)
        assertEquals("Family", viewModel.uiState.value.appFolders.first { it.key == kids.key }.name)

        viewModel.deleteAppFolder(kids)

        val state = newViewModel().uiState.value
        assertFalse(state.appFolders.any { it.key == kids.key })
        assertFalse(state.appFolderAssignments.containsKey(crayons.id))
    }

    @Test
    fun mergingAFolder_filesItsAppsIntoTheOther_andDeletesIt() {
        // The maintainer, 2026-11-19: make a few too many folders, and let
        // the user merge the ones they don't want.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        val chess = viewModel.uiState.value.filteredApps.first { it.name == "Chess" }
        val sudoku = viewModel.uiState.value.filteredApps.first { it.name == "Sudoku" }
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.moveAppToFolder(sudoku, "games")

        viewModel.mergeAppFolder(AppFolderDef.builtIn(AppCategory.Games), AppFolderDef.builtIn(AppCategory.Kids))

        val state = newViewModel().uiState.value
        assertFalse(state.appFolders.any { it.key == "games" })
        assertEquals("kids", state.appFolderAssignments[chess.id])
        assertEquals("kids", state.appFolderAssignments[sudoku.id])
        assertFalse(state.appFolderAssignments.containsKey(mail.id))
    }

    @Test
    fun aNewFolderFromSettings_startsEmpty_andPersists() {
        val viewModel = newViewModel()
        idle()

        viewModel.createAppFolder("Kids Stuff", FolderIcon.ChildCare)

        val folder = newViewModel().uiState.value.appFolders.single { it.name == "Kids Stuff" }
        assertEquals(FolderIcon.ChildCare, folder.icon)
        assertFalse(viewModel.uiState.value.appFolderAssignments.containsValue(folder.key))
    }

    @Test
    fun deletedBuiltIns_comeBackOnRestore() {
        val viewModel = newViewModel()
        idle()

        viewModel.deleteAppFolder(AppFolderDef.builtIn(AppCategory.Games))
        assertTrue(viewModel.uiState.value.hasDeletedAppFolders)
        assertFalse(newViewModel().uiState.value.appFolders.any { it.key == "games" })

        viewModel.restoreAppFolders()
        assertFalse(viewModel.uiState.value.hasDeletedAppFolders)
        assertTrue(newViewModel().uiState.value.appFolders.any { it.key == "games" })
    }

    @Test
    fun typingAFoldersName_findsTheFolder_andItsAppsAfterTheNameMatches() {
        // The maintainer, 2026-11-19: search matches folder names.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Gamma", "com.example.gamma")
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        viewModel.setAppFoldersEnabled(true)

        viewModel.setQuery("gam")
        idle()

        val state = viewModel.uiState.value
        assertEquals(listOf("games"), state.searchFolders.map { it.folder.key })
        assertEquals(setOf("Chess", "Sudoku"), state.searchFolders.single().apps.map { it.name }.toSet())
        val names = state.filteredApps.map { it.name }
        assertEquals("Gamma", names.first())
        assertTrue(names.containsAll(listOf("Chess", "Sudoku")))
        assertFalse("Mail" in names)
    }

    @Test
    fun folderSearch_findsOnlyFoldersTheListShows_withDockedAppsLeftOut() {
        // Codex on PR #862: two docked games make no Games folder in the
        // list, so search mustn't offer one that wouldn't open.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        viewModel.setAppFoldersEnabled(true)
        for (name in listOf("Chess", "Sudoku")) {
            val app = viewModel.uiState.value.filteredApps.first { it.name == name }
            if (!app.isDocked) viewModel.toggleDock(app, maxDockedApps = 12)
        }
        idle()

        viewModel.setQuery("gam")
        idle()

        assertTrue(viewModel.uiState.value.searchFolders.isEmpty())
    }

    @Test
    fun folderSearch_followsAFolderChange_madeWhileSearching() {
        // Codex on PR #862: deleting the folder a search found drops it.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        val viewModel = newViewModel()
        idle()
        viewModel.setAppFoldersEnabled(true)
        viewModel.setQuery("gam")
        idle()
        assertEquals(listOf("games"), viewModel.uiState.value.searchFolders.map { it.folder.key })

        viewModel.deleteAppFolder(AppFolderDef.builtIn(AppCategory.Games))
        idle()

        assertTrue(viewModel.uiState.value.searchFolders.isEmpty())
        assertTrue(viewModel.uiState.value.filteredApps.isEmpty())
    }

    @Test
    fun withFoldersOff_aFoldersName_findsDockedAppsToo() {
        // Codex on PR #864: the dock hides while typing, and there's no folder
        // to open, so a docked game must be found by "games" too.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        val viewModel = newViewModel()
        idle()
        val chess = viewModel.uiState.value.filteredApps.first { it.name == "Chess" }
        if (!chess.isDocked) viewModel.toggleDock(chess, maxDockedApps = 12)
        idle()

        viewModel.setQuery("gam")
        idle()

        assertTrue(viewModel.uiState.value.filteredApps.any { it.name == "Chess" })
    }

    @Test
    fun withFoldersOff_aFoldersName_stillFindsItsApps_withoutTheFolder() {
        // The maintainer, 2026-11-19: the folder name as text search matches,
        // even with folders off.
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Sudoku", "com.example.sudoku", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Notes", "com.example.notes")
        val viewModel = newViewModel()
        idle()

        viewModel.setQuery("gam")
        idle()
        assertTrue(viewModel.uiState.value.searchFolders.isEmpty())
        assertEquals(setOf("Chess", "Sudoku"), viewModel.uiState.value.filteredApps.map { it.name }.toSet())

        // Other isn't something to search for with folders off.
        viewModel.setQuery("oth")
        idle()
        assertTrue(viewModel.uiState.value.filteredApps.none { it.name == "Notes" })
    }

    @Test
    fun loadedApps_carryTheirManifestCategory() {
        seedApp("Chess", "com.example.chess", category = ApplicationInfo.CATEGORY_GAME)
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()

        val apps = viewModel.uiState.value.filteredApps.associateBy { it.name }
        assertEquals(AppCategory.Games, apps.getValue("Chess").declaredCategory)
        assertEquals(null, apps.getValue("Mail").declaredCategory)
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(label: String, packageName: String, category: Int = ApplicationInfo.CATEGORY_UNDEFINED) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.category = category
                }
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }
}
