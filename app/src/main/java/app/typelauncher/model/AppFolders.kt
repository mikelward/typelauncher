package app.typelauncher

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable

/**
 * The folders the app list can group apps into. A fixed, short set rather than
 * user-named folders: the list groups itself, so the names have to be ones a
 * classifier (today the app's own manifest category, later an on-device model)
 * can pick from.
 *
 * Declared in the order folders are listed: alphabetical in English, then
 * [Other] last. Folder order is deliberately independent of the locale's
 * collation so that a folder doesn't move when the language changes.
 *
 * [storageKey] is what [AppFolderStore] persists; it never changes once
 * shipped, whatever the constant is renamed to.
 */
internal enum class AppCategory(val storageKey: String) {
    Finance("finance"),
    Games("games"),
    Health("health"),
    Media("media"),
    News("news"),
    Productivity("productivity"),
    Shopping("shopping"),
    Social("social"),
    Tools("tools"),
    Travel("travel"),

    // Every app no other folder takes (see `groupAppList`).
    Other("other"),
    ;

    companion object {
        fun fromStorageKey(key: String): AppCategory? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * The folder an app's own manifest puts it in (`android:appCategory`), or null
 * when it declares none we map. Most apps declare nothing, and the platform's
 * list has no finance, shopping or health; those apps stay loose until the user
 * (or a later classifier) files them. [ApplicationInfo.FLAG_IS_GAME] is the
 * older way a game says so, and still the only one some games use.
 */
internal fun declaredAppCategory(category: Int, flags: Int): AppCategory? = when (category) {
    ApplicationInfo.CATEGORY_GAME -> AppCategory.Games
    ApplicationInfo.CATEGORY_AUDIO,
    ApplicationInfo.CATEGORY_VIDEO,
    ApplicationInfo.CATEGORY_IMAGE,
    -> AppCategory.Media
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.Social
    ApplicationInfo.CATEGORY_NEWS -> AppCategory.News
    ApplicationInfo.CATEGORY_MAPS -> AppCategory.Travel
    ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppCategory.Productivity
    else -> if (flags and FLAG_IS_GAME != 0) AppCategory.Games else null
}

// Deprecated in favor of CATEGORY_GAME, but games built before API 26 still set
// only this, so it is read on purpose.
@Suppress("DEPRECATION")
private const val FLAG_IS_GAME = ApplicationInfo.FLAG_IS_GAME

/** One folder in the app list, with its members in the list's own order. */
@Immutable
internal data class AppListFolder(
    val category: AppCategory,
    val apps: List<InstalledApp>,
) {
    val id: String get() = "folder:${category.storageKey}"
}

/** The app list split into folders and the apps left loose beside them. */
@Immutable
internal data class AppListGrouping(
    val folders: List<AppListFolder>,
    val looseApps: List<InstalledApp>,
)

/**
 * Groups [apps] into folders. An app goes where the user put it, if they did
 * ([assignments] maps an app id to its folder, or to null for "no folder");
 * otherwise where its manifest says ([InstalledApp.declaredCategory]);
 * otherwise where "Sort with on-device AI" put it ([aiAssignments]).
 *
 * A folder needs at least [minMembers] apps, so a lone game doesn't get a
 * folder of its own. A folder the user filed an app into always shows, even
 * with one app, since they asked for it.
 *
 * Every app left over, with no category or one too small for a folder, goes
 * in [AppCategory.Other]. Only "no folder" keeps an app loose. When Other
 * would be the only folder, it is dropped and its apps stay loose: a list
 * that is one folder is just the list behind an extra tap.
 *
 * Members and loose apps keep the order [apps] arrived in, so the list's sort
 * order still applies inside each folder. Folders are in [AppCategory] order.
 */
internal fun groupAppList(
    apps: List<InstalledApp>,
    assignments: Map<String, AppCategory?>,
    aiAssignments: Map<String, AppCategory> = emptyMap(),
    minMembers: Int = MIN_APP_FOLDER_MEMBERS,
): AppListGrouping {
    // Each app's folder before the size rule; "no folder" apps are absent.
    val categoryOf = HashMap<String, AppCategory>(apps.size)
    val counts = HashMap<AppCategory, Int>()
    val chosen = HashSet<AppCategory>()
    for (app in apps) {
        val category = if (assignments.containsKey(app.id)) {
            assignments[app.id]?.also { chosen += it } ?: continue
        } else {
            app.declaredCategory ?: aiAssignments[app.id] ?: AppCategory.Other
        }
        categoryOf[app.id] = category
        counts[category] = (counts[category] ?: 0) + 1
    }
    val shown = AppCategory.entries.filterTo(HashSet()) { category ->
        val count = counts[category] ?: 0
        category != AppCategory.Other && (count >= minMembers || (count > 0 && category in chosen))
    }
    // One pass in list order: each app joins its folder if that folder shows,
    // else Other (unless it was kept out of folders).
    val members = HashMap<AppCategory, MutableList<InstalledApp>>()
    for (app in apps) {
        val category = categoryOf[app.id] ?: continue
        val folder = if (category in shown) category else AppCategory.Other
        members.getOrPut(folder) { mutableListOf() } += app
    }
    val otherShows = shown.isNotEmpty() || AppCategory.Other in chosen
    val folders = AppCategory.entries.mapNotNull { category ->
        val folderApps = members[category] ?: return@mapNotNull null
        if (category == AppCategory.Other && !otherShows) return@mapNotNull null
        AppListFolder(category, folderApps)
    }
    val foldered = folders.flatMapTo(HashSet()) { folder -> folder.apps.map { it.id } }
    return AppListGrouping(
        folders = folders,
        looseApps = if (foldered.isEmpty()) apps else apps.filter { it.id !in foldered },
    )
}

internal const val MIN_APP_FOLDER_MEMBERS = 2
