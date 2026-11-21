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
    Ai("ai", FolderIcon.AutoAwesome),
    Betting("betting", FolderIcon.Casino),
    Car("car", FolderIcon.DirectionsCar),
    // "Charity & giving" (maintainer, 2026-11-21).
    Charity("charity", FolderIcon.VolunteerActivism),
    Code("code", FolderIcon.Code),
    Cycling("cycling", FolderIcon.PedalBike),
    Design("design", FolderIcon.Palette),
    Events("events", FolderIcon.ConfirmationNumber),
    // "Family history & genealogy" (maintainer, 2026-11-21).
    FamilyHistory("family_history", FolderIcon.FamilyHistory),
    Finance("finance", FolderIcon.Paid),
    Food("food", FolderIcon.Restaurant),
    Games("games", FolderIcon.SportsEsports),
    Government("government", FolderIcon.AccountBalance),
    Health("health", FolderIcon.Favorite),
    Home("home", FolderIcon.Home),
    Hotels("hotels", FolderIcon.Hotel),
    Internet("internet", FolderIcon.Language),
    Jobs("jobs", FolderIcon.Work),
    Kids("kids", FolderIcon.ChildCare),
    Learning("learning", FolderIcon.School),
    Maps("maps", FolderIcon.Map),
    // Live TV's screen (maintainer, 2026-11-21).
    Media("media", FolderIcon.LiveTv),
    // "Meetings & video calls" (maintainer, 2026-11-21).
    Meetings("meetings", FolderIcon.VideoCameraFront),
    Music("music", FolderIcon.MusicNote),
    News("news", FolderIcon.Newspaper),
    // "Notes & tasks" (maintainer, 2026-11-21).
    Notes("notes", FolderIcon.EditNote),
    Office("office", FolderIcon.Business),
    Pets("pets", FolderIcon.Pets),
    Phone("phone", FolderIcon.Call),
    Photos("photos", FolderIcon.PhotoCamera),
    Post("post", FolderIcon.MarkunreadMailbox),
    // "Print & fax" (maintainer, 2026-11-21).
    Print("print", FolderIcon.Print),
    // "Public transit & rail"; keyed so it sorts where its English name does.
    Transit("public_transit", FolderIcon.Train),
    Security("security", FolderIcon.Lock),
    Reading("reading", FolderIcon.MenuBook),
    Rewards("rewards", FolderIcon.Loyalty),
    Shopping("shopping", FolderIcon.ShoppingBag),
    Social("social", FolderIcon.Chat),
    Sports("sports", FolderIcon.SportsSoccer),
    Tools("tools", FolderIcon.Build),
    Travel("travel", FolderIcon.Travel),
    Weather("weather", FolderIcon.PartlyCloudyDay),

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
    Business("business"),
    ShoppingBag("shopping_bag"),
    Chat("chat"),
    Build("build"),
    Travel("travel"),
    DirectionsBus("directions_bus"),
    Train("train"),
    DirectionsCar("directions_car"),
    PedalBike("pedal_bike"),
    Hotel("hotel"),
    Map("map"),
    VolunteerActivism("volunteer_activism"),
    FamilyHistory("family_history"),
    EditNote("edit_note"),
    VideoCameraFront("video_camera_front"),
    LiveTv("live_tv"),
    Print("print"),
    Flight("flight"),
    Restaurant("restaurant"),
    Language("language"),
    Translate("translate"),
    SportsSoccer("sports_soccer"),
    FitnessCenter("fitness_center"),
    Apartment("apartment"),
    Code("code"),
    AutoAwesome("auto_awesome"),
    Palette("palette"),
    FamilyRestroom("family_restroom"),
    ChildCare("child_care"),
    Shield("shield"),
    Lock("lock"),
    Call("call"),
    Casino("casino"),
    ConfirmationNumber("confirmation_number"),
    Loyalty("loyalty"),
    MarkunreadMailbox("markunread_mailbox"),
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
    ApplicationInfo.CATEGORY_AUDIO -> AppCategory.Music
    ApplicationInfo.CATEGORY_VIDEO -> AppCategory.Media
    ApplicationInfo.CATEGORY_IMAGE -> AppCategory.Photos
    ApplicationInfo.CATEGORY_SOCIAL -> AppCategory.Social
    ApplicationInfo.CATEGORY_NEWS -> AppCategory.News
    ApplicationInfo.CATEGORY_MAPS -> AppCategory.Maps
    else -> if (flags and FLAG_IS_GAME != 0) AppCategory.Games else null
}

