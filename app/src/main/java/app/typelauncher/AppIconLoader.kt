package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

internal object AppIconLoader {
    // Roughly enough for ~150 personal-profile icons rendered at xxhdpi 56dp (168px → 113KB)
    // across two distinct sizes (text-list 40dp and dock/icon-only). The byte budget keeps
    // per-size caching from blowing up RAM on high-density displays where 80dp icons can
    // reach 410KB each.
    private const val CACHE_BYTE_BUDGET = 24 * 1024 * 1024
    private const val ARGB_8888_BYTES_PER_PIXEL = 4

    // How often to flush the cache hit/miss counters into a LauncherDebugLog event so
    // they show up as Crashlytics breadcrumbs and (in debug builds) logcat. 50 keeps
    // the cold-start picture roughly chronological while staying out of the way during
    // steady-state usage.
    private const val CACHE_STATS_LOG_INTERVAL = 50

    internal data class CacheKey(val id: String, val sizePx: Int)

    private val cache = object : LruCache<CacheKey, ImageBitmap>(CACHE_BYTE_BUDGET) {
        override fun sizeOf(key: CacheKey, value: ImageBitmap): Int =
            (value.width * value.height * ARGB_8888_BYTES_PER_PIXEL).coerceAtLeast(1)
    }

    private val cacheHits = AtomicInteger()
    private val cacheMisses = AtomicInteger()

    // Coalesces concurrent loads of the same `CacheKey`. The first miss creates the
    // `Deferred`; subsequent misses await that same one and skip the duplicate
    // resolve+rasterize pass. The deferred runs in `loaderScope` so a single caller
    // cancelling (e.g. its `LaunchedEffect` going away) doesn't tear down the work
    // others are still waiting on — the bitmap still lands in the LRU for next time.
    //
    // `inFlight` is guarded by a JVM monitor (not a `Mutex`) because `evict` needs to
    // detach matching entries from non-suspending `LauncherApps.Callback` paths, and
    // every critical section here is just a map operation — no suspension while held.
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val inFlightLock = Any()
    private val inFlight = mutableMapOf<CacheKey, Deferred<ImageBitmap?>>()

    fun cached(id: String, sizePx: Int): ImageBitmap? {
        val result = cache.get(CacheKey(id, sizePx))
        recordCacheLookup(hit = result != null)
        return result
    }

    fun cached(app: InstalledApp, sizePx: Int): ImageBitmap? = cached(app.iconCacheId, sizePx)

    suspend fun load(context: Context, app: InstalledApp, sizePx: Int): ImageBitmap? {
        val key = CacheKey(app.iconCacheId, sizePx)
        cache.get(key)?.let { return it }
        val appContext = context.applicationContext
        return coalesce(key) { performLoad(appContext, app, sizePx) }
    }

    /**
     * Routes a load through the in-flight map so concurrent callers for the same
     * [key] share a single invocation of [producer]. Visible to tests so they can
     * exercise the coalescing path without going through `resolve` / `LauncherApps`.
     */
    internal suspend fun coalesce(
        key: CacheKey,
        producer: suspend () -> ImageBitmap?,
    ): ImageBitmap? {
        cache.get(key)?.let { return it }
        val deferred = synchronized(inFlightLock) {
            // Re-check under the lock: another caller may have completed between
            // our cache miss and now, populating the LRU.
            cache.get(key)?.let { return it }
            inFlight[key] ?: createInFlight(key, producer)
        }
        return deferred.await()
    }

    private fun createInFlight(
        key: CacheKey,
        producer: suspend () -> ImageBitmap?,
    ): Deferred<ImageBitmap?> {
        // `self` is captured by the async block and read on completion to compare
        // against `inFlight[key]`. `evict` may have replaced or removed our slot
        // mid-flight (e.g. a work-profile package event during a load whose
        // resolved drawable is now stale); the identity check ensures we only
        // populate the LRU when our deferred is still the one callers are
        // awaiting through `inFlight`. The assignment runs under
        // `inFlightLock` (held by the caller in `coalesce`), and every read of
        // `self` inside the async block is also under `inFlightLock`, so the
        // happens-before edge for the lateinit write is established by the
        // monitor.
        lateinit var self: Deferred<ImageBitmap?>
        self = loaderScope.async {
            try {
                producer()?.also { bitmap ->
                    synchronized(inFlightLock) {
                        if (inFlight[key] === self) cache.put(key, bitmap)
                    }
                }
            } finally {
                synchronized(inFlightLock) {
                    if (inFlight[key] === self) inFlight.remove(key)
                }
            }
        }
        inFlight[key] = self
        return self
    }

