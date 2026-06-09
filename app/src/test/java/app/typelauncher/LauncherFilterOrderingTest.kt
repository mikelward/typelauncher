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
    fun filterByNameExcludesRecentsWhenCallerCombinesThemWithDocked() {
        // The ViewModel composes the exclusion set: docked when the dock UI
        // is on, plus recents when the always-on recents card is up and the
        // user hasn't disabled the deduplication toggle. `filterByName` just
        // honours whatever set it was given, so verifying the combined-set
        // path here is what the caller cares about.
        val docked = installedApp("ABC")
        val recent = installedApp("Calculator")
        val apps = listOf(docked, recent, installedApp("Camera"))

        val filtered = apps.filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = setOf(docked.id, recent.id),
        )

        assertEquals(listOf("Camera"), filtered.map { it.name })
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
    fun filterByNameIncludesDockedAppsWhenShowDockedAppsInListIsOn() {
        // Mirrors the new "Show docked apps" setting (default ON): the
        // ViewModel passes an empty exclusion set even though the dock UI is
        // active, so docked apps stay in the typed-search list.
        val docked = installedApp("ABC")
        val apps = listOf(docked, installedApp("Camera"), installedApp("Calculator"))

        val filtered = apps.filterByName(
            query = "c",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
        )

        assertEquals(listOf("Calculator", "Camera", "ABC"), filtered.map { it.name })
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
    fun filterByNameSortsWithinTierByLaunchCountDescendingThenAlphabetical() {
        val gallery = installedApp("Gallery")
        val gmail = installedApp("Gmail")
        val googleMaps = installedApp("Google Maps")
        // Gmail has been launched the most, Google Maps once, Gallery never;
        // all three are prefix matches for "g" so the tie-breaker is the
        // launch count, then alphabetical for any remaining tie.
        repeat(5) { store.recordLaunch(gmail.id) }
        store.recordLaunch(googleMaps.id)

        val filtered = listOf(gallery, gmail, googleMaps).filterByName(
            query = "g",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
        )

        assertEquals(listOf("Gmail", "Google Maps", "Gallery"), filtered.map { it.name })
    }

    @Test
    fun filterByNameReversedVariantsShareDataOrderingWithForwardVariants() {
        // The reversed sort orders are a UI-only flip applied via
        // `reverseLayout = true`; the underlying data ordering must match the
        // forward variant so the typed-search active-first target stays at
        // index 0 (which renders at the visual bottom under reverseLayout).
        val gallery = installedApp("Gallery")
        val gmail = installedApp("Gmail")
        val googleMaps = installedApp("Google Maps")
        repeat(5) { store.recordLaunch(gmail.id) }
        store.recordLaunch(googleMaps.id)
        val apps = listOf(gallery, gmail, googleMaps)

        assertEquals(
            apps.filterByName(
                query = "",
                appLaunchStatsStore = store,
                excludedAppIds = emptySet(),
                sortOrder = AppListSortOrder.Usage,
            ).map { it.name },
            apps.filterByName(
                query = "",
                appLaunchStatsStore = store,
                excludedAppIds = emptySet(),
                sortOrder = AppListSortOrder.UsageReversed,
            ).map { it.name },
        )
        assertEquals(
            apps.filterByName(
                query = "",
                appLaunchStatsStore = store,
                excludedAppIds = emptySet(),
                sortOrder = AppListSortOrder.Alphabetical,
            ).map { it.name },
            apps.filterByName(
                query = "",
                appLaunchStatsStore = store,
                excludedAppIds = emptySet(),
                sortOrder = AppListSortOrder.AlphabeticalReversed,
            ).map { it.name },
        )
    }

    @Test
    fun filterByNameReversedVariantsShareTieredDataOrderingUnderTypedQuery() {
        // Under a typed query the reversed variants must produce the same
        // tiered (prefix → anchored → substring) ranking as their forward
        // counterparts; the visual flip happens in the LazyColumn /
        // LazyVerticalGrid via `reverseLayout = true`. If the data ordering
        // diverged between forward and reversed sorts, the active-first
        // launch target (highlighted at index 0, rendered at the visual
        // bottom under reverseLayout) would land on the wrong app when the
        // user pressed Enter.
        val apps = listOf(
            installedApp("iMail"),
            installedApp("Gmail"),
            installedApp("Mail"),
            installedApp("Slack"),
        )
        // Bias usage so the within-tier sort actually has work to do.
        val gmail = apps.first { it.name == "Gmail" }
        repeat(3) { store.recordLaunch(gmail.id) }

        val usageForward = apps.filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.Usage,
        ).map { it.name }
        val usageReversed = apps.filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.UsageReversed,
        ).map { it.name }
        // Anchor the assertion: Mail (prefix) → iMail (anchored) → Gmail
        // (substring). Both variants must produce that exact list.
        assertEquals(listOf("Mail", "iMail", "Gmail"), usageForward)
        assertEquals(usageForward, usageReversed)

        val nameForward = apps.filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.Alphabetical,
        ).map { it.name }
        val nameReversed = apps.filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.AlphabeticalReversed,
        ).map { it.name }
        assertEquals(listOf("Mail", "iMail", "Gmail"), nameForward)
        assertEquals(nameForward, nameReversed)
    }

    @Test
    fun filterByNameUnderAlphabeticalSortIgnoresLaunchCountWithinTier() {
        val gallery = installedApp("Gallery")
        val gmail = installedApp("Gmail")
        val googleMaps = installedApp("Google Maps")
        // Gmail has been opened many times, but the user has explicitly
        // opted into alphabetical sort, so the typed-query path must respect
        // the preference and not re-order by launch count.
        repeat(5) { store.recordLaunch(gmail.id) }
        store.recordLaunch(googleMaps.id)

        val filtered = listOf(gallery, gmail, googleMaps).filterByName(
            query = "g",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.Alphabetical,
        )

        assertEquals(listOf("Gallery", "Gmail", "Google Maps"), filtered.map { it.name })
    }

    @Test
    fun filterByNameRanksDockedAppsFirstWithinTierWhenDockHidden() {
        val calculator = installedApp("Calculator")
        val camera = installedApp("Camera")
        val clock = installedApp("Clock")
        // All three are prefix matches for "c". Camera is docked but the dock
        // is hidden (excludedAppIds is empty, dockedAppIds carries it through),
        // so Camera floats to the top of its tier even though Calculator wins
        // alphabetically.
        val filtered = listOf(calculator, camera, clock).filterByName(
            query = "c",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(camera.id),
        )

        assertEquals(listOf("Camera", "Calculator", "Clock"), filtered.map { it.name })
    }

    @Test
    fun filterByNameRanksDockedAppsFirstAcrossEachTierWhenDockHidden() {
        // Mail = prefix, iMail = anchored, Gmail = substring. iMail and Gmail
        // are docked; the dock is hidden so they should still come first
        // within their respective tiers — but tiers still dominate, so Mail
        // (prefix) precedes the docked Anchored/Substring entries.
        val mail = installedApp("Mail")
        val iMail = installedApp("iMail")
        val gmail = installedApp("Gmail")
        val mailerPrefix = installedApp("Mailer")
        val mailroomPrefix = installedApp("Mailroom")

        val filtered = listOf(mail, iMail, gmail, mailerPrefix, mailroomPrefix).filterByName(
            query = "mail",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(mailroomPrefix.id, iMail.id, gmail.id),
        )

        // Within the Prefix tier the docked Mailroom outranks the
        // alphabetically-earlier non-docked Mail/Mailer; the Anchored and
        // Substring tiers each have a single (docked) entry.
        assertEquals(listOf("Mailroom", "Mail", "Mailer", "iMail", "Gmail"), filtered.map { it.name })
    }

    @Test
    fun filterByNameRanksDockedAppsFirstInEmptyQueryUsageWhenDockHidden() {
        val apple = installedApp("Apple")
        val banana = installedApp("Banana")
        val cherry = installedApp("Cherry")
        // Apple has the most launches; Banana is docked with fewer launches.
        // Dock is hidden, so Banana floats above Apple regardless of count.
        repeat(5) { store.recordLaunch(apple.id) }
        repeat(2) { store.recordLaunch(banana.id) }

        val filtered = listOf(apple, banana, cherry).filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(banana.id),
        )

        assertEquals(listOf("Banana", "Apple", "Cherry"), filtered.map { it.name })
    }

    @Test
    fun filterByNameOrdersDockedAppsByDockPositionInEmptyQueryWhenDockHidden() {
        val apple = installedApp("Apple")
        val banana = installedApp("Banana")
        val cherry = installedApp("Cherry")
        val date = installedApp("Date")
        // Three docked apps in a non-alphabetical, non-usage dock order. Apple
        // has the most launches but is pinned last in the dock; the dock is
        // hidden. The list should mirror the dock's persisted order
        // (cherry, banana, apple) so the muscle-memory positions the user has
        // for the dock survive turning it off.
        repeat(10) { store.recordLaunch(apple.id) }
        repeat(3) { store.recordLaunch(banana.id) }
        repeat(1) { store.recordLaunch(cherry.id) }

        val filtered = listOf(apple, banana, cherry, date).filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(cherry.id, banana.id, apple.id),
        )

        assertEquals(listOf("Cherry", "Banana", "Apple", "Date"), filtered.map { it.name })
    }

    @Test
    fun filterByNameOrdersDockedAppsByDockPositionWithinTierWhenDockHidden() {
        // Three Prefix-tier matches for "c", all docked, persisted in a dock
        // order that is neither alphabetical nor by launch count. Within the
        // tier the docked apps must appear in dock order, not launch-count or
        // alphabetical order.
        val calculator = installedApp("Calculator")
        val camera = installedApp("Camera")
        val clock = installedApp("Clock")
        repeat(7) { store.recordLaunch(calculator.id) }
        repeat(2) { store.recordLaunch(camera.id) }

        val filtered = listOf(calculator, camera, clock).filterByName(
            query = "c",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(clock.id, camera.id, calculator.id),
        )

        assertEquals(listOf("Clock", "Camera", "Calculator"), filtered.map { it.name })
    }

    @Test
    fun filterByNameOrdersDockedAppsByDockPositionUnderAlphabeticalSortWhenDockHidden() {
        val apple = installedApp("Apple")
        val banana = installedApp("Banana")
        val cherry = installedApp("Cherry")
        val date = installedApp("Date")

        val filtered = listOf(apple, banana, cherry, date).filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            dockedAppIds = listOf(cherry.id, apple.id),
            sortOrder = AppListSortOrder.Alphabetical,
        )

        // Docked apps come first in dock order even when the user has chosen
        // the alphabetical sort — the dock-first contract trumps the in-tier
        // ordering rule, same as it does under the usage sort.
        assertEquals(listOf("Cherry", "Apple", "Banana", "Date"), filtered.map { it.name })
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

    @Test
    fun alphabeticalSortCollatesAccentedNamesWithTheirBaseLetter() {
        // Regression test for String.CASE_INSENSITIVE_ORDER comparing
        // case-folded UTF-16 code units: "École" and "Über" sorted after
        // "Zoom" because É (U+00C9) and Ü (U+00DC) follow Z (U+005A). A
        // locale-aware collator keeps accented letters next to their base
        // letter.
        val apps = listOf(
            installedApp("Zoom"),
            installedApp("École"),
            installedApp("Ebay"),
            installedApp("Über"),
            installedApp("Uno"),
        )

        val sorted = apps.filterByName(
            query = "",
            appLaunchStatsStore = store,
            excludedAppIds = emptySet(),
            sortOrder = AppListSortOrder.Alphabetical,
        )

        assertEquals(listOf("Ebay", "École", "Über", "Uno", "Zoom"), sorted.map { it.name })
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
