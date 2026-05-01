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
            downrankedAppIds = emptySet(),
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
            downrankedAppIds = emptySet(),
        )

        assertEquals(listOf("Gallery", "Gmail", "Google Maps"), filtered.map { it.name })
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
            downrankedAppIds = emptySet(),
        )

        // None start with "oo" or anchor-match it; the first three contain it
        // as a substring and stay in their incoming alphabetical order;
        // "Snake" drops out.
        assertEquals(listOf("Cool", "Door", "Foo"), filtered.map { it.name })
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
