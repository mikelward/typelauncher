package app.typelauncher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class BugReportPayloadTest {
    @Test
    fun payloadIncludesBuildDeviceSettingsAndLogSections() {
        val payload = buildBugReportPayload(
            nowMillis = 1_700_000_000_000L,
            versionName = "1.2.3",
            versionCode = 42L,
            buildType = "debug",
            applicationId = "app.typelauncher",
            isDebuggable = true,
            deviceManufacturer = "Pixel",
            deviceModel = "Pixel Test",
            androidRelease = "14",
            androidSdkInt = 34,
            locale = Locale.US,
            isDockEnabled = true,
            appListLayout = AppListLayout.NameBeside,
            isShowAppNameHint = true,
            isShowSearchSuggestion = true,
            isShowDockedAppsInList = true,
            dockIconSizeDp = 5,
            appListSortOrder = AppListSortOrder.Usage,
            isAgendaEnabled = true,
            dockedAppIds = listOf("0:com.example/.LaunchActivity", "0:com.example2/.LaunchActivity"),
            widgetPages = listOf(listOf(11), listOf(22)),
            recentLog = listOf("11-04 09:00:00.000 D TypeLauncherDebug: hello"),
        )

        assertTrue("includes header", payload.startsWith("Type Launcher bug report"))
        assertTrue("includes version", payload.contains("Version: 1.2.3 (42)"))
        assertTrue("includes build type", payload.contains("Build type: debug"))
        assertTrue("includes application id", payload.contains("Application id: app.typelauncher"))
        assertTrue("includes device", payload.contains("Model: Pixel Pixel Test"))
        assertTrue("includes android release", payload.contains("Android: 14 (SDK 34)"))
        assertTrue("includes locale", payload.contains("Locale: en-US"))
        assertTrue("includes dock enabled", payload.contains("Dock enabled: true"))
        assertTrue("includes app list layout", payload.contains("App list layout: NameBeside"))
        assertTrue("includes top match name", payload.contains("Show top match name: true"))
        assertTrue("includes autocomplete hint", payload.contains("Show autocomplete hint: true"))
        assertTrue("includes show docked in list", payload.contains("Show docked apps in list: true"))
        assertTrue("includes dock icon size", payload.contains("Dock icon size: 5dp"))
        assertTrue("includes sort order", payload.contains("App list sort order: Usage"))
        assertTrue("includes agenda setting", payload.contains("Agenda enabled: true"))
        assertTrue("includes docked apps count", payload.contains("Docked apps (2):"))
        assertTrue("includes docked app id", payload.contains("0:com.example/.LaunchActivity"))
        assertTrue("includes widgets summary", payload.contains("Widgets (2): 11, 22"))
        assertTrue("includes widget pages", payload.contains("Page 2: 22"))
        assertTrue("includes recent log header", payload.contains("--- Recent log"))
        assertTrue("includes recent log entry", payload.contains("hello"))
    }

    @Test
    fun payloadHandlesEmptyDockWidgetsAndLog() {
        val payload = buildBugReportPayload(
            nowMillis = 1_700_000_000_000L,
            versionName = "1.0",
            versionCode = 1L,
            buildType = "release",
            applicationId = "app.typelauncher",
            isDebuggable = false,
            deviceManufacturer = "Generic",
            deviceModel = "Robolectric",
            androidRelease = "14",
            androidSdkInt = 34,
            locale = Locale.US,
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
            isShowAppNameHint = false,
            isShowSearchSuggestion = false,
            isShowDockedAppsInList = false,
            dockIconSizeDp = 4,
            appListSortOrder = AppListSortOrder.Alphabetical,
            isAgendaEnabled = false,
            dockedAppIds = emptyList(),
            widgetPages = listOf(emptyList()),
            recentLog = emptyList(),
        )

        assertTrue("notes empty dock", payload.contains("Docked apps (0):"))
        assertTrue("notes none placeholder for dock", payload.contains("(none)"))
        assertTrue("notes none widgets", payload.contains("Widgets (0): (none)"))
        assertTrue("notes empty log", payload.contains("(no captured log lines)"))
    }
}
