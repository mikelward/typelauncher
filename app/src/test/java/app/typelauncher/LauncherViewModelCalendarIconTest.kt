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
import java.util.TimeZone

/**
 * Covers the dynamic-calendar icon refresh: date-aware calendar apps get a
 * per-day icon cache key, and the date-changed broadcast triggers a reload so a
 * long-lived launcher process picks up the new day's icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelCalendarIconTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    private val defaultZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(defaultZone)
    }

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
    fun calendarAppGetsDatedCacheKeyAndOrdinaryAppDoesNot() {
        seedApp("Mail", "com.example.mail")
        seedApp("Calendar", "com.google.android.calendar")
        val viewModel = newViewModel()
        idle()

        // Calendar is one of the popular packages the first-run dock prefill
        // pins, and docked apps are deduped out of the main list by default, so
        // it surfaces in `dockedApps` rather than `filteredApps`. The dated
        // cache token is stamped on every installed app regardless of which
        // surface it lands on, so gather all of them before asserting.
        val state = viewModel.uiState.value
        val apps = (state.filteredApps + state.dockedApps + state.workDockedApps)
            .distinctBy { it.id }
        val calendar = apps.first { it.packageName == "com.google.android.calendar" }
        val mail = apps.first { it.packageName == "com.example.mail" }

        assertTrue(
            "Calendar icon cache key must carry the per-day segment so it re-resolves at midnight",
            calendar.iconCacheToken?.contains("|cal:") == true,
        )
        assertFalse(
            "Ordinary apps must not be date-stamped — their icons don't change daily",
            mail.iconCacheToken?.contains("|cal:") == true,
        )
    }

    @Test
    fun dateChangedBroadcastReloadsTheAppList() {
        seedApp("Mail", "com.example.mail")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Cold-start load must complete before the broadcast reload runs",
            viewModel.uiState.value.isFreshAppLoadComplete,
        )
        assertFalse(
            "Newly added package isn't visible yet",
            viewModel.uiState.value.filteredApps.any { it.name == "Chat" },
        )

        // A package installed after cold start is only surfaced once the launcher
        // reloads; firing the midnight rollover broadcast must drive that reload.
        seedApp("Chat", "com.example.chat")
        context.sendBroadcast(Intent(Intent.ACTION_DATE_CHANGED))
        idle()

        assertTrue(
            "Date-changed broadcast must trigger a reload of the installed-app list",
            viewModel.uiState.value.filteredApps.any { it.name == "Chat" },
        )
    }

    // A log read days later has to be able to explain a step in its own
    // timestamps. The offset each line carries shows *that* the clock moved; only
    // the zone ids say whether the device travelled or DST turned over — and two
    // zones sharing an offset don't move the stamps at all.
    @Test
    fun aTimeZoneChangeIsNamedInTheLog() {
        TimeZone.setDefault(TimeZone.getTimeZone("Australia/Sydney"))
        seedApp("Mail", "com.example.mail")
        newViewModel()
        idle()
        LauncherDebugLog.clearForTest()

        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        idle()

        assertTrue(
            "the log must name both sides of the change",
            LauncherDebugLog.snapshot().any { it.contains("time zone Australia/Sydney -> Europe/London") },
        )
    }

    // A broadcast that doesn't actually change the zone must not add a line —
    // ACTION_TIMEZONE_CHANGED also fires on a DST transition, and a log full of
    // "Europe/London -> Europe/London" is noise crowding out real entries.
    @Test
    fun anUnchangedZoneLogsNothing() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/London"))
        seedApp("Mail", "com.example.mail")
        newViewModel()
        idle()
        LauncherDebugLog.clearForTest()

        context.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED))
        idle()

        assertEquals(
            emptyList<String>(),
            LauncherDebugLog.snapshot().filter { it.contains("time zone ") },
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
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
