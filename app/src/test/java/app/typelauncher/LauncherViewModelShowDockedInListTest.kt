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
 * Coverage for the "Show docked apps" toggle. Default is on, so docked apps
 * stay in the typed-search list as well as the dock row; turning it off
 * restores the original launcher behavior where the dock dedupes itself out
 * of the main list.
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
    fun defaultKeepsDockedAppsVisibleInUnfilteredList() {
        seedApp("Mail", "com.example.mail")
        seedApp("Maps", "com.example.maps")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        viewModel.toggleDock(mail, maxDockedApps = 4)
        idle()

        assertTrue(viewModel.uiState.value.isShowDockedAppsInList)
        assertTrue(
            "Default keeps docked apps in the typed-search list",
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
