package app.typelauncher

import android.content.Context
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.runtime.Composable
import com.caverock.androidsvg.SVG
import java.io.File
import java.time.LocalDate
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.content.getSystemService
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

    // Mirrors `DockSettingsStore.iconTheme`; the ViewModel writes it on init
    // and on every change. A @Volatile mirror (rather than threading the theme
    // through every load call) keeps the loader's call surface unchanged and is
    // read once per `performLoad` on the loader scope.
    @Volatile
    var iconTheme: IconTheme = IconTheme.Default

    // Mirrors the launcher's *resolved* dark/light appearance — the Settings
    // `Theme` mode (System / Light / Dark) combined with the device night
    // mode, the same resolution `MainActivity.applyEdgeToEdgeForThemeMode`
    // and `TypeLauncherTheme` apply. Themed (monochrome) plates must follow
    // this value, not `Configuration.UI_MODE_NIGHT_MASK` alone: with `Theme`
    // pinned to `Dark` on a light-mode device (or vice versa) the night mask
    // disagrees with what the launcher actually renders, and plates derived
    // from it sit light-on-dark. Seeded via [setThemedIconPalette]
    // from the ViewModel (init and every theme-mode change) and from
    // MainActivity whenever it re-resolves — including the recreation after
    // a system night-mode configuration change while `Theme` is `System`.
    @Volatile
    var themedIconsUseDarkPalette: Boolean = false
        private set

    // Mirrors the *resolved* plate/glyph pair themed tiles are rasterized
    // with — not just the dark/light flag. The dynamic system palette can
    // change while the resolved darkness stays the same (a wallpaper or
    // dynamic-color change recreates the activity with the same isDark), so
    // change detection must compare the actual colors: cached monochrome
    // tiles bake the accent in, and the cache key carries no color component.
    @Volatile
    var themedIconColors: IconNormalizer.ThemedIconColors? = null
        private set

    /**
     * Resolves the plate/glyph pair for [isDark] from [context] and updates
     * the mirrors; when the resolved pair actually changes while the
     * Monochrome icon theme is active, drops every cached tile so the next
     * render re-rasterizes each themed plate with the new colors. Comparing
     * the pair (not just [isDark]) is what catches a wallpaper / dynamic-color
     * change, which recreates the activity with the same resolved darkness
     * but new accent colors. No eviction when the pair is unchanged or the
     * Default theme is active — no cached tile carries a themed plate there.
     */
    fun setThemedIconPalette(context: Context, isDark: Boolean) {
        setThemedIconPalette(resolveThemedIconColors(context, isDark), isDark)
    }

    /** Color-injecting overload backing [setThemedIconPalette]; visible so tests can exercise an accent change Robolectric's fixed system palette can't produce. */
    internal fun setThemedIconPalette(colors: IconNormalizer.ThemedIconColors, isDark: Boolean) {
        themedIconsUseDarkPalette = isDark
        if (themedIconColors == colors) return
        themedIconColors = colors
        if (iconTheme == IconTheme.Monochrome) {
            evictAll()
        }
    }

    // Bumped by `evictAll` and `evict` so compositions holding an already-loaded
    // bitmap (`rememberAppIconBitmap`'s `remember` keys don't otherwise change)
    // drop their stale state and re-read the cache — without it, an icon theme
    // change or a per-package eviction would only repaint icons whose composables
    // leave and re-enter the tree. Permanently composed surfaces (the dock) never
    // do, so a work-profile icon evicted after profile boot would stay stale or
    // blank forever: for work apps the `iconCacheId` provably cannot change (see
    // `evict`'s KDoc), leaving this counter as the only re-key signal.
    private val cacheGeneration = mutableIntStateOf(0)

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

    /**
     * The plate/glyph pair to render every icon with under the Monochrome theme,
     * or null when icons render as the app designed them. Non-null only on API
     * 33+ (below that the adaptive monochrome layer — and the whole themed path —
     * doesn't exist, so the colors would never be consumed). Prefers the mirrored
     * pair seeded before composition (ViewModel init, MainActivity onCreate);
     * the fallback derivation keeps a load that somehow races the first seed
     * from rendering a stale plate.
     */
    private fun themedColorsOrNull(context: Context): IconNormalizer.ThemedIconColors? =
        if (iconTheme == IconTheme.Monochrome && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            themedIconColors ?: resolveThemedIconColors(context, themedIconsUseDarkPalette)
        } else {
            null
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
                    val themedColors = themedColorsOrNull(context)
                    if (themedColors == null) {
                        trace.setAttribute("result", "override")
                        return@traceBlock override.asImageBitmap()
                    }
                    // Under the Monochrome theme the override is forced monochrome
                    // too, so an app the user edited matches the rest of the
                    // forced-monochrome grid instead of staying full-color; its
                    // alpha silhouette becomes the glyph. The chosen art returns
                    // as soon as the theme switches back to Default.
                    trace.setAttribute("result", "override_themed")
                    return@traceBlock withContext(Dispatchers.Default) {
                        IconNormalizer.normalizeToTile(
                            BitmapDrawable(context.resources, override),
                            sizePx,
                            app.packageName,
                            themedColors,
                        ).asImageBitmap()
                    }
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
            // Normalize to a full-bleed tile so padded/legacy icons fill the
            // launcher's rounded-rectangle shape instead of floating on the
            // surface plate as a gray square. Adaptive icons pass through
            // edge-to-edge; everything else is re-seated on a dominant-color
            // plate. See IconNormalizer.
            //
            // The work-profile badge is NOT baked in here. The launcher clips
            // this tile to a circle, and a corner-placed briefcase composited
            // into the bitmap would be sliced off by that clip. Instead the
            // badge is loaded separately (see loadWorkBadge) and drawn as an
            // overlay outside the circular clip, so it stays fully visible.
            // Themed rendering only activates on API 33+, where the adaptive
            // monochrome layer exists; below that the colors would never be
            // consumed, so themedColorsOrNull returns null and rendering is
            // unchanged.
            IconNormalizer.normalizeToTile(drawable, sizePx, app.packageName, themedColorsOrNull(context)).asImageBitmap()
        }
        trace.incrementMetric("bitmap_ms", SystemClock.elapsedRealtime() - bitmapStart)
        trace.setAttribute("result", "success")
        bitmap
    }

    /**
     * Loads the standalone work-profile badge overlay for [user] at [sizePx] —
     * a transparent tile with only the OEM badge (the blue briefcase on a
     * Pixel) composited into its corner. The launcher draws this on top of the
     * circular icon *outside* the circular clip, so the briefcase stays fully
     * visible instead of being sliced off where the square corner falls beyond
     * the circle.
     *
     * Cached per (user, size) rather than per app: the badge is the same image
     * for every work app in a profile, so all of them share one entry. The
     * cache id can't collide with an app's — app ids are `<userHash>:<...>`
     * while this is `workbadge:<userHash>`.
     */
    suspend fun loadWorkBadge(context: Context, user: UserHandle, sizePx: Int): ImageBitmap? {
        val key = CacheKey(workBadgeCacheId(user), sizePx)
        cache.get(key)?.let { return it }
        val appContext = context.applicationContext
        return coalesce(key) { performWorkBadgeLoad(appContext, user, sizePx) }
    }

    fun cachedWorkBadge(user: UserHandle, sizePx: Int): ImageBitmap? =
        cached(workBadgeCacheId(user), sizePx)

    private fun workBadgeCacheId(user: UserHandle): String = "workbadge:${user.hashCode()}"

    /**
     * Composites the work-profile badge onto a fully transparent [sizePx] tile,
     * yielding an overlay that is just the badge in its OS corner. Returns null
     * when no badge is applied — a personal user, a shadow PackageManager under
     * test, or a work profile whose badge resource isn't ready yet. A null
     * result is deliberately not cached (see [createInFlight]), so a later load
     * retries once the badge becomes available.
     */
    private fun performWorkBadgeLoad(context: Context, user: UserHandle, sizePx: Int): ImageBitmap? {
        val out = try {
            val transparent = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val badged = context.packageManager
                .getUserBadgedIcon(BitmapDrawable(context.resources, transparent), user)
            // Rasterize whatever getUserBadgedIcon returned into a fresh tile.
            // Instance identity is NOT a reliable "no badge" signal: some OEM
            // implementations badge the backing bitmap in place and hand back
            // the SAME drawable, so a same-instance result can still carry the
            // briefcase. Drawing it out and checking pixels handles both that
            // path and the new-composite-drawable path uniformly.
            Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { tile ->
                badged.setBounds(0, 0, sizePx, sizePx)
                badged.draw(Canvas(tile))
            }
        } catch (exception: Resources.NotFoundException) {
            // getUserBadgedIcon can throw while a work profile is still booting;
            // returning null keeps it out of the cache so a later load retries.
            LauncherDebugLog.warning("performWorkBadgeLoad: badge unavailable for user=$user", exception)
            return null
        }
        // No badge composited (personal user, or a shadow PackageManager under
        // test) leaves the tile fully transparent — detect that by pixel content
        // rather than drawable identity and skip caching an empty overlay.
        return if (hasVisiblePixel(out)) out.asImageBitmap() else null
    }

    /** True when any pixel in [bitmap] has a non-zero alpha channel. */
    private fun hasVisiblePixel(bitmap: Bitmap): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { (it ushr 24) != 0 }
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
     * Compose-observable counter [evictAll] and [evict] bump;
     * [rememberAppIconBitmap] and [rememberWorkBadgeOverlay] key on it so live
     * compositions reload after an eviction.
     */
    internal val cacheGenerationValue: Int
        get() = cacheGeneration.intValue

    /**
     * Drops every cached bitmap that belongs to (`packageName`, `user`) so the next render
     * forces a fresh `resolve` instead of returning a stale entry.
     *
     * Why this is necessary even though `iconCacheToken` already keys on the package's
     * `lastUpdateTime`: the token is derived from the personal-profile `PackageManager`,
     * which never observes a work-profile-only update (we have no `INTERACT_ACROSS_USERS`
     * permission to read the work profile's `PackageInfo` directly). A stale work-app
     * bitmap — e.g. one that landed in the cache during work-profile boot before the icon
     * resource was ready — would therefore stay pinned under the same cache key forever.
     * The `LauncherApps.Callback` package events are per-(packageName, user), so eviction
     * at that boundary catches both the personal and work refresh paths cleanly.
     *
     * The shared `workbadge:<userHash>` overlay entry is intentionally left alone: it is
     * keyed on the user, not the package, and the briefcase art never changes per package.
     * A null overlay (badge resource not ready at boot) is never cached, so it self-heals
     * on the next load without needing eviction here.
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
        // Re-key live compositions, same as `evictAll`: without this, an icon
        // whose composable never leaves the tree (the dock) keeps painting the
        // bitmap it already holds, and for work-profile apps no other key can
        // ever change (see KDoc above) — the post-boot refresh this eviction
        // exists for would never reach the screen. Unaffected icons re-read the
        // cache and hit, so the cost is one lookup per visible icon on a rare
        // per-package event, not a reload storm.
        cacheGeneration.intValue++
    }

    /**
     * Drops every cached bitmap and detaches every in-flight load, forcing the
     * next render of every icon through a fresh `resolve` + rasterize pass.
     * Used when a setting that changes how every tile is rasterized changes
     * (the icon theme), since the cache key carries no rendering-mode component.
     * Same locking rationale as [evict]: detach in-flight producers and clear
     * the LRU under one critical section so no completing producer can re-pin a
     * stale bitmap.
     */
    fun evictAll() {
        synchronized(inFlightLock) {
            inFlight.clear()
            cache.evictAll()
        }
        cacheGeneration.intValue++
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

    /**
     * Plate / glyph pair for themed-icon rendering, derived from the
     * launcher's resolved dark/light appearance ([isDark] — the Settings
     * `Theme` mode combined with the device night mode, not the raw night
     * mask) and (API 31+) the dynamic system palette — the same
     * neutral-on-accent pairing Pixel's themed icons use. Below API 31 the
     * dynamic palette does not exist, so fixed gray tones approximate the look.
     * Pure derivation with no mirror read, so [setThemedIconPalette] can call
     * it from MainActivity with the resolution it just computed. Internal so
     * tests can assert the palette follows the resolved theme rather than the
     * night mask.
     */
    internal fun resolveThemedIconColors(context: Context, isDark: Boolean): IconNormalizer.ThemedIconColors {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) {
                IconNormalizer.ThemedIconColors(
                    plate = context.getColor(android.R.color.system_neutral1_800),
                    glyph = context.getColor(android.R.color.system_accent1_100),
                )
            } else {
                IconNormalizer.ThemedIconColors(
                    plate = context.getColor(android.R.color.system_accent1_100),
                    glyph = context.getColor(android.R.color.system_accent1_700),
                )
            }
        } else {
            if (isDark) {
                IconNormalizer.ThemedIconColors(plate = THEMED_PLATE_DARK_FALLBACK, glyph = THEMED_GLYPH_DARK_FALLBACK)
            } else {
                IconNormalizer.ThemedIconColors(plate = THEMED_GLYPH_DARK_FALLBACK, glyph = THEMED_PLATE_DARK_FALLBACK)
            }
        }
    }

    // Fallback themed-icon tones for devices without the dynamic system palette
    // (below API 31): dark gray and off-white, swapped between plate and glyph
    // by night mode. In practice unreachable today — themed rendering requires
    // API 33 — but kept so the color derivation is total.
    private const val THEMED_PLATE_DARK_FALLBACK = 0xFF3C4043.toInt()
    private const val THEMED_GLYPH_DARK_FALLBACK = 0xFFE8EAED.toInt()

    private fun resolve(context: Context, app: InstalledApp): Drawable? {
        val component = app.launchIntent.component ?: return null
        // Date-aware calendar apps (Google Calendar et al.) ship 31 per-day icon
        // drawables and expect the launcher to pick today's; the system's plain
        // activity icon is the static default (Google Calendar's depicts the
        // 31st). Try that selection first and fall back to the default icon when
        // the app doesn't expose the metadata or anything goes wrong.
        if (app.packageName in DYNAMIC_CALENDAR_PACKAGES) {
            resolveDynamicCalendarIcon(context, component)?.let { return it }
        }
        return if (app.launchWithLauncherApps) {
            // Return the UNBADGED icon (getIcon, not getBadgedIcon): the
            // work-profile badge is loaded separately (loadWorkBadge) and drawn
            // as an overlay outside the icon's circular clip, so it stays fully
            // visible. Personal-profile activities have no badge either way.
            //
            // Guarded because this runs on the loader scope and any throwable
            // escaping the producer is re-thrown by `Deferred.await()` inside
            // every awaiting composition — an unhandled composition exception,
            // i.e. a process crash. getActivityList throws SecurityException
            // when app.user has left the caller's profile group (work profile
            // removed or disabled while this load was in flight), and getIcon
            // can throw Resources.NotFoundException while a work profile is
            // still booting. A missing icon placeholder is the right
            // degradation for both.
            try {
                context.getSystemService<LauncherApps>()
                    ?.getActivityList(component.packageName, app.user)
                    ?.firstOrNull { activity -> activity.componentName == component }
                    ?.getIcon(0)
            } catch (exception: SecurityException) {
                LauncherDebugLog.warning(
                    "resolve: LauncherApps rejected ${app.packageName} user=${app.user}",
                    exception,
                )
                null
            } catch (exception: Resources.NotFoundException) {
                LauncherDebugLog.warning(
                    "resolve: icon unavailable for ${app.packageName}",
                    exception,
                )
                null
            }
        } else {
            // Unbadged here too; the work badge is a separate overlay.
            try {
                context.packageManager.getActivityIcon(component)
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
        component: ComponentName,
    ): Drawable? = try {
        val packageManager = context.packageManager
        val key = dynamicCalendarIconsMetadataKey(component.packageName)
        val arrayResId = findDynamicCalendarArrayResId(packageManager, component, key)
        if (arrayResId == 0) {
            LauncherDebugLog.trace(
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
            LauncherDebugLog.trace(
                "dynamicCalendarIcon: ${component.packageName} day=$dayOfMonth " +
                    "arrayResId=$arrayResId dayResId=$dayIconResId resolved=${raw != null}",
            )
            // Unbadged: performLoad badges the normalized tile for work apps so
            // the badge survives IconNormalizer's crop/scale intact.
            raw
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
    // Keyed on the cache generation as well, so an eviction — full (icon theme
    // change) or per-package (package event, work-profile boot) — drops the
    // remembered bitmap and reloads instead of painting the stale pre-change
    // tile until the composable happens to leave the tree.
    val generation = AppIconLoader.cacheGenerationValue
    var bitmap by remember(cacheId, sizePx, generation) { mutableStateOf(AppIconLoader.cached(app, sizePx)) }
    LaunchedEffect(cacheId, sizePx, generation) {
        if (bitmap == null) {
            bitmap = AppIconLoader.load(context, app, sizePx)
        }
    }
    return bitmap
}

/**
 * Resolves the work-profile badge overlay for [app] at [sizeDp], or null for a
 * personal-profile app (and while the badge is still loading). The badge is the
 * standalone briefcase tile [AppIconLoader.loadWorkBadge] produces; the caller
 * draws it on top of the icon outside the circular clip so it stays uncropped.
 *
 * Hooks run unconditionally so the slot table is stable whether or not [app] is
 * a work app. Keyed on the cache generation (not just `iconCacheId`) so a
 * post-boot icon refresh re-attempts the badge load too: for a work app the
 * `iconCacheId` cannot change (its token comes from the personal-profile
 * `PackageManager` — see [AppIconLoader.evict]), so a badge that loaded null
 * during profile boot would otherwise never retry in a composable that stays
 * in the tree. The eviction the boot-time package event triggers bumps the
 * generation; a cache hit once the overlay exists, a fresh attempt if the
 * badge resource only became ready after first paint.
 */
@Composable
internal fun rememberWorkBadgeOverlay(app: InstalledApp, sizeDp: Dp): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(1)
    val cacheId = app.iconCacheId
    val isWorkApp = app.isWorkApp
    val user = app.user
    val generation = AppIconLoader.cacheGenerationValue
    var bitmap by remember(cacheId, sizePx, generation) {
        mutableStateOf(if (isWorkApp) AppIconLoader.cachedWorkBadge(user, sizePx) else null)
    }
    LaunchedEffect(cacheId, sizePx, generation) {
        if (isWorkApp && bitmap == null) {
            bitmap = AppIconLoader.loadWorkBadge(context, user, sizePx)
        }
    }
    return bitmap
}
