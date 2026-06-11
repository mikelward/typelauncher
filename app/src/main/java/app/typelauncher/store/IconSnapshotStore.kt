package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Persists a small set of pre-rasterised app icons under `filesDir/icon_snapshots_v<N>/`
 * (see [DIRECTORY_NAME] for the current version) so the dock and the most-used app rows can
 * paint real icons on the very first frame of a cold start, ahead of the
 * `LauncherApps`/`PackageManager` resolve + bitmap rasterisation that `AppIconLoader.load`
 * performs on a cache miss.
 *
 * The directory carries a renderer/format version (`_v<N>`). When the bitmaps a renderer
 * produces change shape — e.g. the move to `IconNormalizer`'s full-bleed tiles — bumping the
 * version means an upgrade restores nothing from the old directory, so a stale-format bitmap
 * can never be put back into `AppIconLoader`'s cache (a hit there would bypass the current
 * rasterisation path). [load] deletes any earlier-versioned directory on the first cold-start
 * read so old snapshots don't linger on disk.
 *
 * Bitmaps are written as raw `ARGB_8888` pixel buffers (no PNG/WEBP encoding) so the
 * cold-start read path is just a `File.readBytes` + `Bitmap.copyPixelsFromBuffer`, with no
 * decoder cost. Files are keyed by URL-safe base64 of `InstalledApp.id` plus the pixel
 * size, so a single app may carry several entries (e.g. text-row 40dp and dock 56dp at the
 * current display density) without collision.
 *
 * Stale entries are pruned on `save`: anything not in the freshly-supplied set is deleted.
 * Wrong-size or wrong-density entries simply miss on lookup and the next render falls
 * back to a normal `AppIconLoader.load`, which then overwrites the snapshot on the next
 * `save`.
 *
 * Alongside the bitmaps, every [save] writes a `renderer_state` marker file recording the
 * renderer state (icon theme + resolved themed palette, see [rendererState]) the bitmaps
 * were rasterized under. [load] takes the *current* renderer state and, when the marker is
 * missing or doesn't match, deletes every snapshot and restores nothing: a wrong-variant
 * tile put back into `AppIconLoader`'s cache would be a hit that bypasses rendering
 * indefinitely (e.g. the icon theme changed but the process died before the post-eviction
 * re-save). The marker is written last in [save], so a crash mid-save leaves a marker that
 * mismatches on the next load and the half-written set is purged rather than restored.
 */
internal class IconSnapshotStore(context: Context) {
    private val directory: File = File(context.filesDir, DIRECTORY_NAME)

    // Serializes the directory mutations in [save] (and the [load] read) so two
    // saves racing between rapid `onStop`s can't interleave their prune +
    // per-file writes and leave a half-written or wrongly-pruned snapshot set
    // on disk. Saves run on `Dispatchers.IO`, so a blocking monitor is fine
    // here — the contended case is two background saves, never the main thread.
    private val lock = Any()

    fun load(expectedRendererState: String): List<Snapshot> = synchronized(lock) {
        // Runs on Dispatchers.IO (the cold-start restore coroutine), so the
        // legacy-directory sweep is off the main thread. It precedes save (which
        // is gated on the restore completing), so purging here is enough to keep
        // a stale-format snapshot from ever being restored or re-persisted.
        purgeLegacyDirectories()
        if (!directory.isDirectory) return emptyList()
        val files = directory.listFiles().orEmpty()
        val recordedState = runCatching {
            files.firstOrNull { it.name == RENDERER_STATE_FILE_NAME }?.readText()
        }.getOrNull()
        if (recordedState != expectedRendererState) {
            // The snapshots were rasterized under a different icon theme /
            // palette (or predate the marker entirely), so restoring them would
            // pin wrong-variant tiles in the cache until the next eviction.
            // Purge instead and let the next render rasterize fresh.
            files.forEach { it.delete() }
            return emptyList()
        }
        files.filter { it.name != RENDERER_STATE_FILE_NAME }.mapNotNull(::readSnapshot)
    }

