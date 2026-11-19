package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** How the app list splits into folders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppFoldersTest {
    @Test
    fun declaredCategory_mapsManifestCategories() {
        assertEquals(AppCategory.Games, declaredAppCategory(ApplicationInfo.CATEGORY_GAME, 0))
        assertEquals(AppCategory.Media, declaredAppCategory(ApplicationInfo.CATEGORY_AUDIO, 0))
        assertEquals(AppCategory.Media, declaredAppCategory(ApplicationInfo.CATEGORY_VIDEO, 0))
        assertEquals(AppCategory.Media, declaredAppCategory(ApplicationInfo.CATEGORY_IMAGE, 0))
        assertEquals(AppCategory.Social, declaredAppCategory(ApplicationInfo.CATEGORY_SOCIAL, 0))
        assertEquals(AppCategory.News, declaredAppCategory(ApplicationInfo.CATEGORY_NEWS, 0))
        assertEquals(AppCategory.Travel, declaredAppCategory(ApplicationInfo.CATEGORY_MAPS, 0))
    }

    @Test
    fun declaredCategory_undefinedStaysLoose() {
        assertNull(declaredAppCategory(ApplicationInfo.CATEGORY_UNDEFINED, 0))
        // No folder of ours fits accessibility apps.
        assertNull(declaredAppCategory(ApplicationInfo.CATEGORY_ACCESSIBILITY, 0))
        // Android's junk drawer: banks, brokers and wallets declare it too.
        assertNull(declaredAppCategory(ApplicationInfo.CATEGORY_PRODUCTIVITY, 0))
    }

    @Test
    @Suppress("DEPRECATION")
    fun declaredCategory_legacyGameFlagIsAGame() {
        assertEquals(
            AppCategory.Games,
            declaredAppCategory(ApplicationInfo.CATEGORY_UNDEFINED, ApplicationInfo.FLAG_IS_GAME),
        )
    }

    @Test
    fun groups_appsWithTheSameCategory_andTheRestInOther() {
        val chess = app("Chess", AppCategory.Games)
        val mail = app("Mail")
        val sudoku = app("Sudoku", AppCategory.Games)

        val grouping = group(listOf(chess, mail, sudoku), emptyMap())

        assertEquals(
            listOf(
                folder(AppCategory.Games, listOf(chess, sudoku)),
                folder(AppCategory.Other, listOf(mail)),
            ),
            grouping.folders,
        )
        assertEquals(emptyList<InstalledApp>(), grouping.looseApps)
    }

    @Test
    fun loneAppInACategory_joinsOther_inListOrder() {
        val chess = app("Chess", AppCategory.Games)
        val mail = app("Mail")
        val chat = app("Chat", AppCategory.Social)
        val zoom = app("Zoom", AppCategory.Social)

        val grouping = group(listOf(chess, mail, chat, zoom), emptyMap())

        assertEquals(listOf(AppCategory.Social, AppCategory.Other), grouping.folders.map { it.category })
        assertEquals(listOf(chess, mail), grouping.folders[1].apps)
    }

    @Test
    fun otherAlone_isDropped_andItsAppsStayLoose() {
        // A list that is one folder is just the list behind an extra tap.
        val chess = app("Chess", AppCategory.Games)
        val mail = app("Mail")

        val grouping = group(listOf(chess, mail), emptyMap())

        assertEquals(emptyList<AppListFolder>(), grouping.folders)
        assertEquals(listOf(chess, mail), grouping.looseApps)
    }

    @Test
    fun userFiling_overridesTheManifest_andShowsEvenAlone() {
        val bank = app("Bank")
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku", AppCategory.Games)

        val grouping = group(
            listOf(bank, chess, sudoku),
            mapOf(bank.id to AppCategory.Finance, chess.id to null),
        )

        // Bank: filed by the user, so its folder shows with one app. Chess:
        // "No folder", so loose. That leaves Sudoku alone, so it goes in Other.
        assertEquals(
            listOf(
                folder(AppCategory.Finance, listOf(bank)),
                folder(AppCategory.Other, listOf(sudoku)),
            ),
            grouping.folders,
        )
        assertEquals(listOf(chess), grouping.looseApps)
    }

    @Test
    fun foldersFollowCategoryOrder_membersFollowListOrder() {
        val zoom = app("Zoom", AppCategory.Social)
        val chess = app("Chess", AppCategory.Games)
        val chat = app("Chat", AppCategory.Social)
        val sudoku = app("Sudoku", AppCategory.Games)

        val grouping = group(listOf(zoom, chess, chat, sudoku), emptyMap())

        assertEquals(listOf(AppCategory.Games, AppCategory.Social), grouping.folders.map { it.category })
        assertEquals(listOf(zoom, chat), grouping.folders[1].apps)
    }

    @Test
    fun aiChoice_overridesTheManifest_butNeverTheUser() {
        // A broker declaring "productivity" is the AI's to correct.
        val bank = app("Bank", AppCategory.Productivity)
        val wallet = app("Wallet")
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku", AppCategory.Games)
        val notes = app("Notes")

        val grouping = group(
            listOf(bank, wallet, chess, sudoku, notes),
            assignments = mapOf(notes.id to AppCategory.Productivity),
            aiAssignments = mapOf(
                bank.id to AppCategory.Finance,
                wallet.id to AppCategory.Finance,
                notes.id to AppCategory.Tools,
            ),
        )

        assertEquals(
            listOf(
                folder(AppCategory.Finance, listOf(bank, wallet)),
                folder(AppCategory.Games, listOf(chess, sudoku)),
                folder(AppCategory.Productivity, listOf(notes)),
            ),
            grouping.folders,
        )
    }

    @Test
    fun effectiveFolder_prefersTheUsersFiling() {
        val chess = app("Chess", AppCategory.Games)

        assertEquals("games", effective(chess))
        assertNull(effective(chess, mapOf(chess.id to null)))
        assertEquals("tools", effective(chess, mapOf(chess.id to "tools")))
        val bank = app("Bank")
        assertEquals("other", effective(bank))
        assertEquals("finance", effective(bank, ai = mapOf(bank.id to "finance")))
        assertEquals("social", effective(chess, ai = mapOf(chess.id to "social")))
    }

    @Test
    fun aDeletedFolder_isPassedOver_andItsAppsGoWhereTheyWouldOtherwise() {
        // Games deleted: the user's filing into it and the AI's are passed
        // over, and a game's manifest no longer files it there.
        val folders = activeAppFolders(emptyList(), setOf("games", "finance"))
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku")
        val bank = app("Bank")
        val chat = app("Chat", AppCategory.Social)
        val zoom = app("Zoom", AppCategory.Social)

        val grouping = groupAppList(
            listOf(chess, sudoku, bank, chat, zoom),
            folders,
            assignments = mapOf(sudoku.id to "games"),
            aiAssignments = mapOf(bank.id to "finance"),
        )

        assertEquals(listOf("social", "other"), grouping.folders.map { it.folder.key })
        assertEquals(listOf(chess, sudoku, bank), grouping.folders[1].apps)
    }

    @Test
    fun usersOwnFolder_takesTheAppsFiledInIt_inAlphabeticalPlace() {
        val kids = AppFolderDef("custom-1", null, "Kids", FolderIcon.ChildCare)
        val folders = activeAppFolders(listOf(kids), emptySet())
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku", AppCategory.Games)
        val crayons = app("Crayons")
        val mail = app("Mail")

        val grouping = groupAppList(
            listOf(chess, sudoku, crayons, mail),
            folders,
            assignments = mapOf(crayons.id to "custom-1"),
        )

        assertEquals(listOf("games", "custom-1", "other"), grouping.folders.map { it.folder.key })
        assertEquals(listOf(crayons), grouping.folders[1].apps)
    }

    @Test
    fun activeFolders_mixTheUsersWithTheBuiltIns_alphabetically_withOtherLast() {
        val folders = activeAppFolders(
            listOf(
                AppFolderDef("custom-2", null, "zoo", FolderIcon.Pets),
                AppFolderDef("custom-1", null, "Kids", FolderIcon.ChildCare),
            ),
            setOf("tools"),
        )

        assertEquals(
            listOf(
                "finance", "games", "health", "home", "custom-1", "learning", "media", "news",
                "productivity", "shopping", "social", "travel", "custom-2", "other",
            ),
            folders.map { it.key },
        )
    }

    @Test
    fun storageKeys_roundTrip() {
        for (category in AppCategory.entries) {
            assertEquals(category, AppCategory.fromStorageKey(category.storageKey))
        }
        assertNull(AppCategory.fromStorageKey("unknown"))
        assertEquals(AppCategory.Other, AppCategory.entries.last())
    }

    private val defaults = activeAppFolders(emptyList(), emptySet())

    // The built-in folders, with filings by category, as most tests here use.
    private fun group(
        apps: List<InstalledApp>,
        assignments: Map<String, AppCategory?>,
        aiAssignments: Map<String, AppCategory> = emptyMap(),
    ): AppListGrouping = groupAppList(
        apps,
        defaults,
        assignments.mapValues { (_, category) -> category?.storageKey },
        aiAssignments.mapValues { (_, category) -> category.storageKey },
    )

    private fun folder(category: AppCategory, apps: List<InstalledApp>) =
        AppListFolder(AppFolderDef.builtIn(category), apps)

    private val AppListFolder.category: AppCategory? get() = folder.category

    private fun effective(
        app: InstalledApp,
        assignments: Map<String, String?> = emptyMap(),
        ai: Map<String, String> = emptyMap(),
    ): String? = effectiveFolderKey(app, defaults.mapTo(HashSet()) { it.key }, assignments, ai)

    private fun app(name: String, category: AppCategory? = null): InstalledApp = InstalledApp(
        name = name,
        packageName = "com.example.${name.lowercase()}",
        launchIntent = Intent.makeMainActivity(ComponentName("com.example.${name.lowercase()}", "Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
        declaredCategory = category,
    )
}
