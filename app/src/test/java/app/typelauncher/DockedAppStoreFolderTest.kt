package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockedAppStoreFolderTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings").forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun mergeCreatesFolderAtTargetSlotAndRemovesLooseIds() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val targetPosition = store.dockedAppPositions["b"]

        val folderId = store.mergeIntoFolder(sourceId = "a", targetId = "b", columnCount = 4)

        assertNotNull(folderId)
        assertTrue(folderId!!.isDockFolderId())
        // Both loose ids are gone from the occupant list; the folder is an occupant.
        assertFalse("a" in store.dockedAppIds)
        assertFalse("b" in store.dockedAppIds)
        assertTrue(folderId in store.dockedAppIds)
        // The folder takes the target's slot, with target first.
        assertEquals(targetPosition, store.dockedAppPositions[folderId])
        assertEquals(listOf("b", "a"), store.dockFolders.single().memberAppIds)
        // c is untouched.
        assertTrue("c" in store.dockedAppIds)
    }

    @Test
    fun mergeOntoExistingFolderAppends() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        store.mergeIntoFolder(sourceId = "c", targetId = folderId, columnCount = 4)

        assertEquals(listOf("b", "a", "c"), store.dockFolders.single().memberAppIds)
        assertFalse("c" in store.dockedAppIds)
    }

    @Test
    fun reorderFolderMemberMovesAppToTargetIndex() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!
        store.addToFolder(folderId, "c", columnCount = 4)
        // Members start as [b, a, c].

        store.reorderFolderMember(folderId, appId = "c", targetIndex = 0)

        assertEquals(listOf("c", "b", "a"), store.dockFolders.single().memberAppIds)
    }

    @Test
    fun reorderFolderMemberClampsAndIgnoresNoOps() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!
        store.addToFolder(folderId, "c", columnCount = 4) // [b, a, c]

        // Past-the-end index clamps to the last slot.
        store.reorderFolderMember(folderId, appId = "b", targetIndex = 99)
        assertEquals(listOf("a", "c", "b"), store.dockFolders.single().memberAppIds)

        // Same index is a no-op; an unknown app is ignored.
        store.reorderFolderMember(folderId, appId = "a", targetIndex = 0)
        store.reorderFolderMember(folderId, appId = "zzz", targetIndex = 1)
        assertEquals(listOf("a", "c", "b"), store.dockFolders.single().memberAppIds)
    }

    @Test
    fun addToFolderMovesLooseAppIn() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        store.addToFolder(folderId, "c", columnCount = 4)

        assertEquals(listOf("b", "a", "c"), store.dockFolders.single().memberAppIds)
        assertTrue(store.isFolderMember("c"))
        assertFalse("c" in store.dockedAppIds)
    }

    @Test
    fun removeFromFolderRedocksMemberAsLooseIcon() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!
        store.addToFolder(folderId, "c", columnCount = 4)

        store.removeFromFolder(folderId, "a", columnCount = 4)

        assertTrue("a" in store.dockedAppIds)
        assertFalse(store.isFolderMember("a"))
        assertEquals(listOf("b", "c"), store.dockFolders.single().memberAppIds)
    }

    @Test
    fun removingDownToOneMemberCollapsesFolderInPlace() {
        val store = DockedAppStore(context)
        listOf("a", "b").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!
        val folderPosition = store.dockedAppPositions[folderId]

        store.removeFromFolder(folderId, "a", columnCount = 4)

        // Folder gone; lone member b takes its slot; a is re-docked loose.
        assertTrue(store.dockFolders.isEmpty())
        assertFalse(folderId in store.dockedAppIds)
        assertTrue("b" in store.dockedAppIds)
        assertTrue("a" in store.dockedAppIds)
        assertEquals(folderPosition, store.dockedAppPositions["b"])
    }

    @Test
    fun explodeFolderSpreadsMembersToLooseSlots() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!
        val folderPosition = store.dockedAppPositions[folderId]

        store.explodeFolder(folderId, columnCount = 4)

        assertTrue(store.dockFolders.isEmpty())
        assertTrue("a" in store.dockedAppIds)
        assertTrue("b" in store.dockedAppIds)
        // First member (b) keeps the folder's slot.
        assertEquals(folderPosition, store.dockedAppPositions["b"])
    }

    @Test
    fun renameFolderPersistsName() {
        val store = DockedAppStore(context)
        listOf("a", "b").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        store.renameFolder(folderId, "Social")
        assertEquals("Social", store.dockFolders.single().name)

        store.renameFolder(folderId, "  ")
        assertNull(store.dockFolders.single().name)
    }

    @Test
    fun renameWithSeparatorCharactersDoesNotCorruptPersistence() {
        DockedAppStore(context).apply {
            listOf("a", "b", "c").forEach { dock(it, columnCount = 4) }
            val folderId = mergeIntoFolder("a", "b", columnCount = 4)!!
            // A pasted name containing the field (\t) and record (\n) separators
            // must not break the docked_folders line format.
            renameFolder(folderId, "Work\tStuff\nGroup")
        }

        val reloaded = DockedAppStore(context)
        val folder = reloaded.dockFolders.single()
        assertEquals("Work Stuff Group", folder.name)
        assertEquals(listOf("b", "a"), folder.memberAppIds)
        // The sibling app must still parse — a corrupted line would drop it.
        assertTrue("c" in reloaded.dockedAppIds)
    }

    @Test
    fun foldersSurvivePersistenceRoundTrip() {
        DockedAppStore(context).apply {
            listOf("a", "b", "c").forEach { dock(it, columnCount = 4) }
            val folderId = mergeIntoFolder("a", "b", columnCount = 4)!!
            renameFolder(folderId, "Work")
        }

        val reloaded = DockedAppStore(context)
        val folder = reloaded.dockFolders.single()
        assertEquals(listOf("b", "a"), folder.memberAppIds)
        assertEquals("Work", folder.name)
        assertTrue(folder.id in reloaded.dockedAppIds)
        assertTrue("c" in reloaded.dockedAppIds)
    }

    @Test
    fun absentFolderKeyLeavesDockIntactWithNoFolders() {
        // Simulate a legacy install: only the two legacy keys are present.
        context.getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("docked_app_ids", "a\nb")
            .putString("docked_app_positions", "a\t0\t0\nb\t0\t1")
            .commit()

        val store = DockedAppStore(context)

        assertTrue(store.dockFolders.isEmpty())
        assertEquals(listOf("a", "b"), store.dockedAppIds)
        assertEquals(DockPosition(0, 1), store.dockedAppPositions["b"])
    }

    @Test
    fun memberIsNeverAlsoALooseOccupant() {
        val store = DockedAppStore(context)
        listOf("a", "b").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        store.dockFolders.single().memberAppIds.forEach { memberId ->
            assertFalse("$memberId should not also be a loose occupant", memberId in store.dockedAppIds)
        }
        assertTrue(folderId in store.dockedAppIds)
    }

    @Test
    fun mergePastMemberCapIsNoOp() {
        val store = DockedAppStore(context)
        val ids = (0 until MAX_DOCK_FOLDER_MEMBERS).map { "app$it" }
        ids.forEach { store.dock(it, columnCount = 8) }
        store.dock("overflow", columnCount = 8)
        // Build a full folder by merging the first app onto the second, then
        // adding the rest until at the cap.
        val folderId = store.mergeIntoFolder(ids[0], ids[1], columnCount = 8)!!
        ids.drop(2).forEach { store.addToFolder(folderId, it, columnCount = 8) }
        assertEquals(MAX_DOCK_FOLDER_MEMBERS, store.dockFolders.single().memberAppIds.size)

        store.addToFolder(folderId, "overflow", columnCount = 8)

        assertEquals(MAX_DOCK_FOLDER_MEMBERS, store.dockFolders.single().memberAppIds.size)
        assertTrue("overflow" in store.dockedAppIds)
    }

    @Test
    fun mergeOntoSelfIsNoOp() {
        val store = DockedAppStore(context)
        store.dock("a", columnCount = 4)

        assertNull(store.mergeIntoFolder("a", "a", columnCount = 4))
        assertTrue(store.dockFolders.isEmpty())
    }

    @Test
    fun dockIgnoresAFolderMember() {
        // A folder member is already on the dock (inside a folder); dock() is a
        // no-op. The "Move out of folder" action (removeFromFolder) is the path
        // that takes it out.
        val store = DockedAppStore(context)
        listOf("a", "b").forEach { store.dock(it, columnCount = 4) }
        store.mergeIntoFolder("a", "b", columnCount = 4)

        store.dock("a", columnCount = 4)

        assertFalse("a" in store.dockedAppIds)
        assertTrue(store.isFolderMember("a"))
    }

    @Test
    fun folderIdContainingFindsTheOwningFolder() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        assertEquals(folderId, store.folderIdContaining("a"))
        assertEquals(folderId, store.folderIdContaining("b"))
        assertNull(store.folderIdContaining("c"))
    }

    @Test
    fun dockedOrFolderedAppIdsUnionsLooseAndMembersWithoutFolderIds() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        val ids = store.dockedOrFolderedAppIds
        assertEquals(setOf("a", "b", "c"), ids)
        assertFalse("folder occupant id must not appear", folderId in ids)
    }

    @Test
    fun expandFolderOccupantsExpandsMembersInPlace() {
        val store = DockedAppStore(context)
        listOf("a", "b", "c").forEach { store.dock(it, columnCount = 4) }
        val folderId = store.mergeIntoFolder("a", "b", columnCount = 4)!!

        // Folder holds [b, a] (target first); expansion keeps the folder's slot.
        assertEquals(listOf("b", "a", "c"), store.expandFolderOccupants(listOf(folderId, "c")))
        assertEquals(listOf("c", "b", "a"), store.expandFolderOccupants(listOf("c", folderId)))
        // A loose-only list passes through unchanged.
        assertEquals(listOf("c"), store.expandFolderOccupants(listOf("c")))
    }

    @Test
    fun folderIdsAndAppIdsNeverCollide() {
        val folderId = newDockFolderId()
        assertTrue(folderId.isDockFolderId())
        // App ids look like "${int}:${component}" — integer before the colon.
        assertFalse("123:com.example/.Main".isDockFolderId())
        assertFalse("-1:com.example".isDockFolderId())
    }
}
