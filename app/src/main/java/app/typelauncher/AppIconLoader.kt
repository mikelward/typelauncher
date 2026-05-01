package app.typelauncher

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemClock
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

    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTE_BUDGET) {
        override fun sizeOf(key: String, value: ImageBitmap): Int =
            (value.width * value.height * ARGB_8888_BYTES_PER_PIXEL).coerceAtLeast(1)
    }

    private val cacheHits = AtomicInteger()
    private val cacheMisses = AtomicInteger()

    fun cached(id: String, sizePx: Int): ImageBitmap? {
        val result = cache.get(cacheKey(id, sizePx))
        recordCacheLookup(hit = result != null)
        return result
    }

    suspend fun load(context: Context, app: InstalledApp, sizePx: Int): ImageBitmap? {
        val key = cacheKey(app.id, sizePx)
        cache.get(key)?.let { return it }
        return traceBlock("app_icon_load") { trace ->
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
            cache.put(key, bitmap)
            bitmap
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

    private fun cacheKey(id: String, sizePx: Int): String = "$id:$sizePx"

    private fun resolve(context: Context, app: InstalledApp): Drawable? {
        val component = app.launchIntent.component ?: return null
        return if (app.launchWithLauncherApps) {
            context.getSystemService<LauncherApps>()
                ?.getActivityList(component.packageName, app.user)
                ?.firstOrNull { activity -> activity.componentName == component }
                ?.getIcon(0)
        } else {
            try {
                context.packageManager.getActivityIcon(component)
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
    var bitmap by remember(app.id, sizePx) { mutableStateOf(AppIconLoader.cached(app.id, sizePx)) }
    LaunchedEffect(app.id, sizePx) {
        if (bitmap == null) {
            bitmap = AppIconLoader.load(context, app, sizePx)
        }
    }
    return bitmap
}
