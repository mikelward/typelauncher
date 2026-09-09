package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The custom keys uploaded with every crash report. The interesting assertion
 * is the privacy one: these values leave the device, so the suite pins both the
 * shape they are allowed to take and the fact that none of the state's
 * user-owned strings reaches them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TelemetryKeysTest {

    // Sentinels that stand in for the categories `AGENTS.md` keeps off the
    // machine: an app's display name, a package name, and a typed query. Made
    // deliberately unmistakable so a substring search can't miss one.
    private val appLabel = "ZzSentinelAppLabelZz"
    private val packageName = "com.example.zzsentinelpackagezz"
    private val query = "ZzSentinelQueryZz"

    private fun app(): InstalledApp {
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = appLabel,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    private fun populatedState() = LauncherUiState(
        query = query,
        filteredApps = listOf(app()),
        dockedApps = listOf(app()),
        hiddenApps = listOf(app()),
        recentApps = listOf(app()),
        widgetIds = listOf(7, 8),
        widgetPages = listOf(listOf(7), listOf(8)),
        widgetProviderLabels = mapOf(7 to appLabel),
        destination = LauncherDestination.Agenda,
        agenda = AgendaUiState.PermissionRequired,
        appListLayout = AppListLayout.NameBeside,
        appListSortOrder = AppListSortOrder.Usage,
        themeMode = ThemeMode.Dark,
        isDefaultLauncher = true,
    )

    @Test
    fun keysCarryNoAppLabelPackageNameOrQuery() {
        val rendered = launcherTelemetryKeys(populatedState(), isLocalBuild = false)
            .entries
            .joinToString(";") { (key, value) -> "$key=$value" }

        assertFalse("app label leaked: $rendered", rendered.contains(appLabel))
        assertFalse("package name leaked: $rendered", rendered.contains(packageName))
        assertFalse("search query leaked: $rendered", rendered.contains(query))
    }

    /**
     * The floor as a shape rather than a blocklist: every value must be a
     * boolean, a number, an enum/class name, or one of those joined by `/`.
     * A field added later that renders a user's string fails here even though
     * the sentinel test above knows nothing about it — which is the point, since
     * the sentinels can only catch what the fixture happens to populate.
     */
    @Test
    fun everyValueIsABooleanNumberOrEnumConstant() {
        val safe = Regex("""[A-Za-z0-9_]+(/[A-Za-z0-9_]+)*""")
        launcherTelemetryKeys(populatedState(), isLocalBuild = true).forEach { (key, value) ->
            assertTrue("$key=$value is not a boolean/number/enum token", safe.matches(value))
        }
    }

    @Test
    fun countsReportSizesNotContents() {
        val keys = launcherTelemetryKeys(populatedState(), isLocalBuild = false)

        assertEquals("1", keys[TelemetryKey.APPS_DOCKED])
        assertEquals("1", keys[TelemetryKey.APPS_HIDDEN])
        assertEquals("2", keys[TelemetryKey.WIDGETS])
        assertEquals("2", keys[TelemetryKey.WIDGET_PAGES])
    }

    @Test
    fun settingsAndCoarseStateAreReported() {
        val keys = launcherTelemetryKeys(populatedState(), isLocalBuild = true)

        assertEquals("true", keys[TelemetryKey.LOCAL_BUILD])
        assertEquals("true", keys[TelemetryKey.DEFAULT_LAUNCHER])
        assertEquals("Agenda", keys[TelemetryKey.DESTINATION])
        assertEquals(ThemeMode.Dark.name, keys[TelemetryKey.THEME_MODE])
        assertEquals(AppListLayout.NameBeside.name, keys[TelemetryKey.APP_LIST_LAYOUT])
    }

    /** Duplicate names would silently overwrite each other in the console. */
    @Test
    fun keyNamesAreUnique() {
        val keys = launcherTelemetryKeys(LauncherUiState(), isLocalBuild = false)
        assertEquals(keys.keys.size, keys.keys.toSet().size)
        // Crashlytics caps a report at 64 custom keys; the foreground key is set
        // separately by MainActivity, so it is not in this map.
        assertTrue("too many custom keys: ${keys.size}", keys.size < 64)
    }
    // The key set must stay query-independent: the collector publishing it is
    // gated by `distinctUntilChanged`, so one query-dependent value rewrites
    // every key on every keystroke.
    @Test
    fun keysDoNotMoveWithTheSearchQuery() {
        val idle = populatedState()
        val typing = idle.copy(query = "ma", filteredApps = emptyList())

        assertEquals(
            launcherTelemetryKeys(idle, isLocalBuild = false),
            launcherTelemetryKeys(typing, isLocalBuild = false),
        )
    }

}
