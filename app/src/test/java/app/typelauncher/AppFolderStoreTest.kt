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
        assertEquals(emptyMap<String, AppCategory?>(), store.assignments)
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
            assign("0:com.example.bank/.Main", AppCategory.Finance)
            assign("0:com.example.chess/.Main", null)
        }

        val assignments = AppFolderStore(context).assignments
        assertEquals(AppCategory.Finance, assignments["0:com.example.bank/.Main"])
        assertTrue(assignments.containsKey("0:com.example.chess/.Main"))
        assertEquals(null, assignments["0:com.example.chess/.Main"])
    }

    @Test
    fun refilingReplacesTheEarlierFolder() {
        val store = AppFolderStore(context)
        store.assign("a", AppCategory.Games)
        store.assign("a", AppCategory.Tools)

        assertEquals(mapOf("a" to AppCategory.Tools), AppFolderStore(context).assignments)
    }

    @Test
    fun unreadableEntriesAreDropped_restKept() {
        val parsed = AppFolderStore.parse("a\tgames\nb\tfromthefuture\n\tsocial\nnot-a-pair\nc\tnone")

        assertEquals(mapOf("a" to AppCategory.Games, "c" to null), parsed)
    }

    @Test
    fun aiChoicesPersist_andClearingThemKeepsTheUsersFilings() {
        AppFolderStore(context).apply {
            isAiSortEnabled = true
            isAiOfferAnswered = true
            assign("mine", AppCategory.Tools)
            assignByAi(mapOf("a" to AppCategory.Finance, "b" to AppCategory.Other))
        }

        val reopened = AppFolderStore(context)
        assertTrue(reopened.isAiSortEnabled)
        assertTrue(reopened.isAiOfferAnswered)
        assertEquals(mapOf("a" to AppCategory.Finance, "b" to AppCategory.Other), reopened.aiAssignments)

        reopened.clearAiAssignments()
        val cleared = AppFolderStore(context)
        assertEquals(emptyMap<String, AppCategory>(), cleared.aiAssignments)
        assertEquals(mapOf("mine" to AppCategory.Tools), cleared.assignments)
    }
}
