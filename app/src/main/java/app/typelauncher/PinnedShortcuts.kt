package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex

/**
 * The shortcuts this launcher has pinned for [user] — what a browser's "Add to
 * Home screen" (or a PWA installed without a WebAPK) leaves behind once
 * [PinShortcutActivity] accepts it. Only the default home app may read them,
 * so a launcher that isn't the default gets none rather than an exception.
 *
 * Throws on a locked or paused profile (`IllegalStateException`), a profile
 * leaving the group (`SecurityException`) and binder failures. Readers that
 * only display shortcuts go through [pinnedShortcutsOrNull]; one that writes
 * the pinned set back must see the failure, because an empty read would tell
 * it every pin is gone.
 */
internal fun readPinnedShortcuts(launcherApps: LauncherApps, user: UserHandle): List<ShortcutInfo> =
    if (!launcherApps.hasShortcutHostPermission()) {
        emptyList()
    } else {
        launcherApps.getShortcuts(
            LauncherApps.ShortcutQuery().setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED),
            user,
        ).orEmpty()
    }

/**
 * [read] for display: a failure is logged and reads as null — unknown, not
 * empty — so the caller can keep the shortcuts it already had for that
 * profile instead of dropping every page over a transient failure. Shortcut
 * entries are an addition to the app list, so a failure here never marks the
 * app read itself as degraded.
 */
internal fun pinnedShortcutsOrNull(read: () -> List<ShortcutInfo>): List<ShortcutInfo>? = try {
    read()
} catch (exception: CancellationException) {
    throw exception
} catch (exception: RuntimeException) {
    // No shortcut detail in the line: their ids and labels are the user's own
    // pages.
    LauncherDebugLog.failure(exception, "readPinnedShortcuts failed")
    null
}

/**
 * The pinned set to hand `LauncherApps.pinShortcuts` so that only
 * [shortcutId] is unpinned. `pinShortcuts` *replaces* the launcher's whole
 * pinned set for the package, so this is every other pinned id from [pinned].
 * Null — write nothing — when [shortcutId] is not in [pinned]: a read that
 * doesn't show the shortcut being removed can't be trusted to show the ones
 * that must stay, and writing it back would unpin them all.
 */
internal fun remainingPinnedIds(
    pinned: List<ShortcutInfo>,
    packageName: String,
    shortcutId: String,
): List<String>? {
    val ids = pinned
        .filter { shortcut -> shortcut.`package` == packageName && shortcut.isPinned }
        .map { shortcut -> shortcut.id }
    if (shortcutId !in ids) return null
    return ids.filter { id -> id != shortcutId }
}

/**
 * Maps the pinned shortcuts [readPinnedShortcuts] returned to app-list
 * entries. A disabled shortcut (its publisher withdrew it, e.g. the site's
 * PWA was uninstalled from the browser) is left out — it can't launch.
 */
internal fun pinnedShortcutEntries(
    shortcuts: List<ShortcutInfo>,
    user: UserHandle,
    isWorkApp: (packageName: String) -> Boolean,
    isQuietMode: Boolean,
    displayBase: (label: String, isWork: Boolean) -> String = { label, _ -> label },
    unprefixedName: (label: String, isWork: Boolean) -> String = { label, _ -> label },
): List<InstalledApp> = shortcuts
    .filter { shortcut -> shortcut.isPinned && shortcut.isEnabled }
    .mapNotNull { shortcut ->
        val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString()?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val work = isWorkApp(shortcut.`package`)
        InstalledApp(
            name = label,
            packageName = shortcut.`package`,
            // Never launched: shortcuts go through LauncherApps.startShortcut.
            // Carries the publisher's activity where the system recorded one,
            // so App info reaches the publisher through the profile-aware
            // `LauncherApps.startAppDetailsActivity` — without a component it
            // resolves against the personal profile, the wrong copy for a
            // work-profile shortcut.
            launchIntent = shortcut.activity
                ?.let { activity -> Intent.makeMainActivity(activity) }
                ?: Intent().setPackage(shortcut.`package`),
            user = user,
            isWorkApp = work,
            launchWithLauncherApps = true,
            iconCacheToken = shortcutIconCacheToken(shortcut),
            isQuietMode = isQuietMode,
            // "Remove" unpins rather than uninstalling — always possible.
            isUninstallable = true,
            displayBase = displayBase(label, work),
            unprefixedName = unprefixedName(label, work),
            shortcutId = shortcut.id,
        )
    }

