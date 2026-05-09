package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelReloadTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun personalProfileAppsAreNotMarkedQuiet() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.name == "Mail" }
        // Personal-profile apps must never be marked as quiet because the
        // personal profile cannot be paused. The dimmed-icon treatment in
        // `AppIcon` is reserved for work-profile apps when their profile is
        // in quiet mode.
        assertFalse("Personal-profile app must not be marked quiet", mail.isQuietMode)
    }

    @Test
    fun reloadAfterPackageInstallSurfacesNewApp() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before reload runs",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        val initialNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue("Cold-start load surfaces seeded app", initialNames.contains("Mail"))
        assertFalse("Newly added package isn't visible yet", initialNames.contains("Chat"))

        seedApp("Chat", "com.example.chat")
        viewModel.reloadInstalledAppsForTest()
        idle()

        val updatedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertTrue(
            "Reload picks up the post-install package",
            updatedNames.containsAll(listOf("Mail", "Chat")),
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        // Run the load IO synchronously on the calling thread so the test
        // doesn't have to coordinate with a real `Dispatchers.IO` worker.
        // `idle()` then drains the Main-dispatched continuations.
        ioDispatcher = Dispatchers.Unconfined,
    )

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

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
