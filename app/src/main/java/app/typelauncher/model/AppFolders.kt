package app.typelauncher

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Immutable

/**
 * The built-in folders the app list can group apps into. The user can delete
 * any of them but [Other] and add folders of their own (see [AppFolderDef]).
 *
 * Declared alphabetically in English, then [Other] last. Folder order is
 * deliberately independent of the locale's collation so that a folder doesn't
 * move when the language changes.
 *
 * [storageKey] is what [AppFolderStore] persists; it never changes once
 * shipped, whatever the constant is renamed to.
 */
internal enum class AppCategory(val storageKey: String, val icon: FolderIcon) {
    Finance("finance", FolderIcon.Paid),
    Games("games", FolderIcon.SportsEsports),
    Health("health", FolderIcon.Favorite),
    Home("home", FolderIcon.Home),
    Learning("learning", FolderIcon.School),
    Media("media", FolderIcon.PlayCircle),
    News("news", FolderIcon.Newspaper),
    Productivity("productivity", FolderIcon.Work),
    Shopping("shopping", FolderIcon.ShoppingBag),
    Social("social", FolderIcon.Chat),
    Tools("tools", FolderIcon.Build),
    Travel("travel", FolderIcon.Travel),

    // Every app no other folder takes (see `groupAppList`).
    Other("other", FolderIcon.MoreHoriz),
    ;

