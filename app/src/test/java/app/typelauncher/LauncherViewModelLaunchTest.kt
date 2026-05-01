package app.typelauncher

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelLaunchTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @After
    fun clearPrefs() {
        listOf("app_metadata", "app_launch_stats", "docked_apps", "dock_settings").forEach { name ->
            application.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
    }

    @Test
    fun launchAppSwallowsActivityNotFoundExceptionForStaleEntry() {
        shadowOf(application).setCheckActivities(true)
        val staleApp = InstalledApp(
            name = "Uninstalled",
            packageName = "app.uninstalled",
            launchIntent = Intent.makeMainActivity(
                ComponentName("app.uninstalled", "app.uninstalled.LaunchActivity"),
            ),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )
        val viewModel = LauncherViewModel(
            app = application,
            workPackages = emptySet(),
            ioDispatcher = Dispatchers.Unconfined,
        )

        viewModel.launchApp(staleApp)

        assertEquals(0, AppLaunchStatsStore(application).launchCount(staleApp.id))
    }
}
