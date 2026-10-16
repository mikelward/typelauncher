package app.typelauncher

import android.content.Intent
import android.net.Uri
import android.os.UserHandle
import android.provider.Settings
import androidx.compose.runtime.Immutable
import java.security.MessageDigest

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
    // False for a system app the user has never updated: Android refuses to
    // uninstall one, so the long-press menu leaves the item out rather than
    // offering an action the system declines. An *updated* system app stays
    // true — uninstalling it reverts to the factory version, which is a real
    // outcome the user may want. Defaults to true so the common case (an
    // ordinary installed app) needs no argument.
    val isUninstallable: Boolean = true,
    // Set when this entry is a shortcut the user pinned through Android's
    // pin-shortcut flow (a browser's "Add to Home screen", or a PWA installed
    // without a WebAPK) rather than a launcher activity. [packageName] is then
    // the app that published it — the browser — and the entry launches through
    // `LauncherApps.startShortcut` instead of [launchIntent].
    val shortcutId: String? = null,
    // Set when this entry is a web page the user added through Share → "Add
    // to app list" ([WebLinkStore]'s id for it). [packageName] is then
    // [WEB_LINK_PACKAGE] and [launchIntent] views the page.
    val webLinkId: String? = null,
) {
    val isShortcut: Boolean
        get() = shortcutId != null

    val isWebLink: Boolean
        get() = webLinkId != null

    // Computed once per instance rather than in a getter: a shortcut's id is
    // a SHA-256 digest, and ids are read inside the search sort's comparators
    // on the main thread. Every field it reads is a constructor `val`, and
    // `copy()` builds a new instance, so it can't go stale.
    val id: String = if (shortcutId != null) {
        pinnedShortcutEntryId(user, packageName, shortcutId)
    } else if (webLinkId != null) {
        webLinkEntryId(user, webLinkId)
    } else {
        "${user.hashCode()}:${launchIntent.component?.flattenToString() ?: packageName}"
    }

    // Cache identity for AppIconLoader / IconSnapshotStore. Layered so the
    // system-icon variant and a user-supplied override variant occupy
    // independent cache entries: clearing the override automatically reverts
    // to whatever the system entry already had cached, and bumping
    // [customIconVersion] orphans the previous override entry instead of
    // returning a stale bitmap.
    val iconCacheId: String
        get() {
            val token = iconCacheToken?.let { token -> "$id@$token" } ?: id
            // A web link's tile is drawn from its label, so a rename must
            // redraw it rather than hit the cached tile. Keyed on what the
            // tile shows, not the label: cache ids are written to disk.
            val base = if (isWebLink) "$token#tile:${letterTileLetter(displayName)}${letterTileColor(displayName)}" else token
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
        get() = effectiveCustomName ?: displayBase.withDisambiguator()

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
            if (effectiveCustomName != null) return null
            if (unprefixedName == displayBase) return null
            return unprefixedName.withDisambiguator()
        }

    // The user's rename, when one is set. [RenamedAppStore] only persists
    // non-blank trimmed names, but `customName` also arrives via plain
    // constructor calls, so the non-blank rule is re-enforced here — in one
    // place — rather than trusted at each read site.
    private val effectiveCustomName: String?
        get() = customName?.takeIf { it.isNotBlank() }

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
            val customLabel = effectiveCustomName
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

    // Hands the package off to the system uninstaller, which puts up its own
    // confirmation dialog — the launcher deliberately doesn't add a second one.
    //
    // EXTRA_USER is what makes a work-profile app uninstall the work copy: the
    // `package:` URI resolves against the current user only, exactly as
    // [appInfoIntent] does, and unlike App info there is no LauncherApps call
    // that dispatches an uninstall cross-profile.
    //
    // FLAG_ACTIVITY_NEW_TASK because this is started from an application
    // context; CLEAR_TASK for the same reason as [appInfoIntent] — so a
    // package installer already sitting on another page can't swallow it.
    val uninstallIntent: Intent
        get() = Intent(Intent.ACTION_DELETE)
            .setData(Uri.fromParts("package", packageName, null))
            .putExtra(Intent.EXTRA_USER, user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    override fun toString(): String = name
}

/**
 * [InstalledApp.id] of the entry a pinned shortcut becomes. The shortcut id is
 * the publisher's own string, and a browser may use the page's URL for it, so
 * it is never embedded: the id carries a one-way digest of it (SHA-256,
 * truncated to 128 bits, hex). That keeps browsing data out of every store and
 * diagnostic that records app ids — the dock list a bug report includes, for
 * one — and also keeps the stores' newline delimiter out of the id. The
 * package is a validated Android package name and is kept readable, as it is
 * for apps. The raw id stays on [InstalledApp.shortcutId] for `LauncherApps`.
 */
internal fun pinnedShortcutEntryId(user: UserHandle, packageName: String, shortcutId: String): String {
    // Hashes the raw UTF-16 code units, two bytes each, rather than an
    // encoded form: a charset encoder replaces an unpaired surrogate, which
    // would give two distinct publisher ids the same entry.
    val units = ByteArray(shortcutId.length * 2)
    shortcutId.forEachIndexed { index, char ->
        units[index * 2] = (char.code shr 8).toByte()
        units[index * 2 + 1] = char.code.toByte()
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(units)
    val opaque = digest.take(16).joinToString("") { byte -> "%02x".format(byte) }
    return "${user.hashCode()}:shortcut:$packageName/$opaque"
}

/** Whether an [InstalledApp.id] (or an icon cache id built on one) is a pinned shortcut's. */
internal fun isPinnedShortcutCacheId(id: String): Boolean = id.contains(":shortcut:")