/**
 * Whether a `LauncherApps.Callback.onShortcutsChanged` delivery for
 * [packageName] / [user] changes anything the app list shows. Apps republish
 * their dynamic shortcuts often (a messenger on every new conversation), and
 * each republish fires the callback with the package's whole shortcut set, so
 * reloading the app list on every one would be a full re-enumeration for
 * nothing. Only a change to the pinned, enabled set — or to one of them, which
 * bumps its last-changed timestamp — warrants a reload.
 *
 * Compares ids and timestamps only: the callback delivers key-field-only
 * `ShortcutInfo`s, with no labels, so anything that needs a label (such as
 * [pinnedShortcutEntries]) would read every delivered shortcut as absent.
 */
internal fun pinnedShortcutsChanged(
    current: List<InstalledApp>,
    packageName: String,
    user: UserHandle,
    delivered: List<ShortcutInfo>,
): Boolean {
    val shown = current
        .filter { app -> app.isShortcut && app.packageName == packageName && app.user == user }
        .associate { app -> app.shortcutId to app.iconCacheToken }
    val incoming = delivered
        .filter { shortcut -> shortcut.`package` == packageName && shortcut.isPinned && shortcut.isEnabled }
        .associate { shortcut -> shortcut.id to shortcutIconCacheToken(shortcut) }
    return shown != incoming
}

// A publisher that updates a shortcut (a new label, or a new icon from a
// site's manifest) bumps its timestamp, so the cached icon is not reused —
// and [pinnedShortcutsChanged] sees the change from key fields alone.
private fun shortcutIconCacheToken(shortcut: ShortcutInfo): String = "shortcut:${shortcut.lastChangedTimestamp}"

/**
 * Brings back the entry for a shortcut the user pins again after hiding it.
 * A repeat request for an already-pinned shortcut changes nothing the system
 * reports, so without this the browser's "Add to Home screen" would succeed
 * and the page would stay hidden.
 *
 * Hidden state is held twice: on disk, and in the running view model's
 * [HiddenAppStore], which only reads the disk at construction. So the reveal
 * edits the disk copy (all a later launch will see) and tells a running view
 * model too, which unhides its own copy and repaints. Both writes remove the
 * same id, so whichever saves last agrees with the other. Reads and writes
 * the store on disk, so call it off the main thread.
 */
internal object PinnedShortcutReveals {
    // Replayed so a view model that subscribes just after a reveal — one
    // whose HiddenAppStore read the file before unhideNow committed — still
    // receives it. Cleared once a view model has applied it ([consumed]), so
    // a later one can't replay it over a hide the user made since.
    private val pending = MutableSharedFlow<String>(replay = 8)
    val requests: SharedFlow<String> = pending

    /** Returns false when the disk write failed, so nothing was revealed for good. */
    fun reveal(context: Context, entryId: String): Boolean {
        if (!HiddenAppStore(context).unhideNow(entryId)) {
            LauncherDebugLog.warning("PinnedShortcutReveals unhide write failed")
            return false
        }
        pending.tryEmit(entryId)
        return true
    }

    /** Called by a view model once it has applied the reveals it received. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumed() {
        pending.resetReplayCache()
    }
}

/**
 * Serializes every change to this launcher's pinned set: a pin accepted in
 * [PinShortcutActivity] and the read-and-replace a Remove does. Remove writes
 * back the whole set for a package (`pinShortcuts` replaces it), so a pin
 * accepted between its read and its write would be dropped by that write.
 * Process-wide, because the two run from different components.
 */
internal val pinnedShortcutSetLock = Mutex()
