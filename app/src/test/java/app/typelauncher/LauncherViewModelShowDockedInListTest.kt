package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coverage for the "Show docked apps" toggle. Default is off, so the dock
 * dedupes itself out of the main list; turning it on keeps docked apps in the
 * typed-search list as well as the dock row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelShowDockedInListTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf(
            "docked_apps",
            "work_docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun defaultHidesDockedAppsFromUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.toggleDock(mail, maxDockedApps = 4)
        idle()

        assertFalse(viewModel.uiState.value.isShowDockedAppsInList)
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertFalse(
            "Default dedupes docked apps out of the typed-search list, got $names",
            "Mail" in names,
        )
        assertTrue("Non-docked Maps should remain visible, got $names", "Maps" in names)
    }

    @Test
    fun togglingOnKeepsDockedAppsInUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.toggleDock(mail, maxDockedApps = 4)
        viewModel.setShowDockedAppsInList(true)
        idle()

        assertTrue(viewModel.uiState.value.isShowDockedAppsInList)
        assertTrue(
            "Turning the toggle on keeps docked apps in the typed-search list",
            viewModel.uiState.value.filteredApps.any { it.name == "Mail" },
        )
    }

    @Test
    fun togglingOffHidesDockedAppsFromUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.toggleDock(mail, maxDockedApps = 4)
        viewModel.setShowDockedAppsInList(false)
        idle()

        assertFalse(viewModel.uiState.value.isShowDockedAppsInList)
        // The real Type Launcher activity from the manifest also surfaces via
        // `queryIntentActivities`, so we assert what's filtered out (Mail)
        // rather than the full set.
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertFalse("Docked Mail should be hidden from list, got $names", "Mail" in names)
        assertTrue("Non-docked Maps should remain visible, got $names", "Maps" in names)
    }

    @Test
    fun typingSurfacesDockedAppsAtTopEvenWhenDedupedFromUnfilteredList() {
        // With "Show docked apps" off the dock dedupes itself out of the
        // unfiltered list. But typing hides the dock row entirely, so the
        // docked apps must reappear in the filtered results — and float to the
        // top of their tier, as if they had the highest usage — or they would
        // be buried (or unreachable) mid-search.
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val maps = viewModel.uiState.value.filteredApps.first { it.name == "Maps" }
        viewModel.toggleDock(maps, maxDockedApps = 4)
        viewModel.setShowDockedAppsInList(false)
        idle()

        // Empty query: docked Maps is deduped out (the dock row shows it).
        assertFalse(
            "Docked Maps is deduped from the empty-query list",
            viewModel.uiState.value.filteredApps.any { it.name == "Maps" },
        )

        // "ma" matches both Mail and Maps in the prefix tier. Maps is docked,
        // so once typing hides the dock it must surface and float ahead of the
        // non-docked Mail.
        viewModel.setQuery("ma")
        idle()

        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Docked Maps must surface in the typed list, got $names", "Maps" in names)
        assertTrue("Non-docked Mail must also match, got $names", "Mail" in names)
        assertEquals(
            "Docked Maps must float to the top of the matches, got $names",
            "Maps",
            names.first(),
        )
    }

    @Test
    fun compactTierSurfacesDockedAppsInUnfilteredList() {
        // The cramped-landscape Compact state drops the docks entirely, so the
        // blank-query app list is the only surface for launching apps. Docked
        // apps must therefore reappear in it (even with "Show docked apps" off),
        // or they would be unreachable until the user reveals search — the same
        // reasoning as the typed-search case above, but driven by the tier
        // rather than the query.
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val maps = viewModel.uiState.value.filteredApps.first { it.name == "Maps" }
        viewModel.toggleDock(maps, maxDockedApps = 4)
        viewModel.setShowDockedAppsInList(false)
        idle()

        // Full (the default tier): docked Maps is deduped out — the dock shows it.
        assertFalse(
            "Docked Maps is deduped from the Full blank-query list",
            viewModel.uiState.value.filteredApps.any { it.name == "Maps" },
        )

        // Compact drops the dock, so Maps must reappear in the list.
        viewModel.setHomeLandscapeTier(HomeLandscapeTier.Compact)
        idle()
        assertTrue(
            "Docked Maps must reappear in the Compact list once the dock is dropped",
            viewModel.uiState.value.filteredApps.any { it.name == "Maps" },
        )

        // Returning to Full re-hides it (the dock surface is back).
        viewModel.setHomeLandscapeTier(HomeLandscapeTier.Full)
        idle()
        assertFalse(
            "Returning to Full re-dedupes the docked app",
            viewModel.uiState.value.filteredApps.any { it.name == "Maps" },
        )
    }

    @Test
    fun compactTierFloatsWorkDockedAppsToTop() {
        // In Compact the work dock is dropped too, so its pins must not only
        // reappear in the blank-query list (the exclusion fix) but also float to
        // the top in dock order — like they do during a typed search — instead of
        // scattering by the usage/alphabetical sort. "Zzz Work" sorts last
        // alphabetically, so floating visibly moves it to the front.
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        seedApp("Zzz Work", "com.example.work")
        val viewModel = newViewModel()
        idle()
        // Flip the seeded app to an active work app and pin it to the work dock.
        viewModel.markAsActiveWorkAppForTest("com.example.work")
        viewModel.setWorkDockEnabled(true)
        idle()
        val workApp = viewModel.uiState.value.filteredApps.first { it.name == "Zzz Work" }
        viewModel.toggleWorkDock(workApp, maxDockedApps = 6)
        idle()

        // Full (the default tier) with "Show docked apps" off: the work pin is
        // deduped out — the work dock row shows it.
        assertFalse(
            "Work pin is deduped from the Full blank-query list",
            viewModel.uiState.value.filteredApps.any { it.name == "Zzz Work" },
        )

        // Compact drops the work dock, so the pin reappears and floats to the top.
        viewModel.setHomeLandscapeTier(HomeLandscapeTier.Compact)
        idle()
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Work pin must reappear in the Compact list, got $names", "Zzz Work" in names)
        assertEquals(
            "Work pin must float to the top of the Compact list, got $names",
            "Zzz Work",
            names.first(),
        )
    }

    @Test
    fun togglingBackOnRestoresDockedAppsToUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.toggleDock(mail, maxDockedApps = 4)
        viewModel.setShowDockedAppsInList(false)
        idle()
        viewModel.setShowDockedAppsInList(true)
        idle()

        assertTrue(viewModel.uiState.value.isShowDockedAppsInList)
        assertTrue(
            "Re-enabling the toggle puts the docked app back into the main list",
            viewModel.uiState.value.filteredApps.any { it.name == "Mail" },
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }
}
