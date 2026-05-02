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
class LauncherFilterOrderingTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val store = AppLaunchStatsStore(context)

    @After
    fun clearPrefs() {
        context.getSharedPreferences("app_launch_stats", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun filterByNameOrdersPrefixThenAnchoredThenSubstring() {
        val apps = listOf(
            // Anchored only — capital M is a word-start anchor reachable from
            // the M-anchor; "mail" is not a prefix of "iMail".
            installedApp("iMail"),
            // Substring only — Gmail's 'm' is mid-word lowercase, so it can't
            // anchor; the substring "mail" is still present.
            installedApp("Gmail"),
            // Prefix.
            installedApp("Mail"),
            // No match.
            installedApp("Slack"),
        )

        val filtered = apps.filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
        )

        assertEquals(listOf("Mail", "iMail", "Gmail"), filtered.map { it.name })
    }

    @Test
    fun filterByNamePreservesAlphabeticalOrderWithinTier() {
        val apps = listOf(
            installedApp("Gallery"),
            installedApp("Gmail"),
            installedApp("Google Maps"),
        )

        val filtered = apps.filterByName(
            query = "g",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
        )

        assertEquals(listOf("Gallery", "Gmail", "Google Maps"), filtered.map { it.name })
    }

    @Test
    fun filterByNameExcludesDockedAppsFromTypedSearchResults() {
        val docked = installedApp("ABC")
        // Real app feeds filterByName an alphabetical list (loadInstalledApps
        // sorts that way); mirror that here so the stable within-tier ordering
        // assertion is meaningful.
        val apps = listOf(docked, installedApp("Calculator"), installedApp("Camera"))

        val filtered = apps.filterByName(
            query = "c",
            appLaunchStatsStore = store,
            excludedAppIds = listOf(docked.id),
        )

        // ABC is docked and would otherwise anchor-match "c"; with the dock
        // active it is excluded from search results so the launch target is a
        // non-docked match.
        assertEquals(listOf("Calculator", "Camera"), filtered.map { it.name })
    }

    @Test
    fun filterByNameExcludesDockedAppsFromEmptyQueryListWhileDockEnabled() {
        val docked = installedApp("ABC")
        val apps = listOf(docked, installedApp("Camera"), installedApp("Calculator"))

        val filtered = apps.filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = listOf(docked.id),
        )

        // Docked apps live in the dock row when the dock is enabled, so the
        // main list never contains them in either query mode.
        assertEquals(listOf("Calculator", "Camera"), filtered.map { it.name })
    }

    @Test
    fun filterByNameIncludesDockedAppsWhenDockDisabled() {
        val docked = installedApp("ABC")
        val apps = listOf(docked, installedApp("Camera"), installedApp("Calculator"))

        val filtered = apps.filterByName(
            query = "",
            appLaunchStatsStore = store,
            // Caller passes an empty exclusion set when the dock UI is hidden
            // so docked apps reappear in the main list.
            excludedAppIds = emptySet(),
        )

        assertEquals(listOf("ABC", "Calculator", "Camera"), filtered.map { it.name })
    }

    @Test
    fun filterDockedReturnsAppsInPersistedInsertionOrderRegardlessOfList() {
        val abc = installedApp("ABC")
        val browser = installedApp("Browser")
        val calculator = installedApp("Calculator")
        // Installed-app list happens to be alphabetical, but the dock should
        // sort by the persisted insertion order (browser pinned first), not
        // the input order.
        val installed = listOf(abc, browser, calculator)
        val dockedIds = listOf(browser.id, calculator.id)

        val docked = installed.filterDocked(dockedIds)

        assertEquals(listOf("Browser", "Calculator"), docked.map { it.name })
    }

    @Test
    fun filterDockedDropsIdsThatNoLongerExistInTheInstalledList() {
        val browser = installedApp("Browser")
        val calculator = installedApp("Calculator")
        val installed = listOf(browser, calculator)

        // "ghost" simulates an app that was uninstalled but is still in the
        // persisted dock list; it must drop out silently.
        val docked = installed.filterDocked(listOf(browser.id, "ghost", calculator.id))

        assertEquals(listOf("Browser", "Calculator"), docked.map { it.name })
    }

    @Test
    fun filterByNameSurfacesSubstringMatchesBelowEverythingElse() {
        val apps = listOf(
            installedApp("Cool"),
            installedApp("Door"),
            installedApp("Foo"),
            installedApp("Snake"),
        )

        val filtered = apps.filterByName(
            query = "oo",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
        )

        // None start with "oo" or anchor-match it; the first three contain it
        // as a substring and stay in their incoming alphabetical order;
        // "Snake" drops out.
        assertEquals(listOf("Cool", "Door", "Foo"), filtered.map { it.name })
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
