package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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

/**
 * Coverage for dock folders at the ViewModel layer: merging via
 * [LauncherViewModel.mergeDockItems], the enable-folders gate, removing a
 * member, the main-list dedup of folder members, and the work-dock variant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelFolderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf(
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
    fun mergeCreatesFolderAndRemovesMembersFromDockedApps() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")

        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()

        val folders = viewModel.uiState.value.dockFolders
        assertEquals(1, folders.size)
        assertEquals(listOf("Maps", "Mail"), folders.single().members.map { it.name })
        val dockedNames = viewModel.uiState.value.dockedApps.map { it.name }
        assertFalse("Folder members must leave the loose dock, got $dockedNames", "Mail" in dockedNames)
        assertFalse("Folder members must leave the loose dock, got $dockedNames", "Maps" in dockedNames)
    }

    @Test
    fun mergeIsNoOpWhenFoldersDisabled() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        // Folders disabled (the default).
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")

        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()

        assertTrue("No folder should form while disabled", viewModel.uiState.value.dockFolders.isEmpty())
    }

    @Test
    fun removingMemberDownToOneDissolvesFolder() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()
        val folderId = viewModel.uiState.value.dockFolders.single().id

        viewModel.removeAppFromDockFolder(folderId, mail.id)
        idle()

        assertTrue("Folder dissolves at one member", viewModel.uiState.value.dockFolders.isEmpty())
        val dockedNames = viewModel.uiState.value.dockedApps.map { it.name }
        assertTrue("Both apps return as loose icons, got $dockedNames", "Mail" in dockedNames)
        assertTrue("Both apps return as loose icons, got $dockedNames", "Maps" in dockedNames)
    }

    @Test
    fun folderMembersAreDedupedFromUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()

        // Default "Show docked apps" is off, blank query, Full tier: folder
        // members dedupe out of the list exactly like loose docked apps.
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertFalse("Folder member Mail must hide from the list, got $names", "Mail" in names)
        assertFalse("Folder member Maps must hide from the list, got $names", "Maps" in names)
    }

    @Test
    fun typingSurfacesFolderMembers() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()

        // Typing hides the dock (and folders), so members must be findable.
        viewModel.setQuery("ma")
        idle()
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Folder member Maps must surface while typing, got $names", "Maps" in names)
    }

    @Test
    fun movingOutAFolderMemberLeavesItDockedLoose() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Photos", "com.example.photos")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        val photos = dockApp(viewModel, "Photos")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, photos.id)
        idle()

        // "Move out" (popup) pops the member back to a loose dock icon — it
        // leaves the folder but stays on the dock.
        viewModel.removeAppFromDockFolder(folderId, mail.id)
        idle()

        val folder = viewModel.uiState.value.dockFolders.single()
        assertFalse("Mail left the folder", "Mail" in folder.members.map { it.name })
        assertTrue("Mail stays on the dock", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })
    }

    @Test
    fun undockingAFolderMemberRemovesItFromTheDock() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Photos", "com.example.photos")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        val photos = dockApp(viewModel, "Photos")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, photos.id)
        idle()

        // "Undock" (popup) takes the member off the dock entirely.
        viewModel.undockAppFromDockFolder(folderId, mail.id)
        idle()

        val folder = viewModel.uiState.value.dockFolders.single()
        assertFalse("Mail left the folder", "Mail" in folder.members.map { it.name })
        assertFalse("Mail is off the dock", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })
    }

    @Test
    fun foldlerMembersFloatAboveNonDockedMatchesInSearch() {
        seedApp("Maps", "com.example.maps")
        seedApp("Mail", "com.example.mail")
        seedApp("Mask", "com.example.mask")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        val maps = dockApp(viewModel, "Maps")
        val mail = dockApp(viewModel, "Mail")
        viewModel.mergeDockItems(sourceId = maps.id, targetId = mail.id)
        idle()

        // "ma" matches all three; Mask is not docked. The foldered members must
        // float ahead of it (docked-first ranking expands folders in place).
        viewModel.setQuery("ma")
        idle()
        val names = viewModel.uiState.value.filteredApps.map { it.name }.filter { it in setOf("Maps", "Mail", "Mask") }
        assertEquals("Foldered members float above the non-docked match, got $names", "Mask", names.last())
    }

    @Test
    fun setDockFoldersEnabledRoundTripsState() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()

        assertFalse(viewModel.uiState.value.isDockFoldersEnabled)
        viewModel.setDockFoldersEnabled(true)
        idle()
        assertTrue(viewModel.uiState.value.isDockFoldersEnabled)
    }

    @Test
    fun mergeWorkDockItemsGroupsWorkApps() {
        seedApp("Ztwo Work", "com.example.work2")
        seedApp("Zone Work", "com.example.work1")
        val viewModel = newViewModel()
        idle()
        viewModel.setDockFoldersEnabled(true)
        viewModel.markAsActiveWorkAppForTest("com.example.work1")
        viewModel.markAsActiveWorkAppForTest("com.example.work2")
        viewModel.setWorkDockEnabled(true)
        idle()
        val one = workApp(viewModel, "Zone Work")
        val two = workApp(viewModel, "Ztwo Work")

        viewModel.mergeWorkDockItems(sourceId = one.id, targetId = two.id)
        idle()

        val folders = viewModel.uiState.value.workDockFolders
        assertEquals(1, folders.size)
        assertEquals(
            setOf("Zone Work", "Ztwo Work"),
            folders.single().members.map { it.name }.toSet(),
        )
        assertTrue(viewModel.uiState.value.dockFolders.isEmpty())
    }

    private fun dockApp(viewModel: LauncherViewModel, name: String): InstalledApp {
        val app = viewModel.uiState.value.filteredApps.first { it.name == name }
        viewModel.toggleDock(app, maxDockedApps = 6)
        idle()
        return app
    }

    private fun workApp(viewModel: LauncherViewModel, name: String): InstalledApp {
        val app = viewModel.uiState.value.filteredApps.first { it.name == name }
        viewModel.toggleWorkDock(app, maxDockedApps = 6)
        idle()
        return app
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }
}
