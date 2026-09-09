package app.typelauncher

import android.util.Base64

/**
 * URL-safe base64 codec for deriving on-disk file names from
 * [InstalledApp.id] in the icon stores ([IconOverrideStore],
 * [IconSnapshotStore]) — app ids contain '/' and ':', so they can't be used
 * as file names directly. Both stores must agree on the exact flags: a
 * padding or wrapping mismatch would strand every existing file on upgrade.
 */
private const val APP_ID_BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

internal fun encodeAppIdForFileName(id: String): String =
    Base64.encodeToString(id.toByteArray(Charsets.UTF_8), APP_ID_BASE64_FLAGS)

internal fun decodeAppIdFromFileName(encoded: String): String? = runCatching {
    String(Base64.decode(encoded, APP_ID_BASE64_FLAGS), Charsets.UTF_8)
}.getOrNull()
