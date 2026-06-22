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
 * [LauncherViewModel.mergeDockItems], removing a member, the main-list dedup
 * of folder members, and the work-dock variant.
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
    fun removingMemberDownToOneDissolvesFolder() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
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
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()

        // Blank query, Full tier: folder members dedupe out of the list
        // exactly like loose docked apps.
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
    fun mergeWorkDockItemsGroupsWorkApps() {
        seedApp("Ztwo Work", "com.example.work2")
        seedApp("Zone Work", "com.example.work1")
        val viewModel = newViewModel()
        idle()
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

    @Test
    fun draggingAMemberOntoTheDockLeavesItLooseAtThatPosition() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Photos", "com.example.photos")
        val viewModel = newViewModel()
        idle()
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        val photos = dockApp(viewModel, "Photos")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, photos.id)
        idle()

        viewModel.moveDockFolderMemberToDock(folderId, mail.id, row = 0, column = 1)
        idle()

        val folder = viewModel.uiState.value.dockFolders.single()
        assertFalse("Mail left the folder", "Mail" in folder.members.map { it.name })
        assertTrue("Mail is a loose dock icon", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })
    }

    @Test
    fun draggingAMemberOntoAnotherDockIconMergesThemIntoAFolder() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Photos", "com.example.photos")
        seedApp("Music", "com.example.music")
        val viewModel = newViewModel()
        idle()
        val mail = dockApp(viewModel, "Mail")
        val maps = dockApp(viewModel, "Maps")
        val photos = dockApp(viewModel, "Photos")
        val music = dockApp(viewModel, "Music")
        viewModel.mergeDockItems(sourceId = mail.id, targetId = maps.id)
        idle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, photos.id)
        idle()

        // Drag Mail out of its folder and drop it onto the loose Music icon.
        viewModel.mergeDockFolderMemberInto(folderId, mail.id, music.id)
        idle()

        val folders = viewModel.uiState.value.dockFolders
        val mailFolder = folders.firstOrNull { f -> f.members.any { it.name == "Mail" } }
        assertTrue("Mail and Music form a folder", mailFolder != null)
        assertTrue("Music is in the new folder", mailFolder!!.members.any { it.name == "Music" })
        val source = folders.first { it.id == folderId }
        assertFalse("Mail left the source folder", "Mail" in source.members.map { it.name })
    }

    @Test
    fun mergingAMemberOntoAFullFolderKeepsItInItsSourceFolder() {
        // 18 apps: 16 fill the target folder, 2 form the source folder.
        (1..18).forEach { i -> seedApp("App$i", "com.example.app$i") }
        val viewModel = newViewModel()
        idle()

        // Build a 16-member target folder by docking then folding one app at a
        // time, so no more than two loose icons sit on the dock at once (under the
        // dock cap). Start from a 2-member folder, then add 14 more.
        val first = dockApp(viewModel, "App1")
        val second = dockApp(viewModel, "App2")
        viewModel.mergeDockItems(sourceId = first.id, targetId = second.id)
        idle()
        val targetId = viewModel.uiState.value.dockFolders.single().id
        (3..16).forEach { i ->
            val app = dockApp(viewModel, "App$i")
            viewModel.addAppToDockFolder(targetId, app.id)
            idle()
        }
        assertEquals(16, viewModel.uiState.value.dockFolders.first { it.id == targetId }.members.size)

        // A separate 2-member source folder.
        val s1 = dockApp(viewModel, "App17")
        val s2 = dockApp(viewModel, "App18")
        viewModel.mergeDockItems(sourceId = s1.id, targetId = s2.id)
        idle()
        val sourceId = viewModel.uiState.value.dockFolders.first { it.id != targetId }.id

        // Dropping s1 onto the full target folder must be a no-op: the merge can't
        // land, so the member stays in its source folder rather than being stripped.
        viewModel.mergeDockFolderMemberInto(sourceId, s1.id, targetId)
        idle()

        val target = viewModel.uiState.value.dockFolders.first { it.id == targetId }
        assertEquals("Full target folder is unchanged", 16, target.members.size)
        val source = viewModel.uiState.value.dockFolders.first { it.id == sourceId }
        assertTrue("Dragged member stays in its source folder", source.members.any { it.id == s1.id })
    }

    @Test
    fun draggingAListAppOntoTheDockDocksItThere() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        assertFalse("Mail starts undocked", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })

        viewModel.dockAppAtPosition(mail.id, row = 0, column = 0)
        idle()

        assertTrue("Mail is now a loose dock icon", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })
    }

    @Test
    fun draggingAListAppOntoADockIconMergesThemIntoAFolder() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val maps = dockApp(viewModel, "Maps")
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }

        viewModel.dockAppIntoDockOccupant(mail.id, maps.id)
        idle()

        val folder = viewModel.uiState.value.dockFolders.single()
        assertEquals(setOf("Maps", "Mail"), folder.members.map { it.name }.toSet())
        assertFalse("Mail left the loose list/dock into the folder", viewModel.uiState.value.dockedApps.any { it.name == "Mail" })
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
