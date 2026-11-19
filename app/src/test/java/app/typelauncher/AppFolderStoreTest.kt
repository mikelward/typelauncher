package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppFolderStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences(AppFolderStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun defaultsToOffAndUnanswered() {
        val store = AppFolderStore(context)

        assertFalse(store.isEnabled)
        assertFalse(store.isOfferAnswered)
        assertEquals(emptyMap<String, String?>(), store.assignments)
    }

    @Test
    fun settingsPersistAcrossInstances() {
        AppFolderStore(context).apply {
            isEnabled = true
            isOfferAnswered = true
        }

        val reopened = AppFolderStore(context)
        assertTrue(reopened.isEnabled)
        assertTrue(reopened.isOfferAnswered)
    }

    @Test
    fun filingsPersist_includingNoFolder() {
        AppFolderStore(context).apply {
            assign("0:com.example.bank/.Main", "finance")
            assign("0:com.example.chess/.Main", null)
        }

        val assignments = AppFolderStore(context).assignments
        assertEquals("finance", assignments["0:com.example.bank/.Main"])
        assertTrue(assignments.containsKey("0:com.example.chess/.Main"))
        assertEquals(null, assignments["0:com.example.chess/.Main"])
    }

    @Test
    fun refilingReplacesTheEarlierFolder() {
        val store = AppFolderStore(context)
        store.assign("a", "games")
        store.assign("a", "tools")

        assertEquals(mapOf("a" to "tools"), AppFolderStore(context).assignments)
    }

    @Test
    fun unreadableEntriesAreDropped_restKept() {
        // A folder this build doesn't know (one since deleted, or from a newer
        // version) is kept; grouping passes it over.
        val parsed = AppFolderStore.parse("a\tgames\nb\tfromthefuture\n\tsocial\nnot-a-pair\nc\tnone")

        assertEquals(mapOf("a" to "games", "b" to "fromthefuture", "c" to null), parsed)
    }

    @Test
    fun aiChoicesPersist_andClearingThemKeepsTheUsersFilings() {
        AppFolderStore(context).apply {
            isAiSortEnabled = true
            isAiOfferAnswered = true
            assign("mine", "tools")
            assignByAi(mapOf("a" to "finance", "b" to "other"))
        }

        val reopened = AppFolderStore(context)
        assertTrue(reopened.isAiSortEnabled)
        assertTrue(reopened.isAiOfferAnswered)
        assertEquals(mapOf("a" to "finance", "b" to "other"), reopened.aiAssignments)

        reopened.clearAiAssignments()
        val cleared = AppFolderStore(context)
        assertEquals(emptyMap<String, String>(), cleared.aiAssignments)
        assertEquals(mapOf("mine" to "tools"), cleared.assignments)
    }

    @Test
    fun aiChoicesFromOtherInstructions_areForgotten_once_andTheUsersFilingsKept() {
        AppFolderStore(context).apply {
            forgetAiChoicesUnlessMadeBy(1)
            assign("mine", "tools")
            assignByAi(mapOf("a" to "finance"))
        }

        // Same instructions: kept.
        assertFalse(AppFolderStore(context).forgetAiChoicesUnlessMadeBy(1))
        assertEquals(mapOf("a" to "finance"), AppFolderStore(context).aiAssignments)

        // New instructions (a folder added): forgotten, the user's kept, and only once.
        assertTrue(AppFolderStore(context).forgetAiChoicesUnlessMadeBy(2))
        assertEquals(emptyMap<String, String>(), AppFolderStore(context).aiAssignments)
        assertEquals(mapOf("mine" to "tools"), AppFolderStore(context).assignments)
        assertFalse(AppFolderStore(context).forgetAiChoicesUnlessMadeBy(2))
    }

    @Test
    fun usersFolders_persist_withTheirNameAndIcon_andCanBeEdited() {
        val kids = AppFolderStore(context).createFolder("  Kids\tstuff\n ", FolderIcon.ChildCare)
        val work = AppFolderStore(context).createFolder("Work", FolderIcon.Work)
        assertEquals("custom-1", kids.key)
        assertEquals("custom-2", work.key)

        AppFolderStore(context).editFolder(kids.key, "Family", FolderIcon.FamilyRestroom)

        val folders = AppFolderStore(context).folders
        assertEquals(
            AppFolderDef("custom-1", null, "Family", FolderIcon.FamilyRestroom),
            folders.first { it.key == "custom-1" },
        )
        assertEquals("Work", folders.first { it.key == "custom-2" }.name)
        assertEquals("other", folders.last().key)
    }

    @Test
    fun aFolderName_isKeptToOneLine() {
        val folder = AppFolderStore(context).createFolder("  Kids\tstuff\n ", FolderIcon.ChildCare)

        assertEquals("Kids stuff", folder.name)
        assertEquals("Kids stuff", AppFolderStore(context).folders.first { it.key == folder.key }.name)
    }

    @Test
    fun deletingAFolder_dropsItsFilings_andANewFolderNeverReusesItsKey() {
        val store = AppFolderStore(context)
        val kids = store.createFolder("Kids", FolderIcon.ChildCare)
        store.assign("crayons", kids.key)
        store.assign("chess", "games")
        store.assign("mail", "office")
        store.assignByAi(mapOf("sudoku" to "games", "bank" to "finance"))

        store.deleteFolder(kids.key)
        store.deleteFolder("games")

        val reopened = AppFolderStore(context)
        assertEquals(mapOf("mail" to "office"), reopened.assignments)
        assertEquals(mapOf("bank" to "finance"), reopened.aiAssignments)
        assertTrue(reopened.hasDeletedBuiltIns)
        assertFalse(reopened.folders.any { it.key == "games" || it.key == kids.key })
        assertEquals("custom-2", reopened.createFolder("Kids", FolderIcon.ChildCare).key)
    }

    @Test
    fun mergingAFolder_movesEveryFilingIntoIt_inOneGo_evenForAppsNotListed() {
        // Codex on PR #861: a hidden or paused app's filing moves too.
        val store = AppFolderStore(context)
        store.assign("hidden-game", "games")
        store.assign("mail", "office")
        store.assignByAi(mapOf("paused-game" to "games", "bank" to "finance"))

        // The caller lists every installed app in the folder, hidden and paused
        // ones too, by the AI's choice where that counts.
        store.mergeFolder("games", "kids", members = listOf("manifest-game", "paused-game"))

        val reopened = AppFolderStore(context)
        assertEquals(
            mapOf("hidden-game" to "kids", "mail" to "office", "paused-game" to "kids", "manifest-game" to "kids"),
            reopened.assignments,
        )
        assertEquals(mapOf("bank" to "finance"), reopened.aiAssignments)
        assertFalse(reopened.folders.any { it.key == "games" })
    }

    @Test
    fun mergingAFolder_leavesAnAiChoiceThatDidntPutTheAppThere() {
        // Codex on PRs #861 and #867: with AI sorting off, or with the table
        // overriding the AI (Netflix stays in Media whatever the AI said), an
        // AI choice of the folder doesn't put the app in it, so merging the
        // folder mustn't move the app.
        val store = AppFolderStore(context)
        store.assignByAi(mapOf("netflix" to "games"))

        store.mergeFolder("games", "kids", members = emptyList())

        assertFalse(AppFolderStore(context).assignments.containsKey("netflix"))
    }

    @Test
    fun other_cantBeDeleted_andRestoringBringsBackTheBuiltIns() {
        val store = AppFolderStore(context)
        store.deleteFolder("other")
        store.deleteFolder("games")
        assertEquals("other", store.folders.last().key)

        store.restoreBuiltInFolders()

        val reopened = AppFolderStore(context)
        assertFalse(reopened.hasDeletedBuiltIns)
        assertEquals(activeAppFolders(emptyList(), emptySet()), reopened.folders)
    }

    @Test
    fun unreadableFolders_areDropped_restKept() {
        val parsed = AppFolderStore.parseFolders("custom-1\tpets\tZoo\ngames\tstar\tGames\ncustom-2\tunknown\tKids\ncustom-3\tstar\t")

        assertEquals(
            listOf(
                AppFolderDef("custom-1", null, "Zoo", FolderIcon.Pets),
                AppFolderDef("custom-2", null, "Kids", FolderIcon.Folder),
            ),
            parsed,
        )
    }

}
