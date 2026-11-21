package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reading Google Play pages, and mapping their categories to folders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlayCategoryLookupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences(PlaySortStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun readsTheCategoryFromThePagesSchemaOrgData() {
        // Trimmed from a real page (2026-11-21).
        val page = """<script type="application/ld+json" nonce="x">{"@context":"https://schema.org",""" +
            """"@type":"SoftwareApplication","name":"IMI Heatmiser NeoApp","applicationCategory":"HOUSE_AND_HOME",""" +
            """"operatingSystem":"ANDROID"}</script>"""
        assertEquals("HOUSE_AND_HOME", parsePlayCategory(page))
        assertNull(parsePlayCategory("<html>no data</html>"))
    }

    @Test
    fun mapsPlayCategoriesToFolders_andLeavesVagueOnesOut() {
        assertEquals(AppCategory.Home, folderForPlayCategory("HOUSE_AND_HOME"))
        assertEquals(AppCategory.Games, folderForPlayCategory("GAME_PUZZLE"))
        assertEquals(AppCategory.Games, folderForPlayCategory("GAME_STRATEGY"))
        assertEquals(AppCategory.Social, folderForPlayCategory("COMMUNICATION"))
        assertEquals(AppCategory.Health, folderForPlayCategory("MEDICAL"))
        assertNull(folderForPlayCategory("LIFESTYLE"))
        assertNull(folderForPlayCategory("LIBRARIES_AND_DEMO"))
        assertNull(folderForPlayCategory(""))
    }

    @Test
    fun asksForTheEnglishPageInThePhonesCountry() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app&hl=en&gl=AU",
            playPageUrl("com.example.app", "au"),
        )
        assertEquals("https://play.google.com/store/apps/details?id=com.example.app&hl=en", playPageUrl("com.example.app", null))
        // A package name is letters, digits, '_' and '.', but nothing else reaches the query unescaped.
        assertTrue(playPageUrl("a&b", null).contains("id=a%26b&"))
    }

    @Test
    fun storeKeepsAnswersQueueAndChoicesAcrossRestarts() {
        val store = PlaySortStore(context)
        store.record("com.example.a", "FINANCE")
        store.record("com.example.b", "")
        store.pending = listOf("com.example.c", "com.example.d")
        store.dismissed = setOf("com.example.e")
        store.isAutoEnabled = true
        store.hasUsed = true

        val again = PlaySortStore(context)
        assertEquals(mapOf("com.example.a" to "FINANCE", "com.example.b" to ""), again.categories)
        assertEquals(listOf("com.example.c", "com.example.d"), again.pending)
        assertEquals(setOf("com.example.e"), again.dismissed)
        assertTrue(again.isAutoEnabled)
        assertTrue(again.hasUsed)

        again.clearCategories()
        assertTrue(PlaySortStore(context).categories.isEmpty())
        // Apps kept out stay out (Codex on PR #885).
        assertEquals(setOf("com.example.e"), PlaySortStore(context).dismissed)
    }

    @Test
    fun catalog_readsPackageAndCategoryLines_skippingCommentsAndJunk() {
        val text = "# header\n\ncom.example.weather\tWEATHER\ncom.example.game\tGAME_PUZZLE\nno-tab-here\n" +
            "com.example.lower\tweather\n\tFINANCE\n"
        assertEquals(
            mapOf("com.example.weather" to "WEATHER", "com.example.game" to "GAME_PUZZLE"),
            parsePlayCatalog(text),
        )
    }

    @Test
    fun layered_looksUpFirst_thenTheCatalog() {
        val catalog = mapOf("a" to "WEATHER", "b" to "FINANCE")
        val layered = LayeredPlayCategories(mapOf("a" to "TOOLS", "c" to ""), catalog)
        assertEquals("TOOLS", layered["a"])
        assertEquals("FINANCE", layered["b"])
        assertEquals("", layered["c"])
        assertTrue("c" in layered)
        assertEquals(mapOf("a" to "TOOLS", "b" to "FINANCE", "c" to ""), HashMap(layered))
        assertEquals(LayeredPlayCategories(mapOf("a" to "TOOLS", "c" to ""), catalog), layered)
    }
}
