package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `maybePrefillWorkDock` deliberately skips — without latching
 * `hasBeenPrefilled` — when the load sees no visible work apps (profile
 * paused, or still provisioning), promising a retry "on the next fresh load."
 * Resuming/provisioning lands via `scheduleReload`, not another cold start,
 * so the reload path must run the prefill too. Before the fix it never did:
 * the work dock rendered permanently empty for a user who enabled it while
 * their work apps were paused, until a process restart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WorkDockPrefillRetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata")
            .forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
    }

    @Test
    fun reloadAfterWorkAppsAppearSeedsTheWorkDock() {
        // Cold start with no work apps visible: the user enables the work
        // dock, and the prefill correctly skips without latching.
        val viewModel = LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            // Marks the package as a work app once it shows up in a load.
            workPackages = setOf("com.android.chrome"),
            ioDispatcher = Dispatchers.Unconfined,
        )
        idle()
        assertTrue(viewModel.uiState.value.isFreshAppLoadComplete)
        viewModel.setWorkDockEnabled(true)
        idle()
        assertTrue(
            "no work apps visible yet, so nothing to seed",
            viewModel.uiState.value.workDockedApps.isEmpty(),
        )

        // The work apps become visible (profile resumed / finished
        // provisioning), which reaches the ViewModel as a reload.
        seedApp(label = "Chrome", packageName = "com.android.chrome")
        context.sendBroadcast(Intent(Intent.ACTION_MANAGED_PROFILE_AVAILABLE))
        idle()

        assertTrue(
            "the reload must retry the skipped work-dock prefill",
            viewModel.uiState.value.workDockedApps.any { it.packageName == "com.android.chrome" },
        )
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

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
