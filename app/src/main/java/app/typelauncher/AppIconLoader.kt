package app.typelauncher

import android.content.Context
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.runtime.Composable
import com.caverock.androidsvg.SVG
import java.io.File
import java.time.LocalDate
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
        // User-supplied override wins over the system icon. Decoded directly
        // to a sized bitmap (no Drawable wrapper) so an SVG renders crisply
        // at the requested size instead of being rasterised at its document
        // size and then scaled, and so a large source image is sub-sampled
        // straight to roughly the target dimensions.
        app.customIconPath?.let { path ->
            val file = File(path)
            if (file.isFile) {
                val overrideStart = SystemClock.elapsedRealtime()
                val override = withContext(Dispatchers.IO) { decodeOverrideBitmap(file, sizePx) }
                trace.incrementMetric("override_ms", SystemClock.elapsedRealtime() - overrideStart)
                if (override != null) {
                    trace.setAttribute("result", "override")
                    return@traceBlock override.asImageBitmap()
                }
                trace.setAttribute("override_result", "decode_failed")
            } else {
                trace.setAttribute("override_result", "missing_file")
            }
        }
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

    private fun decodeOverrideBitmap(file: File, sizePx: Int): Bitmap? = try {
        if (file.extension.equals("svg", ignoreCase = true)) {
            renderSvgToBitmap(file, sizePx)
        } else {
            decodeRasterToSquareBitmap(file, sizePx)
        }
    } catch (t: Throwable) {
        LauncherDebugLog.event(
            "AppIconLoader override decode failed path=${file.name} err=${t.javaClass.simpleName}",
        )
        null
    }

    private fun renderSvgToBitmap(file: File, sizePx: Int): Bitmap {
        val svg = file.inputStream().use { input -> SVG.getFromInputStream(input) }
        // Override the SVG's intrinsic dimensions so `renderToCanvas` scales
        // the document into the entire `sizePx × sizePx` target instead of
        // honouring whatever size the original document declared. Use the
        // explicit float setters because AndroidSVG also overloads them with
        // a `String` variant — Kotlin property syntax becomes ambiguous.
        svg.setDocumentWidth(sizePx.toFloat())
        svg.setDocumentHeight(sizePx.toFloat())
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap))
        return bitmap
    }

    private fun decodeRasterToSquareBitmap(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, sizePx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        if (raw.width == sizePx && raw.height == sizePx) return raw
        val scaled = Bitmap.createScaledBitmap(raw, sizePx, sizePx, /* filter = */ true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun computeInSampleSize(srcWidth: Int, srcHeight: Int, targetPx: Int): Int {
        if (targetPx <= 0) return 1
        var sample = 1
        // Halve until both axes are within ~2× of the target. Powers-of-two
        // keep `BitmapFactory`'s decoder on its fast path; any residual
        // mismatch is cleaned up by `createScaledBitmap` afterwards.
        while ((srcHeight / sample) > targetPx * 2 && (srcWidth / sample) > targetPx * 2) {
            sample *= 2
        }
        return sample
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
        // Date-aware calendar apps (Google Calendar et al.) ship 31 per-day icon
        // drawables and expect the launcher to pick today's; the system's plain
        // activity icon is the static default (Google Calendar's depicts the
        // 31st). Try that selection first and fall back to the default icon when
        // the app doesn't expose the metadata or anything goes wrong.
        if (app.packageName in DYNAMIC_CALENDAR_PACKAGES) {
            resolveDynamicCalendarIcon(context, app, component)?.let { return it }
        }
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

    /**
     * Resolves the per-day launcher icon a date-aware calendar app advertises,
     * mirroring AOSP Launcher3's `IconProvider`. The app declares a
     * `<packageName>.dynamic_icons` meta-data entry pointing to an array of 31
     * drawables — one per day of the month — and the launcher picks today's.
     * Without this, the activity's default icon is a single static day (Google
     * Calendar's depicts the 31st), which is why an un-aware launcher shows a
     * stale date.
     *
     * Returns null — so [resolve] falls back to the default icon — when the app
     * exposes no such metadata, the array or day drawable can't be found, or the
     * package's resources aren't readable (e.g. a work-profile calendar the
     * personal `PackageManager` can't reach). Runs on the IO dispatcher via
     * [resolve], so the `PackageManager` / resource lookups are off the main
     * thread.
     */
    private fun resolveDynamicCalendarIcon(
        context: Context,
        app: InstalledApp,
        component: ComponentName,
    ): Drawable? = try {
        val packageManager = context.packageManager
        val key = dynamicCalendarIconsMetadataKey(component.packageName)
        val arrayResId = findDynamicCalendarArrayResId(packageManager, component, key)
        if (arrayResId == 0) {
            LauncherDebugLog.event(
                "dynamicCalendarIcon: no '$key' metadata for ${component.packageName} " +
                    "iconKeys=${dynamicCalendarMetadataKeysForLog(packageManager, component)}",
            )
            null
        } else {
            val resources = packageManager.getResourcesForApplication(component.packageName)
            val dayOfMonth = LocalDate.now().dayOfMonth
            val dayIconResId = resources.obtainTypedArray(arrayResId).use { dayIcons ->
                val index = dynamicCalendarDayIndex(LocalDate.now())
                if (index in 0 until dayIcons.length()) dayIcons.getResourceId(index, 0) else 0
            }
            val raw = if (dayIconResId == 0) {
                null
            } else {
                resources.getDrawableForDensity(
                    dayIconResId,
                    context.resources.displayMetrics.densityDpi,
                    null,
                )
            }
            LauncherDebugLog.event(
                "dynamicCalendarIcon: ${component.packageName} day=$dayOfMonth " +
                    "arrayResId=$arrayResId dayResId=$dayIconResId resolved=${raw != null}",
            )
            when {
                raw == null -> null
                app.isWorkApp -> packageManager.getUserBadgedIcon(raw, app.user)
                else -> raw
            }
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    } catch (_: Resources.NotFoundException) {
        null
    }

    /**
     * Finds the dynamic-calendar icon array resource id declared under [key],
     * checking the launcher [component], then the application element, then every
     * activity / activity-alias in the package. Google Calendar declares the
     * metadata on its launcher **activity-alias**, and `getActivityInfo` on an
     * alias does not reliably surface the alias's own metadata (it resolves
     * toward the target activity, which has none) — so the package-wide scan is
     * the path that actually finds it. Returns 0 when no component declares it.
     */
    internal fun findDynamicCalendarArrayResId(
        packageManager: PackageManager,
        component: ComponentName,
        key: String,
    ): Int {
        runCatching {
            packageManager.getActivityInfo(component, PackageManager.GET_META_DATA).metaData?.getInt(key, 0)
        }.getOrNull()?.takeIf { it != 0 }?.let { return it }
        runCatching {
            packageManager.getApplicationInfo(component.packageName, PackageManager.GET_META_DATA)
                .metaData?.getInt(key, 0)
        }.getOrNull()?.takeIf { it != 0 }?.let { return it }
        runCatching {
            packageManager
                .getPackageInfo(
                    component.packageName,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA,
                )
                .activities.orEmpty()
                .firstNotNullOfOrNull { activity -> activity.metaData?.getInt(key, 0)?.takeIf { it != 0 } }
        }.getOrNull()?.let { return it }
        return 0
    }

    // Diagnostic only: the metadata keys (filtered to icon/calendar/dynamic) the
    // package actually declares, logged when the lookup misses so a future stale
    // report shows what the calendar app exposes instead of guessing.
    private fun dynamicCalendarMetadataKeysForLog(
        packageManager: PackageManager,
        component: ComponentName,
    ): String = runCatching {
        packageManager
            .getPackageInfo(component.packageName, PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA)
            .activities.orEmpty()
            .flatMap { activity -> activity.metaData?.keySet().orEmpty() }
            .filter { it.contains("icon", true) || it.contains("calendar", true) || it.contains("dynamic", true) }
            .distinct()
            .joinToString()
    }.getOrDefault("<unavailable>")
}

// Meta-data key (the package name plus this suffix) a date-aware calendar app
// declares to advertise its array of 31 per-day icon drawables, matching AOSP
// Launcher3 — e.g. `com.google.android.calendar.dynamic_icons`.
private const val CALENDAR_DYNAMIC_ICONS_METADATA_SUFFIX = ".dynamic_icons"

internal fun dynamicCalendarIconsMetadataKey(packageName: String): String =
    "$packageName$CALENDAR_DYNAMIC_ICONS_METADATA_SUFFIX"

// Index into the 31-entry per-day icon array: day 1 maps to index 0. Months
// with fewer than 31 days simply never request the unused trailing entries.
internal fun dynamicCalendarDayIndex(date: LocalDate): Int = date.dayOfMonth - 1

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
