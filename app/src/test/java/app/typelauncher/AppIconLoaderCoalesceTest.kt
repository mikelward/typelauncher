package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Color
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

    private fun newBitmap(sizePx: Int, color: Int): ImageBitmap =
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(color) }
            .asImageBitmap()
}
