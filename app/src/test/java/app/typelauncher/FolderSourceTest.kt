package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What put each app in its folder, for "Check guessed folders". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FolderSourceTest {
    private val folders = activeAppFolders(emptyList(), emptySet())
    private val activeKeys = folders.mapTo(HashSet()) { it.key }

    @Test
    fun eachStep_isNamed_andOnlyGuessesAreGuesses() {
        val bank = app("Greenfield Bank", "com.example.greenfield")
        val netflix = app("Netflix", "com.netflix.mediaclient")
        val tapo = app("Tapo", "com.example.tapo")
        val filed = app("Filed", "com.example.filed")
        val played = app("Thermo", "com.example.thermo")
        val aied = app("Lumen", "com.example.lumen")
        val manifest = app("Tunes", "com.example.tunes", AppCategory.Music)
        val guessed = app("Sky", "com.example.weatherpro")
        val nothing = app("Acme", "com.example.acme")
        val filings = mapOf(filed.id to AppCategory.Tools.storageKey)
        val ai = mapOf(aied.id to AppCategory.Photos.storageKey)
        val play = mapOf("com.example.thermo" to "WEATHER")

        val expected = mapOf(
            bank to FolderSource.NameWord,
            netflix to FolderSource.Rule,
            tapo to FolderSource.Rule,
            filed to FolderSource.User,
            played to FolderSource.GooglePlay,
            aied to FolderSource.Ai,
            manifest to FolderSource.Manifest,
            guessed to FolderSource.Guess,
            nothing to FolderSource.None,
        )
        for ((app, source) in expected) {
            assertEquals(app.name, source, folderSource(app, activeKeys, filings, ai, play))
        }
        assertEquals(
            setOf(bank, aied, guessed),
            expected.keys.filter { folderSource(it, activeKeys, filings, ai, play).isGuess }.toSet(),
        )
    }

    @Test
    fun aGameManifest_beatsANameWord_andIsNoGuess() {
        val game = app("Train Sim World", "com.example.trainsim", AppCategory.Games)
        assertEquals(FolderSource.Rule, folderSource(game, activeKeys, emptyMap()))
        assertEquals(AppCategory.Games.storageKey, effectiveFolderKey(game, activeKeys, emptyMap()))
        val plainGame = app("Zorblax", "com.example.zorblax", AppCategory.Games)
        assertFalse(folderSource(plainGame, activeKeys, emptyMap()).isGuess)
    }

    @Test
    fun theSource_agreesWithTheFolder() {
        // Where folderSource says nothing placed it, effectiveFolderKey says Other.
        val apps = listOf(
            app("Greenfield Bank", "com.example.greenfield"),
            app("Acme", "com.example.acme"),
            app("Sky", "com.example.weatherpro"),
            app("Netflix", "com.netflix.mediaclient"),
        )
        for (app in apps) {
            val source = folderSource(app, activeKeys, emptyMap())
            val key = effectiveFolderKey(app, activeKeys, emptyMap())
            assertEquals(app.name, source == FolderSource.None, key == AppCategory.Other.storageKey)
        }
    }

    @Test
    fun aPinnedWebsite_isNeverSortedByItsBrowsersPlayCategory() {
        // It carries its browser's package (Codex on PR #890).
        val site = app("Acme Portal", "com.android.chrome").copy(shortcutId = "acme")
        val play = mapOf("com.android.chrome" to "COMMUNICATION")
        assertEquals(AppCategory.Other.storageKey, effectiveFolderKey(site, activeKeys, emptyMap(), emptyMap(), play))
        assertEquals(FolderSource.None, folderSource(site, activeKeys, emptyMap(), emptyMap(), play))
    }

    private fun app(name: String, packageName: String, category: AppCategory? = null): InstalledApp = InstalledApp(
        name = name,
        packageName = packageName,
        launchIntent = Intent.makeMainActivity(ComponentName(packageName, "Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
        declaredCategory = category,
    )
}
