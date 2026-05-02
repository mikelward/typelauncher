package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockedAppStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun reorderMovesIdToTargetIndex() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")
        store.dock("d")

        store.reorder(fromIndex = 0, toIndex = 2)

        assertEquals(listOf("b", "c", "a", "d"), store.dockedAppIds)
    }

    @Test
    fun reorderSupportsMovingLeft() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")
        store.dock("d")

        store.reorder(fromIndex = 3, toIndex = 1)

        assertEquals(listOf("a", "d", "b", "c"), store.dockedAppIds)
    }

    @Test
    fun reorderClampsTargetIndexIntoRange() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")
        store.dock("c")

        store.reorder(fromIndex = 0, toIndex = 99)

        assertEquals(listOf("b", "c", "a"), store.dockedAppIds)
    }

    @Test
    fun reorderIgnoresOutOfRangeFromIndex() {
        val store = DockedAppStore(context)
        store.dock("a")
        store.dock("b")

        store.reorder(fromIndex = 5, toIndex = 0)

        assertEquals(listOf("a", "b"), store.dockedAppIds)
    }

    @Test
    fun reorderPersistsAcrossProcessRestart() {
        DockedAppStore(context).apply {
            dock("a")
            dock("b")
            dock("c")
            reorder(fromIndex = 2, toIndex = 0)
        }

        val reloaded = DockedAppStore(context)
        assertEquals(listOf("c", "a", "b"), reloaded.dockedAppIds)
    }
}
