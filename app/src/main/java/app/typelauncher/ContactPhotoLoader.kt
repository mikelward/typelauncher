package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads and caches contact photo thumbnails for the typed-search contacts
 * section. A deliberately smaller sibling of [AppIconLoader]: photos decode
 * lazily per rendered row on the IO dispatcher (the index stores only the
 * `PHOTO_THUMBNAIL_URI` string — see [ContactResult]), land in a byte-budgeted
 * LRU, and rows render the monogram placeholder until the swap-in. There is no
 * in-flight coalescing: a contact appears at most once in the results and the
 * row renders at a single size, so concurrent same-key loads don't arise the
 * way they do for app icons shared between the dock and the list.
 *
 * Freshness: `PHOTO_THUMBNAIL_URI` is stable across photo edits, so the cache
 * key can't observe a changed photo. Instead the ViewModel calls [evictAll]
 * whenever the contact index reloads (resume, enable, permission grant) —
 * photo edits become visible on the same boundary as name edits. A decode
 * that is in flight when the eviction lands must not re-pin its result: the
 * put is generation-gated (atomically against [evictAll]), so the eviction
 * always wins. That keeps the disable path's "holds nothing in memory"
 * contract airtight — a photo decoded across the disable boundary is
 * returned to the already-composed row but never retained.
 */
internal object ContactPhotoLoader {
    // Thumbnails render at 40dp (~105px at 420dpi → ~44KB ARGB_8888); 2MB
    // holds ~45 of them — several screens of search results.
    private const val CACHE_BYTE_BUDGET = 2 * 1024 * 1024
    private const val ARGB_8888_BYTES_PER_PIXEL = 4

    private data class CacheKey(val photoUri: String, val sizePx: Int)

    private val cache = object : LruCache<CacheKey, ImageBitmap>(CACHE_BYTE_BUDGET) {
        override fun sizeOf(key: CacheKey, value: ImageBitmap): Int =
            (value.width * value.height * ARGB_8888_BYTES_PER_PIXEL).coerceAtLeast(1)
    }

    // Compose-observable eviction counter, mirroring AppIconLoader's: rows that
    // stay composed across an [evictAll] (the user is mid-typing when a resume
    // reload lands) re-key their remembered bitmap off it and reload.
    private val cacheGeneration = mutableIntStateOf(0)

    // Makes `load`'s generation-check-then-put atomic against [evictAll]'s
    // clear-then-bump, so a decode completing concurrently with an eviction
    // can never repopulate the just-cleared cache. Never held across IO —
    // both critical sections are a handful of map operations.
    private val cacheLock = Any()

    internal val cacheGenerationValue: Int
        get() = cacheGeneration.intValue

    fun cached(contact: ContactResult, sizePx: Int): ImageBitmap? {
        val uri = contact.photoThumbnailUri ?: return null
        return cache.get(CacheKey(uri, sizePx))
    }

    /**
     * Decodes the photo thumbnail for [contact] at [sizePx], returning the
     * cached bitmap when present and null when the contact has no photo or the
     * blob is unreadable/undecodable. Null results are not cached, so a
     * transient provider failure retries on the next composition. IO and
     * decode both run on [Dispatchers.IO]; safe to call from a composition's
     * `LaunchedEffect`.
     */
    suspend fun load(context: Context, contact: ContactResult, sizePx: Int): ImageBitmap? {
        val uri = contact.photoThumbnailUri ?: return null
        val key = CacheKey(uri, sizePx)
        cache.get(key)?.let { return it }
        val appContext = context.applicationContext
        // Snapshot the eviction generation before decoding, and only cache the
        // result if no eviction landed in between: an evictAll racing this
        // decode (contact search disabled, index reloaded) must win, or the
        // late put would retain a photo past the disable boundary / keep a
        // just-edited photo stale until the next reload. The bitmap is still
        // returned either way — the already-composed row may paint it, but
        // nothing retains it.
        val generationAtStart = cacheGeneration.intValue
        val bitmap = withContext(Dispatchers.IO) { decodePhoto(appContext, uri, sizePx) }
        if (bitmap != null) {
            synchronized(cacheLock) {
                if (cacheGeneration.intValue == generationAtStart) cache.put(key, bitmap)
            }
        }
        return bitmap
    }

