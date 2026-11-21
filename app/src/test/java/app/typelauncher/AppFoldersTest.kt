package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(AppCategory.Music, declaredAppCategory(ApplicationInfo.CATEGORY_AUDIO, 0))
        assertEquals(AppCategory.Media, declaredAppCategory(ApplicationInfo.CATEGORY_VIDEO, 0))
        assertEquals(AppCategory.Photos, declaredAppCategory(ApplicationInfo.CATEGORY_IMAGE, 0))
        assertEquals(AppCategory.Social, declaredAppCategory(ApplicationInfo.CATEGORY_SOCIAL, 0))
        assertEquals(AppCategory.News, declaredAppCategory(ApplicationInfo.CATEGORY_NEWS, 0))
        assertEquals(AppCategory.Transport, declaredAppCategory(ApplicationInfo.CATEGORY_MAPS, 0))
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
        val mail = app("Acme")
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
    fun aLoneAppInACategory_getsItsFolder_withNoMinimum() {
        // The maintainer, 2026-11-19: remove the two-app minimum.
        val chess = app("Chess", AppCategory.Games)
        val acme = app("Acme")
        val chat = app("Chat", AppCategory.Social)
        val zoom = app("Zoom", AppCategory.Social)

        val grouping = group(listOf(chess, acme, chat, zoom), emptyMap())

        assertEquals(
            listOf(AppCategory.Games, AppCategory.Social, AppCategory.Other),
            grouping.folders.map { it.category },
        )
        assertEquals(listOf(chess), grouping.folders[0].apps)
        assertEquals(listOf(acme), grouping.folders[2].apps)
    }

    @Test
    fun otherAlone_isDropped_andItsAppsStayLoose() {
        // A list that is one folder is just the list behind an extra tap.
        val acme = app("Acme")
        val zeta = app("Zeta")

        val grouping = group(listOf(acme, zeta), emptyMap())

        assertEquals(emptyList<AppListFolder>(), grouping.folders)
        assertEquals(listOf(acme, zeta), grouping.looseApps)
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

        // Bank: filed by the user. Chess: "No folder", so loose. Sudoku
        // keeps its Games folder, alone.
        assertEquals(
            listOf(
                folder(AppCategory.Finance, listOf(bank)),
                folder(AppCategory.Games, listOf(sudoku)),
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
        val bank = app("Bank", AppCategory.Office)
        val wallet = app("Wallet")
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku", AppCategory.Games)
        val notes = app("Notes")

        val grouping = group(
            listOf(bank, wallet, chess, sudoku, notes),
            assignments = mapOf(notes.id to AppCategory.Office),
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
                folder(AppCategory.Office, listOf(notes)),
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
        val bank = app("Acme")
        assertEquals("other", effective(bank))
        assertEquals("finance", effective(bank, ai = mapOf(bank.id to "finance")))
        // A manifest saying "game" beats the AI (see aManifestGame_beatsTheAi_butNotTheUser).
        assertEquals("games", effective(chess, ai = mapOf(chess.id to "social")))
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
        val mail = app("Acme")

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
                "ai", "betting", "code", "design", "events", "finance", "food", "games", "government", "health", "home",
                // "Office & productivity" (maintainer, 2026-11-19) sorts under O.
                "internet", "jobs", "kids", "custom-1", "learning", "media", "music", "news", "office", "pets", "phone",
                "photos", "post", "reading", "rewards", "security", "shopping", "social",
                "sports", "transport", "travel", "weather", "custom-2", "other",
            ),
            folders.map { it.key },
        )
    }

    @Test
    fun wellKnownApps_goWhereTheyBelong_aheadOfTheAi_butNotTheUser() {
        // The maintainer, 2026-11-19: Waze went to Tools and Netflix's games
        // to Media.
        val waze = app("Waze").copy(packageName = "com.waze")
        val rdr = app("Red Dead Redemption").copy(packageName = "com.netflix.NGP.RedDeadRedemption")

        assertEquals("transport", effective(waze, ai = mapOf(waze.id to "tools")))
        assertEquals("games", effective(rdr, ai = mapOf(rdr.id to "media")))
        assertEquals("tools", effective(waze, assignments = mapOf(waze.id to "tools")))
    }

    @Test
    fun folderNames_matchByTheStartOfAnyWord_ignoringCase() {
        assertTrue(folderNameMatches("Games", "gam"))
        assertTrue(folderNameMatches("Pets & animals", "ANIM"))
        assertTrue(folderNameMatches("Kids Stuff", "stu"))
        assertFalse(folderNameMatches("Games", "ames"))
        assertFalse(folderNameMatches("Games", " "))
    }

    @Test
    fun aGuess_comesAfterTheAiAndTheManifest() {
        val bank = app("Acme").copy(packageName = "com.example.mobilebanking")
        val chess = app("Chess Pro", AppCategory.Games).copy(packageName = "com.example.banking")

        assertEquals("finance", effective(bank))
        assertEquals("tools", effective(bank, ai = mapOf(bank.id to "tools")))
        assertEquals("games", effective(chess))
    }

    @Test
    fun aKnownApp_beatsTheAi_andANameThatSaysWhatItIs_too() {
        val netflix = app("Netflix").copy(packageName = "com.netflix.mediaclient")
        val health = app("Samsung Health").copy(packageName = "com.example.health")

        assertEquals("media", effective(netflix, ai = mapOf(netflix.id to "other")))
        assertEquals("health", effective(health, ai = mapOf(health.id to "tools")))
    }

    @Test
    fun aPinnedWebsite_isntPlacedByItsBrowsersPackage() {
        // Codex on PR #863: a site pinned from Chrome carries Chrome's package.
        val site = app("Acme").copy(packageName = "com.android.chrome", shortcutId = "acme")
        val news = app("Daily News").copy(packageName = "com.android.chrome", shortcutId = "news")

        assertEquals("other", effective(site))
        assertEquals("social", effective(site, ai = mapOf(site.id to "social")))
        assertEquals("news", effective(news))
    }

    @Test
    fun theUsersOwnFolder_showsWithOneApp_andTheAisChoiceOfIt_beatsTheTable() {
        // The maintainer, 2026-11-19: a new Coding folder never appeared. Too
        // few apps reached it, and the table kept the well-known ones.
        val coding = AppFolderDef("custom-1", null, "Coding", FolderIcon.Code)
        val folders = activeAppFolders(listOf(coding), emptySet())
        val github = app("GitHub").copy(packageName = "com.github.android")
        val netflix = app("Netflix").copy(packageName = "com.netflix.mediaclient")
        val notes = app("Notes")

        val grouping = groupAppList(
            listOf(github, netflix, notes),
            folders,
            assignments = emptyMap(),
            aiAssignments = mapOf(github.id to "custom-1", netflix.id to "games"),
        )

        assertEquals(listOf(github), grouping.folders.first { it.folder.key == "custom-1" }.apps)
        // A built-in choice still doesn't override the table.
        assertTrue(grouping.folders.none { it.folder.key == "games" })
    }

    @Test
    fun aManifestGame_beatsTheAi_butNotTheUser() {
        // The maintainer, 2026-11-19: the AI filed games that say so under
        // Transport and Other.
        val game = app("Subway", AppCategory.Games)
        val video = app("Clips", AppCategory.Media)

        assertEquals("games", effective(game, ai = mapOf(game.id to "transport")))
        assertEquals("kids", effective(game, assignments = mapOf(game.id to "kids")))
        // Other manifest categories still give way to the AI.
        assertEquals("social", effective(video, ai = mapOf(video.id to "social")))
        // And the AI is told.
        assertEquals(AppCategory.Games, game.suggestedCategory())
        assertEquals(AppCategory.Media, video.suggestedCategory())
    }

    @Test
    fun productivity_isAnOfficeBuilding_andJobsTakesTheBriefcase() {
        // The maintainer, 2026-11-19.
        assertEquals(FolderIcon.Business, AppCategory.Office.icon)
        assertEquals(FolderIcon.Work, AppCategory.Jobs.icon)
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

    @Test
    fun googlePlayCategory_ranksBelowTheRulesAndAboveTheAiAndManifest() {
        val unknown = app("Thermo", AppCategory.Tools)
        val ids = defaults.mapTo(HashSet()) { it.key }
        val play = mapOf(unknown.packageName to "HOUSE_AND_HOME")
        val ai = mapOf(unknown.id to AppCategory.Office.storageKey)
        // Above the AI's pick and the manifest's category.
        assertEquals(AppCategory.Home.storageKey, effectiveFolderKey(unknown, ids, emptyMap(), ai, play))
        // Below a rule: a well-known app stays where the rules say.
        val known = app("Netflix").copy(packageName = "com.netflix.mediaclient")
        assertEquals(
            AppCategory.Media.storageKey,
            effectiveFolderKey(known, ids, emptyMap(), emptyMap(), mapOf(known.packageName to "FINANCE")),
        )
        // A category with no folder (or none found) falls through to the AI.
        assertEquals(
            AppCategory.Office.storageKey,
            effectiveFolderKey(unknown, ids, emptyMap(), ai, mapOf(unknown.packageName to "LIFESTYLE")),
        )
        assertEquals(
            AppCategory.Office.storageKey,
            effectiveFolderKey(unknown, ids, emptyMap(), ai, mapOf(unknown.packageName to "")),
        )
        // And the user's own filing beats it all.
        assertEquals(
            AppCategory.Tools.storageKey,
            effectiveFolderKey(unknown, ids, mapOf(unknown.id to AppCategory.Tools.storageKey), ai, play),
        )
    }

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
