package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLaunchStatsStoreRecentsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        context.getSharedPreferences("app_launch_stats", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun recordLaunchPushesAppIdToFront() {
        val store = AppLaunchStatsStore(context)
        store.recordLaunch("a")
        store.recordLaunch("b")
        store.recordLaunch("c")

        assertEquals(listOf("c", "b", "a"), store.recentAppIds)
    }

    @Test
    fun recordLaunchDeduplicatesExistingEntry() {
        val store = AppLaunchStatsStore(context)
        store.recordLaunch("a")
        store.recordLaunch("b")
        store.recordLaunch("a")

        // "a" moves to the front, "b" stays in the list once.
        assertEquals(listOf("a", "b"), store.recentAppIds)
    }

    @Test
    fun recentAppIdsCappedAtSixteenEntries() {
        val store = AppLaunchStatsStore(context)
        repeat(20) { i -> store.recordLaunch("app$i") }

        val recent = store.recentAppIds
        assertEquals(16, recent.size)
        // Most recent first: app19 down to app4 are kept.
        assertEquals("app19", recent.first())
        assertEquals("app4", recent.last())
    }

    @Test
    fun recentsSurviveProcessRestart() {
        AppLaunchStatsStore(context).recordLaunch("a")
        AppLaunchStatsStore(context).recordLaunch("b")

        val reloaded = AppLaunchStatsStore(context)
        assertEquals(listOf("b", "a"), reloaded.recentAppIds)
    }

    @Test
    fun filterRecentMapsIdsToInstalledAppsInDisplayOrder() {
        val mail = installedApp("Mail")
        val calendar = installedApp("Calendar")
        val browser = installedApp("Browser")
        val apps = listOf(mail, calendar, browser)

        // Use the apps' real ids so the round-trip matches what LauncherViewModel does.
        // Storage order is most-recent-first: Browser was launched after Mail.
        val recentIds = listOf(browser.id, mail.id)

        val recent = apps.filterRecent(recentIds)

        // Display order is oldest-first / most-recent-last so the row renders
        // the freshest app on the right; Calendar wasn't in the recent list.
        assertEquals(listOf("Mail", "Browser"), recent.map { it.name })
    }

    @Test
    fun filterRecentDropsAppsThatNoLongerExist() {
        val mail = installedApp("Mail")
        val recent = listOf(mail).filterRecent(listOf("missing-id", mail.id))

        // The uninstalled id is silently skipped rather than producing a placeholder row.
        assertEquals(listOf("Mail"), recent.map { it.name })
    }

    @Test
    fun filterRecentReturnsEmptyForEmptyRecents() {
        val apps = listOf(installedApp("Mail"))
        assertEquals(emptyList<InstalledApp>(), apps.filterRecent(emptyList()))
    }

    private fun installedApp(name: String): InstalledApp {
        val packageName = "app.${name.lowercase().replace(' ', '.')}"
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }
}