/**
 * The folder the rules put [this] app in ahead of the AI: the well-known apps
 * table and name words ([knownCategory]), then a manifest that says the app
 * is a game. A game says so plainly, unlike the junk-drawer categories, and
 * the AI filed Mini Metro under Transport and The Darkside Detective under
 * Other (maintainer, 2026-11-19).
 */
internal fun InstalledApp.ruledCategory(): AppCategory? {
    val game = declaredCategory?.takeIf { it == AppCategory.Games }
    // A game manifest beats a mere word in the name: "Train Sim World" is a
    // game, not a SIM card app (maintainer, 2026-11-21).
    if (game != null && isPlacedByNameWord(if (isShortcut) "" else packageName, name)) return game
    return knownCategory() ?: game
}

/**
 * The folder the rules would pick for [this] app, for AI sorting to weigh
 * (see `AiSortInput.suggested`): [ruledCategory], then its manifest, then a
 * guess from its words.
 */
internal fun InstalledApp.suggestedCategory(): AppCategory? =
    ruledCategory() ?: declaredCategory ?: guessedCategory()

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
 * a folder key, or to null for "no folder"); otherwise, for a well-known app
 * or one whose name says what it is, where [knownAppCategory] says, and
 * for one whose manifest says it's a game, Games ([ruledCategory]);
 * otherwise where its Google Play category says ([playCategories], package to
 * Play code, from "Sort using Google Play"; see [folderForPlayCategory]);
 * otherwise where "Sort with on-device AI" put it ([aiAssignments]), which the caller passes only while
 * that's on; otherwise where its manifest says
 * ([InstalledApp.declaredCategory]); otherwise where words in its package or
 * name suggest ([guessedAppCategory]); otherwise Other. A choice naming a folder
 * that's since been deleted is passed over. The AI comes before the manifest
 * because manifests are often wrong: a broker or bank declaring
 * "productivity", say.
 */
internal fun effectiveFolderKey(
    app: InstalledApp,
    activeKeys: Set<String>,
    assignments: Map<String, String?>,
    aiAssignments: Map<String, String> = emptyMap(),
    playCategories: Map<String, String> = emptyMap(),
): String? {
    if (assignments.containsKey(app.id)) {
        val chosen = assignments[app.id] ?: return null
        if (chosen in activeKeys) return chosen
    }
    val aiChoice = aiAssignments[app.id]?.takeIf { it in activeKeys }
    // The AI's pick of a folder the user made beats the table: the user
    // asked for that folder, and the table can't know about it.
    if (aiChoice != null && aiChoice.startsWith(USER_FOLDER_KEY_PREFIX)) return aiChoice
    app.ruledCategory()?.storageKey?.takeIf { it in activeKeys }?.let { return it }
    app.playCategoryIn(playCategories)?.let(::folderForPlayCategory)?.storageKey?.takeIf { it in activeKeys }
        ?.let { return it }
    aiChoice?.let { return it }
    app.declaredCategory?.storageKey?.takeIf { it in activeKeys }?.let { return it }
    app.guessedCategory()?.storageKey?.takeIf { it in activeKeys }?.let { return it }
    return AppCategory.Other.storageKey
}

/**
 * [this] app's Play category from [playCategories], or null for a pinned
 * website: it carries its browser's package, which says nothing about the
 * site (Codex on PR #890; see [knownCategory]).
 */
internal fun InstalledApp.playCategoryIn(playCategories: Map<String, String>): String? =
    if (isShortcut) null else playCategories[packageName]

/** What put an app in its folder, in [effectiveFolderKey]'s order. */
internal enum class FolderSource {
    /** The user filed it, or chose "No folder". */
    User,

    /** A rule by package, publisher or brand, or a manifest saying it's a game. */
    Rule,

    /** A word in its name ("Greenfield Bank"), by the rules. */
    NameWord,

    /** Its Google Play category, looked up or from the shipped catalog. */
    GooglePlay,

    /** "Sort with on-device AI". */
    Ai,

