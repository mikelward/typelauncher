package app.typelauncher

import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Immutable

// Compose can't infer stability through `Intent` / `UserHandle`, so it
// otherwise marks every parameter typed as `InstalledApp` (or any list of
// them) unstable and skips no recompositions. Every field here is a `val`
// and we never mutate the wrapped Intent after construction, so the
// instance is genuinely immutable for rendering purposes.
@Immutable
internal data class InstalledApp(
    val name: String,
    val packageName: String,
    val launchIntent: Intent,
    val user: UserHandle,
    val isWorkApp: Boolean,
    val launchWithLauncherApps: Boolean,
    val iconCacheToken: String? = null,
    val isDocked: Boolean = false,
    val isWorkDocked: Boolean = false,
    // True when this app is a member of a dock folder (personal or work). It is
    // "on the dock" inside a folder, so it is not a loose occupant (isDocked is
    // false), but its app-list menu offers "Move out of folder" instead of the
    // no-op "Dock".
    val isInFolder: Boolean = false,
    val isHidden: Boolean = false,
    val isQuietMode: Boolean = false,
    val disambiguator: String? = null,
    val customName: String? = null,
    val customBadge: String? = null,
    val customIconPath: String? = null,
    // Bumped (typically to the override file's `lastModified()` timestamp)
    // every time the user re-uploads an icon for this app. Folded into
    // [iconCacheId] so a re-upload produces a fresh `AppIconLoader` cache key
    // rather than handing back the previous bitmap.
    val customIconVersion: Long = 0L,
    // `name` for personal apps; the work-prefixed form (e.g. "Work Calendar",
    // formatted via R.string.app_label_with_work_prefix) for work-profile apps.
    // Pre-formatted at load time because the model is Context-free, and used as
    // the base for [displayName] so the prefix is both visible in every render
    // site and matchable by the keyboard-driven filter in `filterByName`.
    // Defaults to `name` so existing tests and any non-VM constructor needn't
    // spell out the work prefix.
    val displayBase: String = name,
    // The un-prefixed noun a work-profile app searches by — the same stripped
    // label [displayBase] is built from, so it's "Calendar" for a "Work
    // Calendar" and, crucially, "Email" for an app whose own label already
    // starts with the locale's work token ("Work Email"), where raw `name`
    // would still carry the prefix. Set by `LauncherViewModel.loadInstalledApps`
    // and consumed by [workPrefixStrippedSearchName]. Defaults to `name` (the
    // correct value for personal apps and for the common work app whose own
    // label has no leading work token), so non-VM constructors needn't set it.
    val unprefixedName: String = name,
) {
    val id: String
        get() = "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"

    // Cache identity for AppIconLoader / IconSnapshotStore. Layered so the
    // system-icon variant and a user-supplied override variant occupy
    // independent cache entries: clearing the override automatically reverts
    // to whatever the system entry already had cached, and bumping
    // [customIconVersion] orphans the previous override entry instead of
    // returning a stale bitmap.
    val iconCacheId: String
        get() {
            val base = iconCacheToken?.let { token -> "$id@$token" } ?: id
            return customIconPath?.let { _ -> "$base#override:$customIconVersion" } ?: base
        }

    // Display label. A user-supplied [customName] always wins so a renamed app
    // (e.g. a "ChatGPT" PWA renamed to "Codex") shows the chosen label across
    // every surface and search matches the override rather than the system
    // label — and a user who renames a work-profile app gets their chosen
    // label verbatim, no "Work " prefix forced on top. Falling through, builds
    // on [displayBase] (raw `name` for personal apps, "Work <name>" for
    // work-profile apps) so the prefix is visible everywhere and typable in
    // search, then appends a parenthesised disambiguator (e.g. "Chase (US)")
    // when this app shares an icon-level identity with peers. Skips the
    // suffix if the disambiguator already appears as a whitespace-separated
    // token — "Amex UK" with a "UK" badge stays "Amex UK" rather than
    // becoming the redundant "Amex UK (UK)".
    val displayName: String
        get() {
            customName?.takeIf { it.isNotBlank() }?.let { return it }
            return displayBase.withDisambiguator()
        }

    // Extra label the typed-search filter matches against so a work-profile
    // app's "Work " prefix never demotes the match tier: it lets a query
    // prefix-match the underlying app name ("Calendar") at the same tier as
    // the personal copy, instead of being pushed down to a mid-string anchored
    // match on the visible "Work Calendar". Built from [unprefixedName] (the
    // stripped noun, "Calendar" even when the raw label was "Work Calendar")
    // with the same disambiguator suffix the visible label would carry. Null
    // when there's nothing extra to gain: personal apps, a user-renamed work
    // app (its [customName] already wins [displayName] with no forced prefix),
    // or the degenerate case where stripping left the visible label unchanged.
    val workPrefixStrippedSearchName: String?
        get() {
            if (!isWorkApp) return null
            if (customName?.takeIf { it.isNotBlank() } != null) return null
            if (unprefixedName == displayBase) return null
            return unprefixedName.withDisambiguator()
        }

    // Appends the parenthesised disambiguator (e.g. "Chase (US)") when this app
    // shares an icon-level identity with peers. Skips the suffix if the
    // disambiguator already appears as a whitespace-separated token — "Amex UK"
    // with a "UK" badge stays "Amex UK" rather than the redundant "Amex UK (UK)".
    private fun String.withDisambiguator(): String {
        val tag = disambiguator?.takeIf { it.isNotEmpty() } ?: return this
        // Strip surrounding punctuation so a name like "Bank (US)" with a
        // "US" disambiguator doesn't render as "Bank (US) (US)".
        val nameTokens = split(WHITESPACE_REGEX)
            .map { it.trim('(', ')', '[', ']', '-', '–', '—').trim() }
        if (nameTokens.any { it.equals(tag, ignoreCase = true) }) return this
        return "$this ($tag)"
    }

    /**
     * Label used to pick the corner badge (country flag / globe). When the
     * user has supplied a [customName], the override takes precedence over
     * the system-derived [disambiguator]: the badge is parsed from the
     * trailing whitespace-separated token of the override (e.g. "Chase (US)"
     * → "US", "Amex UK" → "UK"), so a user-renamed app gets a US flag if
     * they tag it that way and no badge if their override has no
     * recognisable tag — they explicitly chose the label, so we don't
     * silently keep the old system badge they were trying to override.
     * Returns `null` when no badge should render.
     */
    val effectiveDisambiguator: String?
        get() {
            val customLabel = customName?.takeIf { it.isNotBlank() }
                ?: return disambiguator?.takeIf { it.isNotEmpty() }
            return parseTrailingDisambiguatorTag(customLabel)
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
