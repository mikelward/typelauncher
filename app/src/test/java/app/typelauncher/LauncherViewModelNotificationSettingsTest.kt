package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression coverage for the notification bar's "Settings" action with
 * work-profile apps. `ACTION_APP_NOTIFICATION_SETTINGS` carries only a
 * package name and Settings resolves it against the personal user — there is
 * no cross-profile variant — so a work app routed through it opened the
 * *personal* copy's notification settings. Work apps must take the
 * cross-profile-correct app-info path instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelNotificationSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
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
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun personalAppOpensNotificationSettingsWithItsPackage() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel(workPackages = emptySet())
        idle()
        val mail = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.mail" }

        viewModel.openNotificationSettingsFor(mail)

        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        assertNotNull(started)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started!!.action)
        assertEquals("com.example.mail", started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun workAppDoesNotOpenPersonalProfileNotificationSettings() {
        seedApp("Chat", "com.example.chat")
        val viewModel = newViewModel(workPackages = setOf("com.example.chat"))
        idle()
        val chat = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.chat" }

        viewModel.openNotificationSettingsFor(chat)

        // The work app must take the app-info route (cross-profile-correct);
        // before the fix this fired ACTION_APP_NOTIFICATION_SETTINGS, which
        // Settings resolves against the personal user.
        val started = shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .nextStartedActivity
        if (started != null) {
            assertNotEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        }
    }

    private fun newViewModel(workPackages: Set<String>): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = workPackages,
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
