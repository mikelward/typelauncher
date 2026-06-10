package app.typelauncher

import android.content.Context
import android.util.Base64
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
 * the previous bitmap.
 */
internal class IconOverrideStore(context: Context) {
    private val directory: File = File(context.filesDir, DIRECTORY_NAME)

    // appId → override file, lazily seeded from one directory scan. Guarded
    // by `lock`: lookups run on the main thread while `setIcon` runs on the
    // IO dispatcher.
    private val lock = Any()
    private var index: MutableMap<String, File>? = null

    private fun index(): MutableMap<String, File> {
        index?.let { return it }
        val built = mutableMapOf<String, File>()
        directory.listFiles().orEmpty().forEach { file ->
            if (!file.isFile || file.name.endsWith(TMP_SUFFIX)) return@forEach
            val stem = file.name.substringBeforeLast('.', missingDelimiterValue = "")
            val id = if (stem.isEmpty()) null else decodeId(stem)
            if (id != null) built[id] = file
        }
        index = built
        return built
    }

    fun iconFileFor(appId: String): File? = synchronized(lock) { index()[appId] }

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
        val target = File(directory, encodeId(appId) + "." + normalizedExt)
        val tmp = File(directory, target.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { output -> source.copyTo(output) }
            // Drop any existing override files for this app id so only the
            // freshly-picked one survives; otherwise switching between e.g. an
            // SVG override and a PNG override would leave the older file
            // behind, and `iconFileFor` would non-deterministically pick one.
            val prefix = encodeId(appId) + "."
            directory.listFiles { file ->
                file.isFile && file.name != tmp.name && file.name.startsWith(prefix)
            }?.forEach { it.delete() }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.inputStream().use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.delete()
            }
            synchronized(lock) { index()[appId] = target }
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun clear(appId: String) {
        synchronized(lock) { index().remove(appId) }
        if (!directory.isDirectory) return
        val prefix = encodeId(appId) + "."
        directory.listFiles { file -> file.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    /**
     * Snapshot of every app id that currently has an override. Used by
     * [LauncherViewModel] at cold start to mirror overrides onto the
     * in-memory installed-app list before the first frame renders.
     */
    fun overriddenAppIds(): Set<String> = synchronized(lock) { index().keys.toSet() }

    private fun encodeId(id: String): String =
        Base64.encodeToString(id.toByteArray(Charsets.UTF_8), BASE64_FLAGS)

    private fun decodeId(encoded: String): String? = runCatching {
        String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        const val DIRECTORY_NAME = "icon_overrides"
        const val TMP_SUFFIX = ".tmp"
        const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
