package app.typelauncher

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Persists per-app launcher-icon overrides so the user can replace the icon
 * Android reports for an app with a picture they pick (SVG, PNG, JPEG, WEBP).
 * Overrides are keyed by [InstalledApp.id] — user handle plus component name,
 * both of which are deterministic — so an override survives package upgrades
 * *and* uninstall/reinstall cycles; it persists until the user clears it (or
 * clears the launcher's app data).
 *
 * The on-disk layout is the source of truth: each override is stored under
 * `filesDir/icon_overrides/<base64Url(appId)>.<extension>` and the directory
 * listing seeds an in-memory index on first use. The index exists because
 * [iconFileFor] is no longer a once-per-load call: `markVisibility` consults
 * it for every app in every refreshed list — on the main thread, on every
 * keystroke — so a per-call directory scan multiplied out to
 * O(apps × dirEntries) disk reads per keystroke. One scan per process plus
 * map updates in [setIcon] / [clear] keeps lookups allocation- and I/O-free;
 * drift with the directory is not a concern because every mutation goes
 * through this class.
 *
 * The [extension] in the filename is mirrored from the user's chosen file so
 * the icon loader can pick the right decoder (AndroidSVG for `.svg`, the
 * platform `ImageDecoder` for raster formats) without sniffing bytes. The
 * file's `lastModified()` timestamp doubles as the override's version: it is
 * baked into [InstalledApp.iconCacheId] so that re-uploading an icon for the
 * same app produces a fresh `AppIconLoader` cache entry instead of returning
 * the previous bitmap. The timestamp is captured into the index at seed and
 * at [setIcon] commit — every mutation goes through this class — so
 * [iconVersionFor] answers from memory: `markVisibility` reads the version
 * for every overridden app on every keystroke, and a `File.lastModified()`
 * there re-stats the disk on the main thread per app per refresh.
 */
internal class IconOverrideStore(context: Context) {
    private val directory: File = File(context.filesDir, DIRECTORY_NAME)

    // appId → override file plus its captured version. Lazily seeded from one
    // directory scan. Guarded by `lock`: lookups run on the main thread while
    // `setIcon` runs on the IO dispatcher.
    private val lock = Any()
    private var index: MutableMap<String, Override>? = null

    // The override file and the `lastModified()` timestamp captured when the
    // index last recorded it, so a lookup never has to stat the file again.
    private class Override(val file: File, val version: Long)

    private fun index(): MutableMap<String, Override> {
        index?.let { return it }
        val built = mutableMapOf<String, Override>()
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name.endsWith(TMP_SUFFIX)) {
                // Orphaned by a crash mid-[setIcon]: nothing references it
                // again (a retried save writes its own tmp), so delete on
                // sight rather than let it accumulate for the process's
                // lifetime — the same policy IconSnapshotStore applies to
                // its stray tmp files. Safe against an in-flight save: this
                // scan runs once per process, at first lookup, which
                // precedes any user-initiated icon pick.
                file.delete()
                return@forEach
            }
            val stem = file.name.substringBeforeLast('.', missingDelimiterValue = "")
            val id = if (stem.isEmpty()) null else decodeAppIdFromFileName(stem)
            if (id != null) built[id] = Override(file, file.lastModified())
        }
        index = built
        return built
    }

    fun iconFileFor(appId: String): File? = synchronized(lock) { index()[appId]?.file }

    /**
     * The override's version for [appId] — the file's `lastModified()`
     * captured when the index recorded it — or `0L` when no override exists.
     * Answered from the in-memory index so callers on the main thread (chiefly
     * `markVisibility`, which runs per overridden app on every keystroke) never
     * re-stat the file. Kept in lock-step with [iconFileFor] by every mutation
     * going through [setIcon] / [clear].
     */
    fun iconVersionFor(appId: String): Long = synchronized(lock) { index()[appId]?.version ?: 0L }

    /**
     * Streams the contents of [source] into a new override file for [appId]
     * with the supplied [extension] (lowercased, no leading dot). Any prior
     * override for the same id — including ones with a different extension —
     * is removed once the new file is in place. Returns the saved [File]; on
     * IO failure throws so the caller can surface the error in the UI.
     */
    @Throws(IOException::class)
    fun setIcon(appId: String, source: InputStream, extension: String): File {
        val normalizedExt = extension.lowercase().trimStart('.')
        require(normalizedExt.isNotEmpty()) { "extension must not be empty" }
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Failed to create $directory")
        }
        val target = File(directory, encodeAppIdForFileName(appId) + "." + normalizedExt)
        // Unique tmp name per call: two saves for the same app and extension
        // (e.g. the user re-picks from a slow content:// provider before the
        // first stream finishes) must not stream into the same tmp file, or
        // their bytes interleave and whichever commits first renames a
        // corrupted image onto `target`. createTempFile guarantees a distinct
        // path; the name still starts with `<encoded>.` so [clear]'s prefix
        // sweep still catches it, and still ends with TMP_SUFFIX so the
        // index() scan still treats a crash-orphaned copy as stray.
        val tmp = File.createTempFile(target.name + ".", TMP_SUFFIX, directory)
        try {
            tmp.outputStream().use { output -> source.copyTo(output) }
            // Commit under `lock` so a concurrent [clear] (main thread —
            // the user tapping "Reset icon" while this save streams on the
            // IO dispatcher) can't interleave between the rename and the
            // index update: unsynchronized, the clear's file sweep could
            // run between them and leave the index mapping to a deleted
            // file — or the rename could land after the sweep and leave an
            // on-disk file that resurrects the cleared override on the
            // next cold start. With the lock, a clear either runs before
            // the commit (it deletes `tmp`, the rename fails, and this
            // save fails cleanly — the reset wins) or after it (a normal
            // clear of the fresh override). Only the fast local-FS commit
            // runs under the lock, never the source stream copy above, so
            // main-thread lookups can't stall behind a slow content
            // provider.
            synchronized(lock) {
                // `renameTo` atomically replaces an existing same-name
                // target, so a same-extension replacement keeps the prior
                // override on disk until the very moment the new one takes
                // its place — a failure before this line loses nothing.
                if (!tmp.renameTo(target)) {
                    tmp.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.delete()
                }
                // Only after the new file is in place, drop other-extension
                // override files for this app id so only the freshly-picked
                // one survives; otherwise switching between e.g. an SVG
                // override and a PNG override would leave the older file
                // behind, and `iconFileFor` would non-deterministically
                // pick one. Sweeping before the commit destroyed the
                // existing override when the commit then failed.
                val prefix = encodeAppIdForFileName(appId) + "."
                directory.listFiles { file ->
                    file.isFile &&
                        file.name != target.name &&
                        // Skip every tmp, not just this call's: a concurrent
                        // same-app save streams into its own unique tmp, and
                        // deleting it here would corrupt that save. Committed
                        // tmps don't exist (a commit renames the tmp onto
                        // target), and crash-orphaned ones are reaped by index().
                        !file.name.endsWith(TMP_SUFFIX) &&
                        file.name.startsWith(prefix)
                }?.forEach { it.delete() }
                // Capture the fresh file's timestamp now, under the lock, so
                // `iconVersionFor` reflects the new override without a later
                // re-stat. This is the value that busts the icon cache.
                index()[appId] = Override(target, target.lastModified())
            }
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun clear(appId: String) {
        synchronized(lock) {
            index().remove(appId)
            if (!directory.isDirectory) return
            // The file sweep shares the index's lock so it is atomic with
            // respect to [setIcon]'s commit — see the comment there. The
            // prefix match also catches an in-flight save's `.tmp`, which
            // makes that save fail cleanly instead of re-creating the
            // override the user just reset.
            val prefix = encodeAppIdForFileName(appId) + "."
            directory.listFiles { file -> file.name.startsWith(prefix) }
                ?.forEach { it.delete() }
        }
    }

    /**
     * Snapshot of every app id that currently has an override. Used by
     * [LauncherViewModel] at cold start to mirror overrides onto the
     * in-memory installed-app list before the first frame renders.
     */
    fun overriddenAppIds(): Set<String> = synchronized(lock) { index().keys.toSet() }

    private companion object {
        const val DIRECTORY_NAME = "icon_overrides"
        const val TMP_SUFFIX = ".tmp"
    }
}
