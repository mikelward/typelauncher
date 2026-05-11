package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.UserHandle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkDockPrefillTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "app_launch_stats").forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun seedsFromPersonalDockedWorkAppsFirst() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val workChrome = workApp("com.android.chrome")
        val workGmail = workApp("com.google.android.gm")
        val workSlack = workApp("com.slack.app")

        prefillWorkDock(
            installedApps = listOf(workChrome, workGmail, workSlack),
            personalDockedIds = setOf(workSlack.id),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        // workSlack is not in POPULAR_APP_PACKAGES and has no launch count, so
        // without the personal-dock seed it would never appear. The seed picks
        // it up first; the popular fallback then tops up the remaining slots.
        assertTrue(workSlack.id in store.dockedAppIds)
        assertEquals(workSlack.id, store.dockedAppIds.first())
    }

    @Test
    fun fillsRemainderWithMostUsedWorkApps() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val workChrome = workApp("com.android.chrome")
        val workTeams = workApp("com.microsoft.teams")
        val workOutlook = workApp("com.microsoft.office.outlook")
        // Record more launches for Teams than Outlook so the by-usage tier
        // ranks Teams first inside the work-app set.
        repeat(5) { stats.recordLaunch(workTeams.id) }
        repeat(2) { stats.recordLaunch(workOutlook.id) }

        prefillWorkDock(
            installedApps = listOf(workChrome, workTeams, workOutlook),
            personalDockedIds = emptySet(),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        // Teams + Outlook come from the usage tier (in descending order),
        // Chrome comes from the popular-fallback tier afterwards.
        val docked = store.dockedAppIds
        assertEquals(workTeams.id, docked[0])
        assertEquals(workOutlook.id, docked[1])
        assertTrue(workChrome.id in docked)
    }

    @Test
    fun fallsBackToPopularPackagesWhenNothingElseQualifies() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val workChrome = workApp("com.android.chrome")
        val workGmail = workApp("com.google.android.gm")
        val workMaps = workApp("com.google.android.apps.maps")
        val workYouTube = workApp("com.google.android.youtube")

        // Four popular work apps are available, but with `maxSlots = 4` and
        // no contributions from tiers (a) or (b), tier (c) caps at
        // `maxSlots - 1 = 3` to leave a slot for the "+" hint — matching the
        // personal dock's first-run convention (`prefillDock` in
        // DockPrefill.kt). Without the cap, all four would land in the dock.
        prefillWorkDock(
            installedApps = listOf(workChrome, workGmail, workMaps, workYouTube),
            personalDockedIds = emptySet(),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        assertEquals(3, store.dockedAppIds.size)
        assertEquals(workChrome.id, store.dockedAppIds[0])
        assertEquals(workGmail.id, store.dockedAppIds[1])
        assertEquals(workMaps.id, store.dockedAppIds[2])
    }

    @Test
    fun fillsFullRowWhenSeededFromPersonalOrUsage() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val workSlack = workApp("com.slack.app")
        val workChrome = workApp("com.android.chrome")
        val workGmail = workApp("com.google.android.gm")
        val workYouTube = workApp("com.google.android.youtube")
        val workMaps = workApp("com.google.android.apps.maps")

        prefillWorkDock(
            installedApps = listOf(workSlack, workChrome, workGmail, workYouTube, workMaps),
            personalDockedIds = setOf(workSlack.id),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        // Slack seeds tier (a), so tier (c) targets the full row rather than
        // reserving a "+" slot. All four slots get filled.
        assertEquals(4, store.dockedAppIds.size)
        assertEquals(workSlack.id, store.dockedAppIds[0])
    }

    @Test
    fun skipsPersonalProfileApps() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val personalChrome = personalApp("com.android.chrome")
        val workGmail = workApp("com.google.android.gm")

        prefillWorkDock(
            installedApps = listOf(personalChrome, workGmail),
            personalDockedIds = setOf(personalChrome.id),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        // Even though personalChrome is in personalDockedIds, it's not a work
        // app so it must not bleed into the work dock.
        assertTrue(personalChrome.id !in store.dockedAppIds)
        assertTrue(workGmail.id in store.dockedAppIds)
    }

    @Test
    fun skipsQuietModeWorkApps() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val pausedGmail = workApp("com.google.android.gm", quiet = true)
        repeat(3) { stats.recordLaunch(pausedGmail.id) }

        prefillWorkDock(
            installedApps = listOf(pausedGmail),
            personalDockedIds = setOf(pausedGmail.id),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 4,
        )

        // No visible work apps → no seeds, no usage entries, no popular
        // fallbacks. The dock stays empty so the retry on the next non-quiet
        // load picks it up.
        assertTrue(store.dockedAppIds.isEmpty())
    }

    @Test
    fun isCappedAtMaxSlotsAcrossAllTiers() {
        val store = workStore()
        val stats = AppLaunchStatsStore(context)
        val pinnedSlack = workApp("com.slack.app")
        val workTeams = workApp("com.microsoft.teams")
        val workChrome = workApp("com.android.chrome")
        val workGmail = workApp("com.google.android.gm")
        repeat(2) { stats.recordLaunch(workTeams.id) }

        prefillWorkDock(
            installedApps = listOf(pinnedSlack, workTeams, workChrome, workGmail),
            personalDockedIds = setOf(pinnedSlack.id),
            appLaunchStatsStore = stats,
            workDockStore = store,
            maxSlots = 2,
        )

        assertEquals(2, store.dockedAppIds.size)
        assertEquals(pinnedSlack.id, store.dockedAppIds[0])
        assertEquals(workTeams.id, store.dockedAppIds[1])
    }

    private fun workStore(): DockedAppStore =
        DockedAppStore(context, DockedAppStore.WORK_PREFERENCES_NAME)

    private fun personalApp(packageName: String): InstalledApp =
        buildApp(packageName, user = Process.myUserHandle(), isWork = false, quiet = false)

    private fun workApp(packageName: String, quiet: Boolean = false): InstalledApp =
        // A distinct UserHandle so personal and work copies share neither id
        // nor app launch stats; the test only needs *a* non-personal handle,
        // not a real managed-profile one.
        buildApp(packageName, user = workUserHandle, isWork = true, quiet = quiet)

    private fun buildApp(
        packageName: String,
        user: UserHandle,
        isWork: Boolean,
        quiet: Boolean,
    ): InstalledApp {
        val component = ComponentName(packageName, "$packageName.MainActivity")
        return InstalledApp(
            name = packageName,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = user,
            isWorkApp = isWork,
            launchWithLauncherApps = true,
            isQuietMode = quiet,
        )
    }

    companion object {
        // Robolectric returns the same `Process.myUserHandle()` regardless of
        // serial number, so we fabricate a UserHandle for the work profile via
        // reflection on the constructor (it takes a single Int identifier).
        private val workUserHandle: UserHandle by lazy {
            val constructor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(10)
        }
    }
}
