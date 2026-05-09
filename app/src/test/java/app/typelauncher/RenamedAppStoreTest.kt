package app.typelauncher

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RenamedAppStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("renamed_apps", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun renamePersistsAndReloads() {
        RenamedAppStore(context).rename("0:com.openai.chatgpt/.Web", "Codex")

        val reloaded = RenamedAppStore(context)
        assertEquals("Codex", reloaded.customNameFor("0:com.openai.chatgpt/.Web"))
    }

    @Test
    fun renameTrimsLeadingAndTrailingWhitespace() {
        val store = RenamedAppStore(context)
        store.rename("a", "  Codex  ")
        assertEquals("Codex", store.customNameFor("a"))
    }

    @Test
    fun renameWithBlankClearsExistingOverride() {
        val store = RenamedAppStore(context)
        store.rename("a", "Codex")
        store.rename("a", "   ")
        assertNull(store.customNameFor("a"))
    }

    @Test
    fun clearRemovesOverride() {
        val store = RenamedAppStore(context)
        store.rename("a", "Codex")
        store.clear("a")
        assertNull(store.customNameFor("a"))
    }

    @Test
    fun clearMissingIdIsNoOp() {
        val store = RenamedAppStore(context)
        store.clear("never-renamed")
        assertNull(store.customNameFor("never-renamed"))
    }

    @Test
    fun renameOverwritesPreviousValue() {
        val store = RenamedAppStore(context)
        store.rename("a", "First")
        store.rename("a", "Second")
        assertEquals("Second", store.customNameFor("a"))
        assertEquals("Second", RenamedAppStore(context).customNameFor("a"))
    }

    @Test
    fun customNameForUnknownIdIsNull() {
        assertNull(RenamedAppStore(context).customNameFor("unknown"))
    }

    @Test
    fun multipleOverridesPersistIndependently() {
        val store = RenamedAppStore(context)
        store.rename("a", "Alpha")
        store.rename("b", "Beta")
        store.clear("a")
        val reloaded = RenamedAppStore(context)
        assertNull(reloaded.customNameFor("a"))
        assertEquals("Beta", reloaded.customNameFor("b"))
    }

    @Test
    fun corruptStoredJsonDegradesToEmpty() {
        context.getSharedPreferences("renamed_apps", android.content.Context.MODE_PRIVATE)
            .edit().putString("custom_names", "not-json").commit()
        // The constructor must not throw and must report no overrides; a
        // corrupt blob should never crash the launcher at cold start.
        assertNull(RenamedAppStore(context).customNameFor("anything"))
    }
}
