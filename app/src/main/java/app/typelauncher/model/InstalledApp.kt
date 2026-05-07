package app.typelauncher

import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings

internal data class InstalledApp(
    val name: String,
    val packageName: String,
    val launchIntent: Intent,
    val user: UserHandle,
    val isWorkApp: Boolean,
    val launchWithLauncherApps: Boolean,
    val shortcutId: String? = null,
    val iconCacheToken: String? = null,
    val isDocked: Boolean = false,
    val isHidden: Boolean = false,
    val isQuietMode: Boolean = false,
    val disambiguator: String? = null,
) {
    val id: String
        get() = shortcutId
            ?.let { id -> "${user.hashCode()}:shortcut:$packageName/${Uri.encode(id)}" }
            ?: "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

    val iconCacheId: String
        get() = iconCacheToken?.let { token -> "$id@$token" } ?: id

    // Display label that appends a parenthesised disambiguator (e.g. "Chase
    // (US)") when this app shares an icon-level identity with peers; falls
    // back to the raw `name` when no disambiguator was assigned. Skips the
    // suffix if the disambiguator already appears as a whitespace-separated
    // token in the name — "Amex UK" with a "UK" badge stays "Amex UK"
    // rather than becoming the redundant "Amex UK (UK)".
    val displayName: String
        get() {
            val tag = disambiguator?.takeIf { it.isNotEmpty() } ?: return name
            // Strip surrounding punctuation so a name like "Bank (US)" with a
            // "US" disambiguator doesn't render as "Bank (US) (US)".
            val nameTokens = name
                .split(Regex("\\s+"))
                .map { it.trim('(', ')', '[', ']', '-', '–', '—').trim() }
            if (nameTokens.any { it.equals(tag, ignoreCase = true) }) return name
            return "$name ($tag)"
        }

    // Fallback used by `LauncherViewModel.openAppInfo` when no LauncherApps
    // component is available; resolves the package URI against the current
    // user only, so work-profile apps must go through `LauncherApps.
    // startAppDetailsActivity(..., user, ...)` instead.
    //
    // FLAG_ACTIVITY_CLEAR_TASK: if Settings is already running on a different page
    // (e.g. Bluetooth), NEW_TASK alone reuses that task and shows the page on top
    // instead of App Info. CLEAR_TASK resets the task so we always land on App Info.
    val appInfoIntent: Intent
        get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    override fun toString(): String = name
}
