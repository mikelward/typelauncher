package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the in-flight request coalescing in `AppIconLoader.coalesce`. Multiple
 * surfaces (app list, dock, recents, …) can miss the cache for the same icon at
 * the same time during a cold start; the loader must only resolve+rasterize once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppIconLoaderCoalesceTest {
    @Test
    fun concurrentLoadsForSameKeyShareProducerInvocation() = runBlocking {
        val key = AppIconLoader.CacheKey(
            id = "0:app.typelauncher.coalescetest.shared/Activity@token-shared",
            sizePx = 24,
        )
        val invocations = AtomicInteger(0)
        val producerStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val bitmap = newBitmap(key.sizePx, Color.MAGENTA)

        val producer: suspend () -> ImageBitmap? = {
            invocations.incrementAndGet()
            producerStarted.complete(Unit)
            release.await()
            bitmap
        }

        // Kick off the first call and wait until its producer has started so the
        // in-flight deferred is established. Only then do we add concurrent callers,
        // guaranteeing they hit the in-flight path rather than racing to create a
        // second deferred.
        val first = async(Dispatchers.Default) { AppIconLoader.coalesce(key, producer) }
        producerStarted.await()
        val later = (1..7).map {
            async(Dispatchers.Default) { AppIconLoader.coalesce(key, producer) }
        }
        // Yield a few times so the later callers reach the mutex; even if they
        // don't, they'll find the populated cache after `release` fires.
        repeat(5) { yield() }
        release.complete(Unit)

        assertSame(bitmap, first.await())
        for (caller in later) {
            assertSame(bitmap, caller.await())
        }
        assertEquals("producer should run exactly once for coalesced misses", 1, invocations.get())
        assertNotNull(
            "completed bitmap should land in the LRU for subsequent cache hits",
            AppIconLoader.cached(key.id, key.sizePx),
        )
    }

    @Test
    fun differentKeysRunIndependentProducers() = runBlocking {
        val keyA = AppIconLoader.CacheKey(
            id = "0:app.typelauncher.coalescetest.distinctA/Activity@token-A",
            sizePx = 24,
        )
        val keyB = AppIconLoader.CacheKey(
            id = "0:app.typelauncher.coalescetest.distinctB/Activity@token-B",
            sizePx = 24,
        )
        val bitmapA = newBitmap(keyA.sizePx, Color.RED)
        val bitmapB = newBitmap(keyB.sizePx, Color.GREEN)
        val invocations = AtomicInteger(0)

        val resultA = AppIconLoader.coalesce(keyA) {
            invocations.incrementAndGet()
            bitmapA
        }
        val resultB = AppIconLoader.coalesce(keyB) {
            invocations.incrementAndGet()
            bitmapB
        }

        assertSame(bitmapA, resultA)
        assertSame(bitmapB, resultB)
        assertEquals(2, invocations.get())
    }

    @Test
    fun nullProducerResultIsNotCachedAndAllowsRetry() = runBlocking {
        val key = AppIconLoader.CacheKey(
            id = "0:app.typelauncher.coalescetest.retry/Activity@token-retry",
            sizePx = 24,
        )
        val invocations = AtomicInteger(0)
        val bitmap = newBitmap(key.sizePx, Color.BLUE)

        val miss = AppIconLoader.coalesce(key) {
            invocations.incrementAndGet()
            null
        }
        assertEquals(null, miss)

        // A subsequent call must reach the producer again — null results
        // mustn't poison the cache or stick in the in-flight map.
        val hit = AppIconLoader.coalesce(key) {
            invocations.incrementAndGet()
            bitmap
        }
        assertSame(bitmap, hit)
        assertEquals(2, invocations.get())
    }

    @Test
    fun evictDuringFlightSkipsCachePutAndForcesNextLoadToReResolve() = runBlocking {
        val sizePx = 24
        val packageName = "app.typelauncher.coalescetest.evict"
        // Build the InstalledApp through the same path production uses so the
        // resulting `iconCacheId` matches what `evict(packageName, user)` will
        // scan for. Using a hand-crafted key would risk drifting from the real
        // prefix scheme (`${user.hashCode()}:component@token`).
        val app = InstalledApp(
            name = "EvictDuringFlight",
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(
                ComponentName(packageName, "$packageName.LaunchActivity"),
            ),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
            iconCacheToken = "100",
        )
        val key = AppIconLoader.CacheKey(id = app.iconCacheId, sizePx = sizePx)
        val staleBitmap = newBitmap(sizePx, Color.YELLOW)
        val freshBitmap = newBitmap(sizePx, Color.GREEN)
        val invocations = AtomicInteger(0)
        val staleStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        // First call: producer starts, then parks. While parked, evict() runs and
        // detaches our in-flight slot. When the producer finally returns, the
        // identity check in `createInFlight` must skip `cache.put` so the stale
        // bitmap doesn't repopulate the LRU after the eviction.
        val staleLoad = async(Dispatchers.Default) {
            AppIconLoader.coalesce(key) {
                invocations.incrementAndGet()
                staleStarted.complete(Unit)
                release.await()
                staleBitmap
            }
        }
        staleStarted.await()
        AppIconLoader.evict(packageName, app.user)
        release.complete(Unit)

        // The orphaned deferred still delivers its bitmap to its existing awaiter
        // (we can't change that without disrupting the caller), but the LRU must
        // be empty so the next render forces a fresh resolve.
        assertSame(staleBitmap, staleLoad.await())
        assertNull(
            "evict during flight must prevent the stale bitmap from landing in the cache",
            AppIconLoader.cached(key.id, key.sizePx),
        )

        // A subsequent load creates a brand-new deferred and runs its producer.
        val freshLoad = AppIconLoader.coalesce(key) {
            invocations.incrementAndGet()
            freshBitmap
        }
        assertSame(freshBitmap, freshLoad)
        assertEquals(2, invocations.get())
        assertSame(freshBitmap, AppIconLoader.cached(key.id, key.sizePx))
    }

    private fun newBitmap(sizePx: Int, color: Int): ImageBitmap =
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()
}