    companion object {
        fun fromStorageKey(key: String): AppCategory? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * The glyphs a folder can show: the built-in folders' own, then a few more to
 * choose from for a folder the user makes. [storageKey] is the Material
 * Symbol's name, persisted for user-made folders; it never changes once
 * shipped.
 */
internal enum class FolderIcon(val storageKey: String) {
    Folder("folder"),
    Star("star"),
    Paid("paid"),
    SportsEsports("sports_esports"),
    Favorite("favorite"),
    Home("home"),
    School("school"),
    PlayCircle("play_circle"),
    Newspaper("newspaper"),
    Work("work"),
    ShoppingBag("shopping_bag"),
    Chat("chat"),
    Build("build"),
    Travel("travel"),
    DirectionsBus("directions_bus"),
    Train("train"),
    DirectionsCar("directions_car"),
    Flight("flight"),
    Restaurant("restaurant"),
    Language("language"),
    Translate("translate"),
    SportsSoccer("sports_soccer"),
    FitnessCenter("fitness_center"),
    Apartment("apartment"),
    Code("code"),
    Palette("palette"),
    FamilyRestroom("family_restroom"),
    ChildCare("child_care"),
    Shield("shield"),
    Lock("lock"),
    Smartphone("smartphone"),
    AccountBalance("account_balance"),
    Badge("badge"),
    Flag("flag"),
    MenuBook("menu_book"),
    PartlyCloudyDay("partly_cloudy_day"),
    Eco("eco"),
    MusicNote("music_note"),
    PhotoCamera("photo_camera"),
    Pets("pets"),
    MoreHoriz("more_horiz"),
    ;

    companion object {
        fun fromStorageKey(key: String): FolderIcon? = entries.firstOrNull { it.storageKey == key }
    }
}

/**
 * One folder the app list can group into: a built-in [category], or one the
 * user made, with its own [name] and [icon]. [key] is what filings refer to:
 * the category's storage key, or `custom-<n>` for a user-made folder.
 */
@Immutable
internal data class AppFolderDef(
    val key: String,
    val category: AppCategory?,
    val name: String,
    val icon: FolderIcon,
) {
    val isOther: Boolean get() = category == AppCategory.Other

    companion object {
        fun builtIn(category: AppCategory) = AppFolderDef(category.storageKey, category, "", category.icon)
    }
}

/**
 * The folders in use, in the order the list shows them: the built-in folders
 * the user hasn't deleted and the user's own, together in alphabetical order
 * (built-ins by their English name, so the order holds across languages), then
 * Other, which can't be deleted.
 */
internal fun activeAppFolders(custom: List<AppFolderDef>, deletedBuiltIns: Set<String>): List<AppFolderDef> {
    val builtIns = AppCategory.entries
        .filter { it != AppCategory.Other && it.storageKey !in deletedBuiltIns }
        .map(AppFolderDef::builtIn)
    return (builtIns + custom).sortedBy { (it.category?.storageKey ?: it.name).lowercase() } +
        AppFolderDef.builtIn(AppCategory.Other)
}

/**
 * The folder an app's own manifest puts it in (`android:appCategory`), or null
 * when it declares none we map. Most apps declare nothing, and the platform's
 * list has no finance, shopping or health. [ApplicationInfo.CATEGORY_PRODUCTIVITY]
 * is ignored too: with nothing better to choose, it's Android's junk drawer,
 * where banks, brokers and wallets end up (maintainer, 2026-11-19). Apps with
 * no category here go to Other until the user or AI sorting files them.
 * [ApplicationInfo.FLAG_IS_GAME] is the older way a game says so, and still
 * the only one some games use.
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
    else -> if (flags and FLAG_IS_GAME != 0) AppCategory.Games else null
}

// Deprecated in favor of CATEGORY_GAME, but games built before API 26 still set
// only this, so it is read on purpose.
@Suppress("DEPRECATION")
private const val FLAG_IS_GAME = ApplicationInfo.FLAG_IS_GAME

/** One folder in the app list, with its members in the list's own order. */
@Immutable
internal data class AppListFolder(
    val folder: AppFolderDef,
    val apps: List<InstalledApp>,
) {
    val id: String get() = "folder:${folder.key}"
}

/** The app list split into folders and the apps left loose beside them. */
@Immutable
internal data class AppListGrouping(
    val folders: List<AppListFolder>,
    val looseApps: List<InstalledApp>,
)

/**
 * The key of the folder [app] is filed in among [activeKeys], or null for "no
 * folder": where the user put it, if they did ([assignments] maps an app id to
 * a folder key, or to null for "no folder"); otherwise where "Sort with
 * on-device AI" put it ([aiAssignments]), which the caller passes only while
 * that's on; otherwise where its manifest says
 * ([InstalledApp.declaredCategory]); otherwise Other. A choice naming a folder
 * that's since been deleted is passed over. The AI comes before the manifest
 * because manifests are often wrong: a broker or bank declaring
 * "productivity", say.
 */
internal fun effectiveFolderKey(
    app: InstalledApp,
    activeKeys: Set<String>,
    assignments: Map<String, String?>,
    aiAssignments: Map<String, String> = emptyMap(),
): String? {
    if (assignments.containsKey(app.id)) {
        val chosen = assignments[app.id] ?: return null
        if (chosen in activeKeys) return chosen
    }
    aiAssignments[app.id]?.takeIf { it in activeKeys }?.let { return it }
    app.declaredCategory?.storageKey?.takeIf { it in activeKeys }?.let { return it }
    return AppCategory.Other.storageKey
}

/**
 * Groups [apps] into [folders] (from [activeAppFolders]), each app where
 * [effectiveFolderKey] puts it.
 *
 * A folder needs at least [minMembers] apps, so a lone game doesn't get a
 * folder of its own. A folder the user filed an app into always shows, even
 * with one app, since they asked for it.
 *
 * Every app left over, with no folder or one too small to show, goes in Other.
 * Only "no folder" keeps an app loose. When Other would be the only folder, it
 * is dropped and its apps stay loose: a list that is one folder is just the
 * list behind an extra tap.
 *
 * Members and loose apps keep the order [apps] arrived in, so the list's sort
 * order still applies inside each folder. Folders are in [folders] order.
 */
internal fun groupAppList(
    apps: List<InstalledApp>,
    folders: List<AppFolderDef>,
    assignments: Map<String, String?>,
    aiAssignments: Map<String, String> = emptyMap(),
    minMembers: Int = MIN_APP_FOLDER_MEMBERS,
): AppListGrouping {
    val other = AppCategory.Other.storageKey
    val activeKeys = folders.mapTo(HashSet()) { it.key }
    // Each app's folder before the size rule; "no folder" apps are absent.
    val keyOf = HashMap<String, String>(apps.size)
    val counts = HashMap<String, Int>()
    val chosen = HashSet<String>()
    for (app in apps) {
        val key = effectiveFolderKey(app, activeKeys, assignments, aiAssignments) ?: continue
        if (assignments[app.id] == key) chosen += key
        keyOf[app.id] = key
        counts[key] = (counts[key] ?: 0) + 1
    }
    val shown = folders.mapNotNullTo(HashSet()) { folder ->
        val count = counts[folder.key] ?: 0
        folder.key.takeIf { it != other && (count >= minMembers || (count > 0 && it in chosen)) }
    }
    // One pass in list order: each app joins its folder if that folder shows,
    // else Other (unless it was kept out of folders).
    val members = HashMap<String, MutableList<InstalledApp>>()
    for (app in apps) {
        val key = keyOf[app.id] ?: continue
        members.getOrPut(if (key in shown) key else other) { mutableListOf() } += app
    }
    val otherShows = shown.isNotEmpty() || other in chosen
    val grouped = folders.mapNotNull { folder ->
        val folderApps = members[folder.key] ?: return@mapNotNull null
        if (folder.key == other && !otherShows) return@mapNotNull null
        AppListFolder(folder, folderApps)
    }
    val foldered = grouped.flatMapTo(HashSet()) { folder -> folder.apps.map { it.id } }
    return AppListGrouping(
        folders = grouped,
        looseApps = if (foldered.isEmpty()) apps else apps.filter { it.id !in foldered },
    )
}

internal const val MIN_APP_FOLDER_MEMBERS = 2