    private fun purgeLegacyDirectories() {
        directory.parentFile
            ?.listFiles { file ->
                file.isDirectory && file.name != DIRECTORY_NAME && file.name.startsWith(DIRECTORY_PREFIX)
            }
            ?.forEach { it.deleteRecursively() }
    }

    fun save(snapshots: Collection<Snapshot>, rendererState: String): Unit = synchronized(lock) {
        if (snapshots.isEmpty()) {
            // An empty save still prunes orphans (including the renderer-state
            // marker), but we don't create the directory just to delete nothing
            // from it.
            if (directory.isDirectory) {
                directory.listFiles()?.forEach { it.delete() }
            }
            return
        }
        directory.mkdirs()
        val expectedNames = snapshots.mapTo(mutableSetOf()) { it.fileName() }
        directory.listFiles()?.forEach { file ->
            if (file.name !in expectedNames && file.name != RENDERER_STATE_FILE_NAME) {
                file.delete()
            }
        }
        for (snapshot in snapshots) {
            val file = File(directory, snapshot.fileName())
            runCatching { writeSnapshot(file, snapshot) }
                .onFailure { file.delete() }
        }
        // Written last: a crash mid-save leaves the previous marker (or none),
        // which mismatches on the next load and purges the half-written set.
        writeRendererState(rendererState)
    }

    private fun writeRendererState(rendererState: String) {
        val file = File(directory, RENDERER_STATE_FILE_NAME)
        val tmp = File(directory, "$RENDERER_STATE_FILE_NAME$TMP_SUFFIX")
        runCatching {
            tmp.writeText(rendererState)
            if (!tmp.renameTo(file)) {
                // Fall back to a non-atomic write rather than leaving a stale .tmp behind.
                tmp.delete()
                file.writeText(rendererState)
            }
        }.onFailure {
            // A marker recording the wrong state is worse than no marker (no
            // marker purges on the next load, which is the safe direction).
            tmp.delete()
            file.delete()
        }
    }

    private fun writeSnapshot(file: File, snapshot: Snapshot) {
        val bitmap = snapshot.bitmap.asAndroidBitmap()
        val width = bitmap.width
        val height = bitmap.height
        val pixelByteCount = width * height * BYTES_PER_PIXEL
        val buffer = ByteBuffer.allocate(HEADER_SIZE + pixelByteCount).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(width)
        buffer.putInt(height)
        bitmap.copyPixelsToBuffer(buffer)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(buffer.array())
        if (!tmp.renameTo(file)) {
            // Fall back to a non-atomic write rather than leaving a stale .tmp behind.
            tmp.delete()
            file.writeBytes(buffer.array())
        }
    }

