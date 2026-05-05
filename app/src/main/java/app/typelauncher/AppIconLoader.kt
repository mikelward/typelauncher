package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
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
import kotlinx.coroutines.Dispatchers
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

    // Sub-id suffix for the work-profile badge overlay. The base icon stays
    // cached under the plain `iconCacheId`; the system badge (briefcase on
    // Pixel) — applied to a transparent canvas via `getUserBadgedIcon` so
    // only the badge graphic ends up rendered — sits under `id@badge`.
    // Splitting the layers lets `AppIcon` paint the disambiguator strip
    // *between* the icon and the badge, preserving briefcase visibility on
    // work apps even when a regional bar covers the bottom of the icon.
    // The same suffix shows up in `evict`'s prefix sweep, so per-(package,
    // user) eviction naturally drops the matching badge entry.
    private const val BADGE_CACHE_SUFFIX = "@badge"

    private val cache = object : LruCache<CacheKey, ImageBitmap>(CACHE_BYTE_BUDGET) {
        override fun sizeOf(key: CacheKey, value: ImageBitmap): Int =
            (value.width * value.height * ARGB_8888_BYTES_PER_PIXEL).coerceAtLeast(1)
    }

    private val cacheHits = AtomicInteger()
    private val cacheMisses = AtomicInteger()

    fun cached(id: String, sizePx: Int): ImageBitmap? {
        val result = cache.get(CacheKey(id, sizePx))
        recordCacheLookup(hit = result != null)
        return result
    }

    fun cached(app: InstalledApp, sizePx: Int): ImageBitmap? = cached(app.iconCacheId, sizePx)

    fun cachedBadge(app: InstalledApp, sizePx: Int): ImageBitmap? =
        cache.get(CacheKey(app.iconCacheId + BADGE_CACHE_SUFFIX, sizePx))

    suspend fun load(context: Context, app: InstalledApp, sizePx: Int): ImageBitmap? {
        val key = CacheKey(app.iconCacheId, sizePx)
        val cachedBase = cache.get(key)
        if (cachedBase != null && (!app.isWorkApp || cachedBadge(app, sizePx) != null)) {
            return cachedBase
        }
        return traceBlock("app_icon_load") { trace ->
            val resolveStart = SystemClock.elapsedRealtime()
            val resolved = withContext(Dispatchers.IO) { resolve(context, app) }
            trace.incrementMetric("resolve_ms", SystemClock.elapsedRealtime() - resolveStart)
            if (resolved == null) {
                trace.setAttribute("result", "drawable_missing")
                return@traceBlock null
            }
            val bitmapStart = SystemClock.elapsedRealtime()
            val baseBitmap = cachedBase ?: withContext(Dispatchers.Default) {
                resolved.base.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
            }
            val badgeBitmap = resolved.badge?.let { badge ->
                withContext(Dispatchers.Default) {
                    badge.toBitmap(width = sizePx, height = sizePx).asImageBitmap()
                }
            }
            trace.incrementMetric("bitmap_ms", SystemClock.elapsedRealtime() - bitmapStart)
            trace.setAttribute("result", "success")
            cache.put(key, baseBitmap)
            if (badgeBitmap != null) {
                cache.put(CacheKey(app.iconCacheId + BADGE_CACHE_SUFFIX, sizePx), badgeBitmap)
            }
            baseBitmap
        }
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
        val toRemove = cache.snapshot().keys.filter { key ->
            key.id.startsWith(componentPrefix) ||
                key.id.startsWith(packageWithToken) ||
                key.id == packageOnly
        }
        toRemove.forEach { cache.remove(it) }
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

    private data class ResolvedIcon(val base: Drawable, val badge: Drawable?)

    private fun resolve(context: Context, app: InstalledApp): ResolvedIcon? {
        val component = app.launchIntent.component ?: return null
        return if (app.launchWithLauncherApps) {
            // `LauncherActivityInfo.getIcon(0)` returns the unbadged drawable
            // for both personal and managed-profile activities. The work
            // briefcase used to be picked up implicitly via `getBadgedIcon`
            // here; it now arrives via `extractBadgeOverlay` below as a
            // separate transparent-canvas drawable so the disambiguator
            // strip can render between icon and badge.
            val base = context.getSystemService<LauncherApps>()
                ?.getActivityList(component.packageName, app.user)
                ?.firstOrNull { activity -> activity.componentName == component }
                ?.getIcon(0)
                ?: return null
            ResolvedIcon(base = base, badge = extractBadgeOverlay(context, app))
        } else {
            try {
                val raw = context.packageManager.getActivityIcon(component)
                ResolvedIcon(base = raw, badge = extractBadgeOverlay(context, app))
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    // Renders the system profile badge ("blue briefcase" on Pixel) on its own
    // by feeding `getUserBadgedIcon` a fully transparent canvas. The framework
    // composites the badge over the source drawable at its system-defined
    // corner (bottom-end on every OEM we care about), so a transparent source
    // yields a drawable that is empty except for the badge graphic — exactly
    // what we want to layer on top of the disambiguator strip.
    private fun extractBadgeOverlay(context: Context, app: InstalledApp): Drawable? {
        if (!app.isWorkApp) return null
        val transparent = ColorDrawable(AndroidColor.TRANSPARENT)
        return context.packageManager.getUserBadgedIcon(transparent, app.user)
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

@Composable
internal fun rememberAppBadgeBitmap(app: InstalledApp, sizeDp: Dp): ImageBitmap? {
    if (!app.isWorkApp) return null
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(1)
    val cacheId = app.iconCacheId
    // Driven by its own LaunchedEffect rather than piggy-backing on the base
    // bitmap's state because cold-start hydration re-puts the *same* base
    // reference in the cache, so the base StateFlow never transitions and a
    // bystanding remember() keyed on base wouldn't re-read the badge cache.
    // `AppIconLoader.load` is idempotent and short-circuits when both base
    // and badge are cached, so the parallel call here doesn't double-work
    // with `rememberAppIconBitmap` once both LaunchedEffects have run.
    var badge by remember(cacheId, sizePx) { mutableStateOf(AppIconLoader.cachedBadge(app, sizePx)) }
    LaunchedEffect(cacheId, sizePx) {
        if (badge == null) {
            AppIconLoader.load(context, app, sizePx)
            badge = AppIconLoader.cachedBadge(app, sizePx)
        }
    }
    return badge
}
