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

    @Test
    fun concurrentRenameAndLookupStayConsistent() {
        // Regression test for the unsynchronized HashMap: `customNameFor` runs
        // on the IO dispatcher during every installed-apps reload while
        // `rename` / `clear` run on the main thread, so before the lock a
        // concurrent put could race `save()`'s iteration into a
        // ConcurrentModificationException (or a torn read that silently
        // dropped an override from the reloaded list). Same shape as
        // DockedAppStoreTest.concurrentMutationAndReadsStayConsistent;
        // inherently nondeterministic, sized to trip the race reliably.
        val store = RenamedAppStore(context)
        val ids = (0 until 50).map { index -> "app$index" }

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map { threadIndex ->
            Thread {
                try {
                    repeat(300) { iteration ->
                        val id = ids[(threadIndex * 31 + iteration) % ids.size]
                        when (iteration % 3) {
                            0 -> store.rename(id, "Name $threadIndex-$iteration")
                            1 -> store.clear(id)
                            // Consume the read so it can't be optimized away.
                            else -> check(store.customNameFor(id)?.isEmpty() != true)
                        }
                    }
                } catch (throwable: Throwable) {
                    failure.compareAndSet(null, throwable)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        failure.get()?.let { throw AssertionError("concurrent access failed", it) }
        // The store must still round-trip cleanly after the hammering.
        store.rename("final", "Final")
        assertEquals("Final", RenamedAppStore(context).customNameFor("final"))
    }
}
