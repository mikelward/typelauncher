package app.typelauncher

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebLinksTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf(WebLinkStore.PREFERENCES_NAME, "docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata", "hidden_apps")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun aSharedAddressTakesItsNameFromTheSubject() {
        val shared = parseSharedWebLink("https://example.com/recipes", "Recipes")

        assertEquals(SharedWebLink("https://example.com/recipes", "Recipes"), shared)
    }

    @Test
    fun withoutASubjectTheNameIsTheTextAroundTheAddress() {
        val shared = parseSharedWebLink("Weekly recipes https://example.com/recipes", null)

        assertEquals(SharedWebLink("https://example.com/recipes", "Weekly recipes"), shared)
    }

    @Test
    fun withNothingElseTheNameIsTheSite() {
        assertEquals("example.com", parseSharedWebLink("https://www.example.com/a", " ")?.name)
    }

    @Test
    fun punctuationAfterTheAddressIsNotPartOfIt() {
        assertEquals("https://example.com/a", parseSharedWebLink("See https://example.com/a.", null)?.url)
        assertEquals("https://example.com/a", parseSharedWebLink("(https://example.com/a)", null)?.url)
        assertEquals(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            parseSharedWebLink("https://en.wikipedia.org/wiki/Function_(mathematics)", null)?.url,
        )
        assertEquals(
            "https://en.wikipedia.org/wiki/Function_(mathematics)",
            parseSharedWebLink("(see https://en.wikipedia.org/wiki/Function_(mathematics)).", null)?.url,
        )
    }

    @Test
    fun anAddressOnItsOwnLineIsKeptExactly() {
        assertEquals("https://en.wikipedia.org/wiki/Yahoo!", parseSharedWebLink("https://en.wikipedia.org/wiki/Yahoo!", null)?.url)
        assertEquals("https://example.com/a?", parseSharedWebLink("Example\n  https://example.com/a?\n", null)?.url)
    }

    @Test
    fun renamingALinkRedrawsItsTile() {
        val entry = webLinkEntry(WebLink(id = "abc", name = "Recipes", url = "https://example.com/recipes"))
        val renamed = entry.copy(customName = "News")

        assertFalse(entry.iconCacheId == renamed.iconCacheId)
        assertEquals("N", letterTileLetter(renamed.displayName))
    }

    @Test
    fun onlyWebPagesAreTaken() {
        assertNull(parseSharedWebLink("just some text", null))
        assertNull(parseSharedWebLink("ftp://example.com/file", null))
        assertNull(parseSharedWebLink("javascript:alert(1)", null))
        assertNull(parseSharedWebLink("https://", null))
        assertNull(parseSharedWebLink(null, "Title"))
    }

    @Test
    fun aLongTitleIsShortened() {
        val name = parseSharedWebLink("https://example.com", "x".repeat(500))?.name

        assertEquals(100, name?.length)
    }

    @Test
    fun theStoreKeepsOneLinkPerAddress() {
        val store = WebLinkStore(context)

        val first = store.add("Recipes", "https://example.com/recipes")
        val again = store.add("Other name", "https://example.com/recipes")

        assertTrue(first!!.isNew)
        assertFalse(again!!.isNew)
        assertEquals(first.link, again.link)
        assertEquals(listOf(first.link), WebLinkStore(context).links())
    }

    @Test
    fun removingALinkKeepsTheOthers() {
        val store = WebLinkStore(context)
        val recipes = store.add("Recipes", "https://example.com/recipes")!!.link
        val news = store.add("News", "https://example.com/news")!!.link

        assertTrue(store.remove(recipes.id))

        assertEquals(listOf(news), WebLinkStore(context).links())
    }

    @Test
    fun aLinksEntryOpensItsPageAndItsIdNeverCarriesTheAddress() {
        val entry = webLinkEntry(WebLink(id = "abc", name = "Recipes", url = "https://example.com/recipes"))

        assertTrue(entry.isWebLink)
        assertFalse(entry.isShortcut)
        assertEquals(Intent.ACTION_VIEW, entry.launchIntent.action)
        assertEquals("https://example.com/recipes", entry.launchIntent.dataString)
        assertNull(entry.launchIntent.component)
        assertEquals("${Process.myUserHandle().hashCode()}:link:abc", entry.id)
        assertFalse(entry.id.contains("example"))
    }

    @Test
    fun aTileShowsTheNamesFirstLetterInAColorThatStays() {
        assertEquals("R", letterTileLetter("recipes"))
        assertEquals("9", letterTileLetter("  9gag"))
        assertEquals("?", letterTileLetter("…"))
        assertEquals(letterTileColor("Recipes"), letterTileColor("recipes"))
    }

    @Test
    fun searchMatchesALinkByItsNameNotItsStandInPackage() {
        val links = listOf(webLinkEntry(WebLink(id = "abc", name = "Recipes", url = "https://example.com/recipes")))

        assertTrue(links.anyMatchesName("rec"))
        assertFalse(links.anyMatchesName("web"))
        assertFalse(links.anyMatchesName("typelauncher"))
    }

    @Test
    fun theColdStartSnapshotKeepsLinks() {
        val entry = webLinkEntry(WebLink(id = "abc", name = "Recipes", url = "https://example.com/recipes"))
        val store = AppMetadataStore(context)

        store.save(listOf(entry))
        shadowOf(Looper.getMainLooper()).idle()
        val restored = store.load().single()

        assertEquals(entry.id, restored.id)
        assertEquals("Recipes", restored.name)
        assertEquals("https://example.com/recipes", restored.launchIntent.dataString)
        assertTrue(restored.isWebLink)
    }

    @Test
    fun aLinkJoinsTheAppListAndRemoveDeletesIt() {
        val viewModel = launcherViewModel()
        val link = WebLinkStore(context).add("Recipes", "https://example.com/recipes")!!.link
        shadowOf(Looper.getMainLooper()).idle()

        val entry = viewModel.uiState.value.filteredApps.single { app -> app.isWebLink }
        assertEquals(webLinkEntryId(Process.myUserHandle(), link.id), entry.id)
        assertEquals("Recipes", entry.displayName)

        viewModel.uninstallApp(entry)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(WebLinkStore(context).links().isEmpty())
        assertTrue(viewModel.uiState.value.filteredApps.none { app -> app.isWebLink })
    }

    @Test
    fun linksChangeEvenWhileTheAppReadIsFailing() {
        var failing = false
        val viewModel = launcherViewModel { _, _ ->
            if (failing) throw android.os.BadParcelableException("truncated") else emptyList()
        }
        failing = true

        val link = WebLinkStore(context).add("Recipes", "https://example.com/recipes")!!.link
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertTrue(viewModel.uiState.value.filteredApps.any { app -> app.webLinkId == link.id })

        WebLinkStore(context).remove(link.id)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        assertTrue(viewModel.uiState.value.filteredApps.none { app -> app.isWebLink })
    }

    @Test
    fun aStartupReadThatFoundOnlyLinksStillCountsAsFailed() {
        WebLinkStore(context).add("Recipes", "https://example.com/recipes")
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        appListEvents.record("test start ${System.nanoTime()}")

        launcherViewModel { _, _ -> throw android.os.BadParcelableException("truncated") }

        val lines = appListEvents.lines().let { lines -> lines.drop(lines.indexOfLast { it.contains("test start") } + 1) }
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("startup load failed, kept") })
    }

    @Test
    fun aDegradedStartupStillShowsALinkAddedWhileTheLauncherWasClosed() {
        // In the store, not in the cold-start snapshot.
        WebLinkStore(context).add("Recipes", "https://example.com/recipes")
        context.packageManager.setComponentEnabledSetting(
            android.content.ComponentName(context, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )

        val viewModel = launcherViewModel { _, _ -> throw android.os.BadParcelableException("truncated") }

        assertTrue(viewModel.uiState.value.filteredApps.any { app -> app.isWebLink })
    }

    @Test
    fun removingADockedLinkFreesItsDockSlot() {
        val viewModel = launcherViewModel()
        WebLinkStore(context).add("Recipes", "https://example.com/recipes")
        shadowOf(Looper.getMainLooper()).idle()
        val entry = viewModel.uiState.value.filteredApps.single { app -> app.isWebLink }
        viewModel.toggleDock(entry, maxDockedApps = 5)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(DockedAppStore(context).contains(entry.id))

        viewModel.uninstallApp(entry)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(DockedAppStore(context).contains(entry.id))
    }

    private fun launcherViewModel(
        enumerate: (android.content.pm.LauncherApps, android.os.UserHandle) -> List<android.content.pm.LauncherActivityInfo> =
            { _, _ -> emptyList() },
    ): LauncherViewModel {
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
            enumerateLauncherActivities = enumerate,
            queryShortcuts = { _, _ -> emptyList() },
            queryAppShortcuts = { _, _, _ -> emptyList() },
        )
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(viewModel)
        return viewModel
    }
}