    private suspend fun performLoad(
        context: Context,
        app: InstalledApp,
        sizePx: Int,
    ): ImageBitmap? = traceBlock("app_icon_load") { trace ->
        val resolveStart = SystemClock.elapsedRealtime()
        val drawable = withContext(Dispatchers.IO) { resolve(context, app) }
        trace.incrementMetric("resolve_ms", SystemClock.elapsedRealtime() - resolveStart)
        if (drawable == null) {
            trace.setAttribute("result", "drawable_missing")
            return@traceBlock null
        }
        val bitmapStart = SystemClock.elapsedRealtime()
        val bitmap = withContext(Dispatchers.Default) {
            drawable.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
        }
        trace.incrementMetric("bitmap_ms", SystemClock.elapsedRealtime() - bitmapStart)
        trace.setAttribute("result", "success")
        bitmap
    }

    fun put(id: String, sizePx: Int, bitmap: ImageBitmap) {
        cache.put(CacheKey(id, sizePx), bitmap)
    }

    fun cacheSnapshot(): Map<CacheKey, ImageBitmap> = cache.snapshot()

    /**
     * Drops every cached bitmap that belongs to (`packageName`, `user`) so the next render
     * forces a fresh `resolve` instead of returning a stale entry.
     *
     * Why this is necessary even though `iconCacheToken` already keys on the package's
     * `lastUpdateTime`: the token is derived from the personal-profile `PackageManager`,
     * which never observes a work-profile-only update (we have no `INTERACT_ACROSS_USERS`
     * permission to read the work profile's `PackageInfo` directly). A stale unbadged
     * work-app bitmap — e.g. one that landed in the cache during work-profile boot before
     * the badge resource was ready — would therefore stay pinned under the same cache key
     * forever. The `LauncherApps.Callback` package events are per-(packageName, user), so
     * eviction at that boundary catches both the personal and work refresh paths cleanly.
     */
    fun evict(packageName: String, user: UserHandle) {
        val userPrefix = "${user.hashCode()}:"
        val componentPrefix = "$userPrefix$packageName/"
        val packageOnly = "$userPrefix$packageName"
        val packageWithToken = "$packageOnly@"
        fun matches(id: String): Boolean =
            id.startsWith(componentPrefix) ||
                id.startsWith(packageWithToken) ||
                id == packageOnly
        // Order matters: detach in-flight loads under the lock first so any
        // concurrent producer completion sees its slot is no longer current
        // and skips its `cache.put`. Then clear the LRU under the same lock,
        // which also catches a `cache.put` that an async block had already
        // performed before we acquired the lock — without holding the lock
        // across the cache clear, that put could land between our snapshot
        // and our detach, leaving a stale bitmap pinned past the eviction.
        synchronized(inFlightLock) {
            inFlight.keys.filter { matches(it.id) }.toList().forEach { inFlight.remove(it) }
            cache.snapshot().keys.filter { matches(it.id) }.forEach { cache.remove(it) }
        }
    }

    private fun recordCacheLookup(hit: Boolean) {
        val hits: Int
        val misses: Int
        if (hit) {
            hits = cacheHits.incrementAndGet()
            misses = cacheMisses.get()
        } else {
            hits = cacheHits.get()
            misses = cacheMisses.incrementAndGet()
        }
        val total = hits + misses
        if (total % CACHE_STATS_LOG_INTERVAL == 0) {
            LauncherDebugLog.event("AppIconLoader cache hits=$hits misses=$misses total=$total")
        }
    }

    private fun resolve(context: Context, app: InstalledApp): Drawable? {
        val component = app.launchIntent.component ?: return null
        return if (app.launchWithLauncherApps) {
            // getBadgedIcon delegates to PackageManager.getUserBadgedIcon for
            // managed-profile activities, so on a Pixel the work icon comes
            // back with the system blue-briefcase already composited in.
            // Personal-profile activities pass through unchanged.
            context.getSystemService<LauncherApps>()
                ?.getActivityList(component.packageName, app.user)
                ?.firstOrNull { activity -> activity.componentName == component }
                ?.getBadgedIcon(0)
        } else {
            try {
                val raw = context.packageManager.getActivityIcon(component)
                if (app.isWorkApp) {
                    context.packageManager.getUserBadgedIcon(raw, app.user)
                } else {
                    raw
                }
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }
}

@Composable
internal fun rememberAppIconBitmap(app: InstalledApp, sizeDp: Dp): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(1)
    val cacheId = app.iconCacheId
    var bitmap by remember(cacheId, sizePx) { mutableStateOf(AppIconLoader.cached(app, sizePx)) }
    LaunchedEffect(cacheId, sizePx) {
        if (bitmap == null) {
            bitmap = AppIconLoader.load(context, app, sizePx)
        }
    }
    return bitmap
}
