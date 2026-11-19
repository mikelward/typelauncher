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

        val grouping = groupAppList(listOf(chess, mail, sudoku), emptyMap())

        assertEquals(
            listOf(
                AppListFolder(AppCategory.Games, listOf(chess, sudoku)),
                AppListFolder(AppCategory.Other, listOf(mail)),
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

        val grouping = groupAppList(listOf(chess, mail, chat, zoom), emptyMap())

        assertEquals(listOf(AppCategory.Social, AppCategory.Other), grouping.folders.map { it.category })
        assertEquals(listOf(chess, mail), grouping.folders[1].apps)
    }

    @Test
    fun otherAlone_isDropped_andItsAppsStayLoose() {
        // A list that is one folder is just the list behind an extra tap.
        val chess = app("Chess", AppCategory.Games)
        val mail = app("Mail")

        val grouping = groupAppList(listOf(chess, mail), emptyMap())

        assertEquals(emptyList<AppListFolder>(), grouping.folders)
        assertEquals(listOf(chess, mail), grouping.looseApps)
    }

    @Test
    fun userFiling_overridesTheManifest_andShowsEvenAlone() {
        val bank = app("Bank")
        val chess = app("Chess", AppCategory.Games)
        val sudoku = app("Sudoku", AppCategory.Games)

        val grouping = groupAppList(
            listOf(bank, chess, sudoku),
            mapOf(bank.id to AppCategory.Finance, chess.id to null),
        )

        // Bank: filed by the user, so its folder shows with one app. Chess:
        // "No folder", so loose. That leaves Sudoku alone, so it goes in Other.
        assertEquals(
            listOf(
                AppListFolder(AppCategory.Finance, listOf(bank)),
                AppListFolder(AppCategory.Other, listOf(sudoku)),
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

        val grouping = groupAppList(listOf(zoom, chess, chat, sudoku), emptyMap())

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

        val grouping = groupAppList(
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
                AppListFolder(AppCategory.Finance, listOf(bank, wallet)),
                AppListFolder(AppCategory.Games, listOf(chess, sudoku)),
                AppListFolder(AppCategory.Productivity, listOf(notes)),
            ),
            grouping.folders,
        )
    }

    @Test
    fun effectiveFolder_prefersTheUsersFiling() {
        val chess = app("Chess", AppCategory.Games)

        assertEquals(AppCategory.Games, effectiveAppFolder(chess, emptyMap()))
        assertNull(effectiveAppFolder(chess, mapOf(chess.id to null)))
        assertEquals(AppCategory.Tools, effectiveAppFolder(chess, mapOf(chess.id to AppCategory.Tools)))
        val bank = app("Bank")
        assertEquals(AppCategory.Other, effectiveAppFolder(bank, emptyMap()))
        assertEquals(AppCategory.Finance, effectiveAppFolder(bank, emptyMap(), mapOf(bank.id to AppCategory.Finance)))
        assertEquals(AppCategory.Social, effectiveAppFolder(chess, emptyMap(), mapOf(chess.id to AppCategory.Social)))
    }

    @Test
    fun storageKeys_roundTrip() {
        for (category in AppCategory.entries) {
            assertEquals(category, AppCategory.fromStorageKey(category.storageKey))
        }
        assertNull(AppCategory.fromStorageKey("unknown"))
        assertEquals(AppCategory.Other, AppCategory.entries.last())
    }

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
