package app.typelauncher

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** How many of an app's shortcuts its long-press menu offers, as Launcher3 does. */
internal const val MAX_MENU_SHORTCUTS = 4

/** How many of those four Launcher3 keeps for dynamic shortcuts when there are enough. */
internal const val MAX_MENU_DYNAMIC_SHORTCUTS = 2

/** Which package a set of shortcuts belongs to. */
internal data class AppShortcutKey(val packageName: String, val user: UserHandle)

/**
 * One of an app's own shortcuts ("New message", "New incognito tab") as its
 * long-press menu shows it. [info] is the system record, kept for the icon;
 * null only in tests and previews, which supply icons another way.
 *
 * Labels can be the user's own data — a messenger's dynamic shortcuts name
 * recent contacts — so they live in memory only: never logged, never
 * persisted.
 */
@Immutable
internal data class AppShortcut(
    val id: String,
    val packageName: String,
    val user: UserHandle,
    val label: String,
    // The launcher activity (icon) the shortcut belongs to; an app with two
    // icons gives each its own. Null only in tests and previews.
    val activity: ComponentName? = null,
    // Published at runtime (a recent chat, the last route) rather than in
    // the app's manifest.
    val isDynamic: Boolean = false,
    val lastChangedTimestamp: Long = 0L,
    val info: ShortcutInfo? = null,
)

/**
 * The manifest and dynamic shortcuts of [packageName] in [user], or of every
 * app in [user] when [packageName] is null — one binder call either way. Only
 * the default home app may read them, so a launcher that isn't the default
 * gets none rather than an exception.
 *
 * Throws on a locked or paused profile (`IllegalStateException`), a profile
 * leaving the group (`SecurityException`) and binder failures; [AppShortcutCache]
 * keeps what it had when a read fails.
 */
internal fun readAppShortcuts(
    launcherApps: LauncherApps,
    user: UserHandle,
    packageName: String?,
): List<ShortcutInfo> =
    if (!launcherApps.hasShortcutHostPermission()) {
        emptyList()
    } else {
        val query = LauncherApps.ShortcutQuery().setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC,
        )
        if (packageName != null) query.setPackage(packageName)
        launcherApps.getShortcuts(query, user).orEmpty()
    }

/**
 * Groups [shortcuts] by package and keeps what its menus can show: enabled,
 * labeled shortcuts, static (manifest) ones ahead of dynamic ones, each kind
 * in the app's own rank order. [menuShortcutsFor] then picks one icon's.
 *
 * A shortcut its app disables stops being static or dynamic — it survives
 * only as a pin — so the query rarely returns one; the filter is for the
 * rest. Like Launcher3, the menu never offers what the app withdrew.
 */
internal fun menuShortcuts(
    shortcuts: List<ShortcutInfo>,
    user: UserHandle,
): Map<AppShortcutKey, List<AppShortcut>> = shortcuts
    .groupBy { shortcut -> shortcut.`package` }
    .mapNotNull { (packageName, appShortcuts) ->
        val shown = appShortcuts
            .filter { shortcut -> shortcut.isEnabled }
            .sortedWith(compareBy({ shortcut -> !shortcut.isDeclaredInManifest }, { shortcut -> shortcut.rank }))
            .mapNotNull { shortcut ->
                val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString()?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                AppShortcut(
                    id = shortcut.id,
                    packageName = packageName,
                    user = user,
                    label = label,
                    activity = shortcut.activity,
                    isDynamic = shortcut.isDynamic,
                    lastChangedTimestamp = shortcut.lastChangedTimestamp,
                    info = shortcut,
                )
            }
            .distinctBy { shortcut -> shortcut.id }
        if (shown.isEmpty()) null else AppShortcutKey(packageName, user) to shown
    }
    .toMap()

/**
 * The shortcuts [app]'s menu shows: those of its package that belong to its
 * own launcher activity, at most [MAX_MENU_SHORTCUTS]. A package with two
 * icons (a phone app's Phone and Contacts) attaches each shortcut to one of
 * them, and the other icon's menu must not offer it.
 *
 * The cap is Launcher3's (`PopupPopulator`): static shortcuts lead, but up to
 * [MAX_MENU_DYNAMIC_SHORTCUTS] dynamic ones replace the lowest-ranked static
 * ones, so an app with four static shortcuts still shows its contextual ones.
 */
internal fun menuShortcutsFor(
    app: InstalledApp,
    byPackage: Map<AppShortcutKey, List<AppShortcut>>,
): List<AppShortcut> {
    val component = app.launchIntent.component
    return byPackage[AppShortcutKey(app.packageName, app.user)].orEmpty()
        .filter { shortcut -> component == null || shortcut.activity == null || shortcut.activity == component }
        .let(::capMenuShortcuts)
}