    private fun readSnapshot(file: File): Snapshot? {
        if (!file.isFile || file.name.endsWith(TMP_SUFFIX)) {
            // Drop any leftover .tmp from a crashed write so it doesn't accumulate.
            if (file.name.endsWith(TMP_SUFFIX)) file.delete()
            return null
        }
        val parsed = parseFileName(file.name) ?: return null
        return runCatching {
            val bytes = file.readBytes()
            if (bytes.size < HEADER_SIZE) return@runCatching null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
                return@runCatching null
            }
            val expectedPixelBytes = width * height * BYTES_PER_PIXEL
            if (bytes.size != HEADER_SIZE + expectedPixelBytes) return@runCatching null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.position(HEADER_SIZE)
            bitmap.copyPixelsFromBuffer(buffer)
            Snapshot(id = parsed.first, sizePx = parsed.second, bitmap = bitmap.asImageBitmap())
        }.getOrNull()
    }

    private fun Snapshot.fileName(): String = "${encodeId(id)}_$sizePx$EXTENSION"

    private fun parseFileName(name: String): Pair<String, Int>? {
        if (!name.endsWith(EXTENSION)) return null
        val stem = name.removeSuffix(EXTENSION)
        val sep = stem.lastIndexOf('_')
        if (sep <= 0 || sep == stem.length - 1) return null
        val sizePx = stem.substring(sep + 1).toIntOrNull() ?: return null
        if (sizePx <= 0) return null
        val id = decodeId(stem.substring(0, sep)) ?: return null
        return id to sizePx
    }

    private fun encodeId(id: String): String =
        Base64.encodeToString(id.toByteArray(Charsets.UTF_8), BASE64_FLAGS)

    private fun decodeId(encoded: String): String? = runCatching {
        String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)
    }.getOrNull()

    internal data class Snapshot(val id: String, val sizePx: Int, val bitmap: ImageBitmap)

    companion object {
        /**
         * Serializes the renderer state the cached tiles bake in — the icon
         * theme plus, under Monochrome, the resolved plate/glyph pair (the
         * dynamic system palette can change while the theme stays Monochrome,
         * e.g. a wallpaper change while the process is dead). Saved into the
         * `renderer_state` marker on every [save] and compared by [load]
         * against the current mirrors so wrong-variant tiles are never
         * restored. `Default` tiles carry no themed plate, so the palette is
         * deliberately not part of the default fingerprint.
         */
        fun rendererState(
            iconTheme: IconTheme,
            themedColors: IconNormalizer.ThemedIconColors?,
        ): String = when (iconTheme) {
            IconTheme.Default -> "default"
            IconTheme.Monochrome ->
                "monochrome plate=${themedColors?.plate} glyph=${themedColors?.glyph}"
        }

        // Bump the version suffix whenever the persisted bitmap's appearance
        // changes so an upgrade drops the old directory instead of restoring
        // stale-format icons. _v2 = IconNormalizer full-bleed tiles; _v3 added
        // the adaptive safe-zone zoom; _v4 = layer-aware adaptive normalization
        // (foreground logo measured and enlarged); _v5 drops the baked-in
        // work-profile badge — work tiles are now cached unbadged and the
        // briefcase is drawn as a separate overlay, so a restored older work
        // bitmap would carry a clipped baked-in badge under the new overlay
        // (a double badge) until eviction; _v6 = selective foreground zoom
        // (only tiny logos enlarged, scaled about the tile center); _v7 raised
        // the tiny-logo target; _v8 = shadow-excluded shape-aware logo sizing
        // with a larger minimum for dark plates, enlargement anchored on the
        // logo's own center; _v9 seated the composition as an inset circle on
        // a dominant-color plate (reverted); _v10 removes the inset ring,
        // caps the safe-zone zoom so margin-less foregrounds (Chrome, Play
        // Store) are never cropped, and fills the tile with dark-plated
        // disc logos (GitHub); _v11 restores the platform crop for full-bleed
        // foregrounds (Play Store) and raises the bright-plate minimum;
        // _v12 retires per-logo sizing entirely — adaptive icons render
        // exactly as the platform composes them; _v13 forces every app
        // monochrome under the Monochrome theme — apps without an authored
        // monochrome layer now get a glyph synthesized from their own art
        // rather than keeping their full-color tile; _v14 engraves that
        // synthesized glyph from the icon's brightness instead of its alpha
        // silhouette, so a shape drawn in color on a solid field keeps its
        // detail instead of flattening to a disc; _v15 engraves from color
        // distance rather than brightness alone, so a mark set apart from its
        // background only by hue (same brightness) is kept instead of flattening.
        private const val DIRECTORY_NAME = "icon_snapshots_v15"
        private const val DIRECTORY_PREFIX = "icon_snapshots"
        private const val RENDERER_STATE_FILE_NAME = "renderer_state"
        private const val EXTENSION = ".bin"
        private const val TMP_SUFFIX = ".tmp"
        private const val HEADER_SIZE = 8
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_DIMENSION = 1024
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
