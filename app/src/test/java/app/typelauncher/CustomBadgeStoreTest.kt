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
class CustomBadgeStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("custom_badges", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun setBadgePersistsAndReloads() {
        CustomBadgeStore(context).setBadge("0:com.openai.chatgpt/.Web", "🇺🇸")

        val reloaded = CustomBadgeStore(context)
        assertEquals("🇺🇸", reloaded.customBadgeFor("0:com.openai.chatgpt/.Web"))
    }

    @Test
    fun setBadgeTrimsLeadingAndTrailingWhitespace() {
        val store = CustomBadgeStore(context)
        store.setBadge("a", "  🏠  ")
        assertEquals("🏠", store.customBadgeFor("a"))
    }

    @Test
    fun setBadgeWithBlankClearsExistingOverride() {
        val store = CustomBadgeStore(context)
        store.setBadge("a", "🏠")
        store.setBadge("a", "   ")
        assertNull(store.customBadgeFor("a"))
    }

    @Test
    fun clearRemovesOverride() {
        val store = CustomBadgeStore(context)
        store.setBadge("a", "🎓")
        store.clear("a")
        assertNull(store.customBadgeFor("a"))
    }

    @Test
    fun clearMissingIdIsNoOp() {
        val store = CustomBadgeStore(context)
        store.clear("never-set")
        assertNull(store.customBadgeFor("never-set"))
    }

    @Test
    fun setBadgeOverwritesPreviousValue() {
        val store = CustomBadgeStore(context)
        store.setBadge("a", "🏠")
        store.setBadge("a", "💼")
        assertEquals("💼", store.customBadgeFor("a"))
        assertEquals("💼", CustomBadgeStore(context).customBadgeFor("a"))
    }

    @Test
    fun customBadgeForUnknownIdIsNull() {
        assertNull(CustomBadgeStore(context).customBadgeFor("unknown"))
    }

    @Test
    fun multipleOverridesPersistIndependently() {
        val store = CustomBadgeStore(context)
        store.setBadge("a", "🏠")
        store.setBadge("b", "✈️")
        store.clear("a")
        val reloaded = CustomBadgeStore(context)
        assertNull(reloaded.customBadgeFor("a"))
        assertEquals("✈️", reloaded.customBadgeFor("b"))
    }

    @Test
    fun corruptStoredJsonDegradesToEmpty() {
        context.getSharedPreferences("custom_badges", android.content.Context.MODE_PRIVATE)
            .edit().putString("custom_badges", "not-json").commit()
        // The constructor must not throw and must report no overrides; a
        // corrupt blob should never crash the launcher at cold start.
        assertNull(CustomBadgeStore(context).customBadgeFor("anything"))
    }

    @Test
    fun concurrentSetBadgeAndLookupStayConsistent() {
        // Regression test for the unsynchronized HashMap: `customBadgeFor`
        // runs on the IO dispatcher during every installed-apps reload while
        // `setBadge` / `clear` run on the main thread, so before the lock a
        // concurrent put could race `save()`'s iteration into a
        // ConcurrentModificationException (or a torn read that silently
        // dropped an override from the reloaded list). Same shape as
        // DockedAppStoreTest.concurrentMutationAndReadsStayConsistent;
        // inherently nondeterministic, sized to trip the race reliably.
        val store = CustomBadgeStore(context)
        val ids = (0 until 50).map { index -> "app$index" }

        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val threads = (0 until 8).map { threadIndex ->
            Thread {
                try {
                    repeat(300) { iteration ->
                        val id = ids[(threadIndex * 31 + iteration) % ids.size]
                        when (iteration % 3) {
                            0 -> store.setBadge(id, "🏠")
                            1 -> store.clear(id)
                            // Consume the read so it can't be optimized away.
                            else -> check(store.customBadgeFor(id)?.isEmpty() != true)
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
        store.setBadge("final", "✈️")
        assertEquals("✈️", CustomBadgeStore(context).customBadgeFor("final"))
    }
}
