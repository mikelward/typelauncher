package app.typelauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

/** What the launcher records in the bug report's app-list history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppListEventsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun clearPrefs() {
        listOf("docked_apps", "work_docked_apps", "dock_settings", "app_launch_stats", "app_metadata", "hidden_apps")
            .forEach { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test
    fun aHealthyStartupLoadIsRecordedAsLoaded() {
        val lines = launcher { _, _ -> emptyList() }

        assertTrue(lines.joinToString("\n"), lines.any { it.contains("loaded at startup:") })
    }

    @Test
    fun aStartupLoadThatReadNothingSaysItKeptTheCache() {
        // The PackageManager fallback must come back empty too, or the read
        // isn't the degraded-and-empty one: drop the test app's own entry.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, MainActivity::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP,
        )
        val lines = launcher { _, _ -> throw android.os.BadParcelableException("truncated") }

        assertTrue(lines.joinToString("\n"), lines.any { it.contains("startup load failed, kept") })
        assertTrue("not reported as loaded", lines.none { it.contains("loaded at startup") })
    }

    @Test
    fun aPartialStartupLoadSaysItWasPartial() {
        // The LauncherApps read fails; the PackageManager fallback still finds an app.
        seedApp("Mail", "com.example.mail")
        val lines = launcher { _, _ -> throw android.os.BadParcelableException("truncated") }

        assertTrue(lines.joinToString("\n"), lines.any { it.contains("startup load was partial:") })
        assertTrue("not reported as loaded", lines.none { it.contains("loaded at startup") })
    }

    @Test
    fun anInstallAndARemovalAreRecordedWithTheReloadsTheyCaused() {
        seedApp("Mail", "com.example.mail")
        val viewModel = launcherViewModel { _, _ -> emptyList() }
        appListEvents.record("test start ${System.nanoTime()}")
        val launcherApps = context.getSystemService(android.content.pm.LauncherApps::class.java)

        seedApp("Notes", "com.example.notes")
        shadowOf(launcherApps).notifyPackageAdded("com.example.notes")
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(launcherApps).notifyPackageRemoved("com.example.notes")
        shadowOf(Looper.getMainLooper()).idle()

        val lines = linesSinceStart()
        val user = android.os.Process.myUserHandle().hashCode()
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("installed com.example.notes user=$user") })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("reloaded for packageAdded com.example.notes:") })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("removed com.example.notes user=$user") })
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("reloaded for packageRemoved com.example.notes:") })
        assertTrue(viewModel.uiState.value.filteredApps.isNotEmpty())
    }

    @Test
    fun aReloadThatFailsSaysItKeptTheList() {
        seedApp("Mail", "com.example.mail")
        var failing = false
        val viewModel = launcherViewModel { _, _ ->
            if (failing) throw android.os.BadParcelableException("truncated") else emptyList()
        }
        appListEvents.record("test start ${System.nanoTime()}")

        failing = true
        viewModel.reloadInstalledAppsForTest()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))

        val lines = linesSinceStart()
        assertTrue(lines.joinToString("\n"), lines.any { it.contains("failed, kept") })
    }

    /** Starts a launcher and returns the history lines its startup added. */
    private fun launcher(
        enumerate: (android.content.pm.LauncherApps, android.os.UserHandle) -> List<android.content.pm.LauncherActivityInfo>,
    ): List<String> {
        // The history is process-wide, so earlier tests' lines are still in it.
        appListEvents.record("test start ${System.nanoTime()}")
        launcherViewModel(enumerate)
        return linesSinceStart()
    }

    private fun linesSinceStart(): List<String> {
        val lines = appListEvents.lines()
        return lines.drop(lines.indexOfLast { it.contains("test start") } + 1)
    }

    private fun launcherViewModel(
        enumerate: (android.content.pm.LauncherApps, android.os.UserHandle) -> List<android.content.pm.LauncherActivityInfo>,
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
        return viewModel
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = android.content.pm.ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = android.content.pm.ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            resolveInfo,
        )
    }
}