    /** Its manifest's category. */
    Manifest,

    /** A guess from words in its package or name. */
    Guess,

    /** Nothing placed it: Other. */
    None,
    ;

    /** True for the placements "Check guessed folders" asks about. */
    val isGuess: Boolean get() = this == NameWord || this == Ai || this == Guess
}

/**
 * What puts [app] where [effectiveFolderKey] does, given the same inputs.
 * Mirrors it step for step; `FolderSourceTest` holds the two together.
 */
internal fun folderSource(
    app: InstalledApp,
    activeKeys: Set<String>,
    assignments: Map<String, String?>,
    aiAssignments: Map<String, String> = emptyMap(),
    playCategories: Map<String, String> = emptyMap(),
): FolderSource {
    if (assignments.containsKey(app.id)) {
        val chosen = assignments[app.id] ?: return FolderSource.User
        if (chosen in activeKeys) return FolderSource.User
    }
    val aiChoice = aiAssignments[app.id]?.takeIf { it in activeKeys }
    if (aiChoice != null && aiChoice.startsWith(USER_FOLDER_KEY_PREFIX)) return FolderSource.Ai
    if (app.ruledCategory()?.storageKey?.takeIf { it in activeKeys } != null) {
        // A game manifest beats a name word (see ruledCategory).
        val byNameWord = app.declaredCategory != AppCategory.Games &&
            isPlacedByNameWord(if (app.isShortcut) "" else app.packageName, app.name)
        return if (byNameWord) FolderSource.NameWord else FolderSource.Rule
    }
    if (app.playCategoryIn(playCategories)?.let(::folderForPlayCategory)?.storageKey?.takeIf { it in activeKeys } != null) {
        return FolderSource.GooglePlay
    }
    if (aiChoice != null) return FolderSource.Ai
    if (app.declaredCategory?.storageKey?.takeIf { it in activeKeys } != null) return FolderSource.Manifest
    if (app.guessedCategory()?.storageKey?.takeIf { it in activeKeys } != null) return FolderSource.Guess
    return FolderSource.None
}

/**
 * Groups [apps] into [folders] (from [activeAppFolders]), each app where
 * [effectiveFolderKey] puts it. A folder shows as soon as it has an app,
 * even just one (maintainer, 2026-11-19: no minimum); one with none doesn't.
 *
 * Every app with no folder goes in Other. Only "no folder" keeps an app
 * loose. When Other would be the only folder, it is dropped and its apps stay
 * loose: a list that is one folder is just the list behind an extra tap.
 *
 * Members and loose apps keep the order [apps] arrived in, so the list's sort
 * order still applies inside each folder. Folders are in [folders] order.
 */
internal fun groupAppList(
    apps: List<InstalledApp>,
    folders: List<AppFolderDef>,
    assignments: Map<String, String?>,
    aiAssignments: Map<String, String> = emptyMap(),
    playCategories: Map<String, String> = emptyMap(),
): AppListGrouping {
    val other = AppCategory.Other.storageKey
    val activeKeys = folders.mapTo(HashSet()) { it.key }
    // "No folder" apps are absent.
    val members = HashMap<String, MutableList<InstalledApp>>()
    var otherChosen = false
    for (app in apps) {
        val key = effectiveFolderKey(app, activeKeys, assignments, aiAssignments, playCategories) ?: continue
        if (key == other && assignments[app.id] == other) otherChosen = true
        members.getOrPut(key) { mutableListOf() } += app
    }
    val otherShows = otherChosen || members.keys.any { it != other }
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

/** The key prefix of a folder the user made (see `AppFolderStore.createFolder`). */
internal const val USER_FOLDER_KEY_PREFIX = "custom-"

/**
 * Whether typing [query] finds the folder called [label]: the start of its
 * name or of any word in it, ignoring case ("gam" finds Games, "anim" finds
 * Pets & animals).
 */
internal fun folderNameMatches(label: String, query: String): Boolean {
    val wanted = query.trim().lowercase()
    if (wanted.isEmpty()) return false
    val name = label.lowercase()
    return name.startsWith(wanted) || name.split(FOLDER_NAME_WORD_BREAK).any { it.startsWith(wanted) }
}

private val FOLDER_NAME_WORD_BREAK = Regex("""[^\p{L}\p{N}]+""")
