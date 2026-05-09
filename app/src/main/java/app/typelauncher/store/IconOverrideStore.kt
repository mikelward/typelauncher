package app.typelauncher

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Persists per-app launcher-icon overrides so the user can replace the icon
 * Android reports for an app with a picture they pick (SVG, PNG, JPEG, WEBP).
 * Overrides are keyed by [InstalledApp.id] so they survive a package upgrade
 * but not a fresh install (which produces a new component name and therefore
 * a new id).
 *
 * The on-disk layout is the source of truth: each override is stored under
 * `filesDir/icon_overrides/<base64Url(appId)>.<extension>` and the directory
 * listing is the index. Keeping a separate SharedPreferences map alongside the
 * files would only invite drift between the two stores; since [iconFileFor] is
 * called once per app during launcher load (cheap directory scan, single-digit
 * milliseconds at expected sizes) the no-index trade-off is fine.
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

    fun iconFileFor(appId: String): File? {
        if (!directory.isDirectory) return null
        val prefix = encodeId(appId) + "."
        return directory.listFiles { file ->
            file.isFile && file.name.startsWith(prefix) && !file.name.endsWith(TMP_SUFFIX)
        }?.firstOrNull()
    }

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
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    fun clear(appId: String) {
        if (!directory.isDirectory) return
        val prefix = encodeId(appId) + "."
        directory.listFiles { file -> file.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    /**
     * Snapshot of every app id that currently has an override, recovered by
     * decoding the filenames in the directory. Used by [LauncherViewModel] at
     * cold start to mirror overrides onto the in-memory installed-app list
     * before the first frame renders.
     */
    fun overriddenAppIds(): Set<String> {
        if (!directory.isDirectory) return emptySet()
        return directory.listFiles().orEmpty().mapNotNullTo(mutableSetOf()) { file ->
            if (!file.isFile || file.name.endsWith(TMP_SUFFIX)) return@mapNotNullTo null
            val stem = file.name.substringBeforeLast('.', missingDelimiterValue = "")
            if (stem.isEmpty()) null else decodeId(stem)
        }
    }

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
