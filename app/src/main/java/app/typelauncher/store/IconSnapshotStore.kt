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
 * Persists a small set of pre-rasterised app icons under `filesDir/icon_snapshots_v2/` so the
 * dock and the most-used app rows can paint real icons on the very first frame of a cold
 * start, ahead of the `LauncherApps`/`PackageManager` resolve + bitmap rasterisation that
 * `AppIconLoader.load` performs on a cache miss.
 *
 * The directory carries a renderer/format version (`_v2`). When the bitmaps a renderer
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
 */
internal class IconSnapshotStore(context: Context) {
    private val directory: File = File(context.filesDir, DIRECTORY_NAME)

    // Serializes the directory mutations in [save] (and the [load] read) so two
    // saves racing between rapid `onStop`s can't interleave their prune +
    // per-file writes and leave a half-written or wrongly-pruned snapshot set
    // on disk. Saves run on `Dispatchers.IO`, so a blocking monitor is fine
    // here — the contended case is two background saves, never the main thread.
    private val lock = Any()

    fun load(): List<Snapshot> = synchronized(lock) {
        // Runs on Dispatchers.IO (the cold-start restore coroutine), so the
        // legacy-directory sweep is off the main thread. It precedes save (which
        // is gated on the restore completing), so purging here is enough to keep
        // a stale-format snapshot from ever being restored or re-persisted.
        purgeLegacyDirectories()
        if (!directory.isDirectory) return emptyList()
        directory.listFiles().orEmpty().mapNotNull(::readSnapshot)
    }

    private fun purgeLegacyDirectories() {
        directory.parentFile
            ?.listFiles { file ->
                file.isDirectory && file.name != DIRECTORY_NAME && file.name.startsWith(DIRECTORY_PREFIX)
            }
            ?.forEach { it.deleteRecursively() }
    }

    fun save(snapshots: Collection<Snapshot>): Unit = synchronized(lock) {
        if (snapshots.isEmpty()) {
            // An empty save still prunes orphans, but we don't create the directory just
            // to delete nothing from it.
            if (directory.isDirectory) {
                directory.listFiles()?.forEach { it.delete() }
            }
            return
        }
        directory.mkdirs()
        val expectedNames = snapshots.mapTo(mutableSetOf()) { it.fileName() }
        directory.listFiles()?.forEach { file ->
            if (file.name !in expectedNames) {
                file.delete()
            }
        }
        for (snapshot in snapshots) {
            val file = File(directory, snapshot.fileName())
            runCatching { writeSnapshot(file, snapshot) }
                .onFailure { file.delete() }
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

    private companion object {
        // Bump the version suffix whenever the persisted bitmap's appearance
        // changes so an upgrade drops the old directory instead of restoring
        // stale-format icons. _v2 = IconNormalizer full-bleed tiles.
        const val DIRECTORY_NAME = "icon_snapshots_v2"
        const val DIRECTORY_PREFIX = "icon_snapshots"
        const val EXTENSION = ".bin"
        const val TMP_SUFFIX = ".tmp"
        const val HEADER_SIZE = 8
        const val BYTES_PER_PIXEL = 4
        const val MAX_DIMENSION = 1024
        const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