    /** Drops every cached photo and re-keys live compositions. See class KDoc for when. */
    fun evictAll() {
        synchronized(cacheLock) {
            cache.evictAll()
            cacheGeneration.intValue++
        }
    }

    /**
     * Opens [photoUri] through the content resolver and decodes it to a
     * [sizePx]-square bitmap (center-cropped when the source isn't square, so
     * the row's circular clip never squashes a face). Guarded like
     * [AppIconLoader]'s producers: the provider can throw or hand back a
     * truncated blob, and a missing photo must degrade to the monogram, never
     * crash the launcher.
     */
    private fun decodePhoto(context: Context, photoUri: String, sizePx: Int): ImageBitmap? = try {
        val raw = context.contentResolver.openInputStream(Uri.parse(photoUri))
            ?.use { stream -> BitmapFactory.decodeStream(stream) }
        raw?.let { cropScaleToSquare(it, sizePx).asImageBitmap() }
    } catch (exception: Exception) {
        // Message only, no URI and no throwable: warnings mirror into
        // Crashlytics breadcrumbs (and recordException uploads the throwable),
        // and both the photo URI and a provider exception's own message can
        // identify a contact — PRIVACY.md promises breadcrumbs carry no
        // contact data. The exception class is the useful non-identifying
        // signal; the full stack still lands in logcat for local debugging.
        LauncherDebugLog.warning("ContactPhotoLoader decode failed: ${exception.javaClass.simpleName}")
        LauncherDebugLog.trace("ContactPhotoLoader decode failure detail: $exception")
        null
    }

    private fun cropScaleToSquare(raw: Bitmap, sizePx: Int): Bitmap {
        val side = minOf(raw.width, raw.height)
        val square = if (raw.width == raw.height) {
            raw
        } else {
            Bitmap.createBitmap(raw, (raw.width - side) / 2, (raw.height - side) / 2, side, side)
                .also { if (it !== raw) raw.recycle() }
        }
        if (square.width == sizePx) return square
        val scaled = Bitmap.createScaledBitmap(square, sizePx, sizePx, /* filter = */ true)
        if (scaled !== square) square.recycle()
        return scaled
    }
}

/**
 * The state of one contact row's async photo load, mirroring
 * [rememberAppIconResolution]: `bitmap` is the decoded thumbnail (or null for
 * the monogram), and `isResolved` distinguishes "still loading" from "resolved
 * to no photo" so screenshot tests can wait for the swap-in instead of
 * capturing an arbitrary mid-load mix of photos and monograms. A contact with
 * no [ContactResult.photoThumbnailUri] resolves immediately — the common case
 * costs no effect work at all.
 */
@Composable
internal fun rememberContactPhotoResolution(contact: ContactResult, sizeDp: Dp): AppIconResolution {
    val context = LocalContext.current.applicationContext
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }.coerceAtLeast(1)
    val photoUri = contact.photoThumbnailUri
    val generation = ContactPhotoLoader.cacheGenerationValue
    var resolution by remember(photoUri, sizePx, generation) {
        if (photoUri == null) {
            mutableStateOf(AppIconResolution(bitmap = null, isResolved = true))
        } else {
            val cached = ContactPhotoLoader.cached(contact, sizePx)
            mutableStateOf(AppIconResolution(bitmap = cached, isResolved = cached != null))
        }
    }
    LaunchedEffect(photoUri, sizePx, generation) {
        if (!resolution.isResolved) {
            resolution = AppIconResolution(
                bitmap = ContactPhotoLoader.load(context, contact, sizePx),
                isResolved = true,
            )
        }
    }
    return resolution
}