private fun capMenuShortcuts(sorted: List<AppShortcut>): List<AppShortcut> {
    val shown = ArrayList<AppShortcut>(MAX_MENU_SHORTCUTS)
    var dynamicCount = 0
    sorted.forEach { shortcut ->
        if (shown.size < MAX_MENU_SHORTCUTS) {
            shown += shortcut
            if (shortcut.isDynamic) dynamicCount++
        } else if (shortcut.isDynamic && dynamicCount < MAX_MENU_DYNAMIC_SHORTCUTS) {
            // Statics sort first, so the last static sits just before the
            // dynamic ones already taken.
            dynamicCount++
            shown.removeAt(shown.size - dynamicCount)
            shown += shortcut
        }
    }
    return shown
}

/**
 * Every app's menu shortcuts, read off the main thread ahead of time so a
 * long-press never waits on a binder call: the whole set once per app-list
 * load, and one app's again whenever it republishes them.
 *
 * Reads are serialized, so a refresh of one app can't be overwritten by an
 * older full read that finishes after it.
 */
internal class AppShortcutCache(
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    // Throws on failure, like [readAppShortcuts].
    private val read: (user: UserHandle, packageName: String?) -> List<ShortcutInfo>,
) {
    private val state = MutableStateFlow<Map<AppShortcutKey, List<AppShortcut>>>(emptyMap())
    val shortcuts: StateFlow<Map<AppShortcutKey, List<AppShortcut>>> = state
    private val readLock = Mutex()
    private var refreshAllJob: Job? = null

    /**
     * Rereads every app's shortcuts for each profile in [profiles] (true for
     * a paused profile, whose apps show none). A profile that is gone drops
     * its entries; one whose read fails keeps the ones it had. Call from the
     * main thread; a newer call supersedes an unfinished one.
     */
    fun refreshAll(profiles: Map<UserHandle, Boolean>) {
        refreshAllJob?.cancel()
        refreshAllJob = scope.launch {
            readLock.withLock {
                val reads = withContext(ioDispatcher) {
                    profiles.mapValues { (user, isPaused) ->
                        if (isPaused) emptyMap() else readOrNull { read(user, null) }?.let { menuShortcuts(it, user) }
                    }
                }
                state.update { current ->
                    buildMap {
                        reads.forEach { (user, read) ->
                            putAll(read ?: current.filterKeys { key -> key.user == user })
                        }
                    }
                }
            }
        }
    }

    /** Rereads [packageName]'s shortcuts in [user], e.g. after it republished them. */
    fun refreshPackage(packageName: String, user: UserHandle) {
        scope.launch {
            readLock.withLock {
                val read = withContext(ioDispatcher) {
                    readOrNull { read(user, packageName) }
                        ?.filter { shortcut -> shortcut.`package` == packageName }
                } ?: return@withLock
                val key = AppShortcutKey(packageName, user)
                val shown = menuShortcuts(read, user)[key]
                state.update { current -> if (shown == null) current - key else current + (key to shown) }
            }
        }
    }

    private inline fun readOrNull(block: () -> List<ShortcutInfo>): List<ShortcutInfo>? = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RuntimeException) {
        // Operation only: which apps have which shortcuts is the user's own.
        LauncherDebugLog.failure(exception, "readAppShortcuts failed")
        null
    }
}

/**
 * Menu-sized icons for [AppShortcut]s, rasterized off the main thread and
 * kept in a small in-memory cache: a menu is reopened far more often than an
 * app changes its shortcuts. Keyed by the shortcut's last-changed time, so an
 * app that updates one gets its new icon.
 *
 * An icon can come from any resource qualifier (`-night`, `-land`, `-ldrtl`,
 * a locale…), and this loader outlives the activity a configuration change
 * recreates, so [clear] drops everything on any such change rather than
 * keying on a hand-picked subset of qualifiers.
 */
internal class AppShortcutIconLoader(
    private val ioDispatcher: CoroutineDispatcher,
    private val drawable: (ShortcutInfo) -> android.graphics.drawable.Drawable?,
) {
    private val cache = LruCache<String, ImageBitmap>(ICON_CACHE_ENTRIES)
    // Bumped by [clear], so a load that started under the old configuration
    // doesn't put its bitmap back afterwards.
    @Volatile private var generation = 0

    suspend fun load(shortcut: AppShortcut, sizePx: Int): ImageBitmap? {
        val info = shortcut.info ?: return null
        val key = "${shortcut.user.hashCode()}:${shortcut.packageName}/${shortcut.id}@${shortcut.lastChangedTimestamp}:$sizePx"
        cache.get(key)?.let { return it }
        val startedIn = generation
        val bitmap = withContext(ioDispatcher) {
            try {
                drawable(info)?.let { icon ->
                    IconNormalizer.normalizeToTile(icon, sizePx, shortcut.packageName, null).asImageBitmap()
                }
            } catch (exception: RuntimeException) {
                // The app's drawable code ran here, and it can throw (a
                // resource gone stale mid-update). No icon is the fallback.
                LauncherDebugLog.failure(exception, "loadAppShortcutIcon failed")
                null
            }
        } ?: return null
        if (generation == startedIn) cache.put(key, bitmap)
        return bitmap
    }

    fun clear() {
        generation++
        cache.evictAll()
    }

    private companion object {
        const val ICON_CACHE_ENTRIES = 48
    }
}
