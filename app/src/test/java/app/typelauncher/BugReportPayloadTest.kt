package app.typelauncher

import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ProcessExits
import com.mikelward.androidlog.boundedLogTail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.util.Locale

class BugReportPayloadTest {
    @get:Rule
    val folder = TemporaryFolder()

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
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = true,
            appListLayout = AppListLayout.NameBeside,
            dockIconSizeDp = 5,
            appListSortOrder = AppListSortOrder.Usage,
            isAgendaEnabled = true,
            dockedAppIds = listOf("0:com.example/.LaunchActivity", "0:com.example2/.LaunchActivity"),
            widgetPages = listOf(listOf(11), listOf(22)),
            log = fixedLog(listOf("11-04 09:00:00.000 D TypeLauncherDebug: hello")),
        )

        assertTrue("includes header", payload.startsWith("Type Launcher bug report"))
        assertTrue("includes version", payload.contains("Version: 1.2.3 (42)"))
        assertTrue("includes build type", payload.contains("Build type: debug"))
        assertTrue("includes application id", payload.contains("Application id: app.typelauncher"))
        assertTrue("includes device", payload.contains("Model: Pixel Pixel Test"))
        assertTrue("includes android release", payload.contains("Android: 14 (SDK 34)"))
        assertTrue("includes locale", payload.contains("Locale: en-US"))
        assertTrue("includes time zone", payload.contains("Time zone: Australia/Sydney"))
        // The capture stamp carries the offset for the same reason every log
        // line does: the report is read somewhere else, later.
        assertTrue(
            "captured stamp carries the offset",
            payload.contains("Captured: 2023-11-15T09:13:20.000+11:00"),
        )
        assertTrue("includes dock enabled", payload.contains("Dock enabled: true"))
        assertTrue("includes app list layout", payload.contains("App list layout: NameBeside"))
        assertTrue("includes dock icon size", payload.contains("Dock icon size: 5dp"))
        assertTrue("includes sort order", payload.contains("App list sort order: Usage"))
        assertTrue("includes agenda setting", payload.contains("Agenda enabled: true"))
        assertTrue("includes docked apps count", payload.contains("Docked apps (2):"))
        assertTrue("includes docked app id", payload.contains("0:com.example/.LaunchActivity"))
        assertTrue("includes widgets summary", payload.contains("Widgets (2): 11, 22"))
        assertTrue("includes widget pages", payload.contains("Page 2: 22"))
        assertTrue("includes the log header", payload.contains("--- Log ("))
        assertTrue("includes the log entry", payload.contains("hello"))
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
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
            dockIconSizeDp = 4,
            appListSortOrder = AppListSortOrder.Alphabetical,
            isAgendaEnabled = false,
            dockedAppIds = emptyList(),
            widgetPages = listOf(emptyList()),
            log = fixedLog(emptyList()),
        )

        assertTrue("notes empty dock", payload.contains("Docked apps (0):"))
        assertTrue("notes none placeholder for dock", payload.contains("(none)"))
        assertTrue("notes none widgets", payload.contains("Widgets (0): (none)"))
        assertTrue("notes empty log", payload.contains("(no captured log lines)"))
    }

    @Test
    fun oversizedSettingsSectionStillLeavesRoomForTheLog() {
        var logBudget = 0
        // A long docked-app list used to push the whole report past the Binder
        // limit, and prefix-truncating it would drop the log that is appended
        // last — exactly the diagnostic the report exists for.
        val payload = buildBugReportPayload(
            nowMillis = 1_700_000_000_000L,
            versionName = "1.0",
            versionCode = 1L,
            buildType = "debug",
            applicationId = "app.typelauncher",
            isDebuggable = true,
            deviceManufacturer = "Generic",
            deviceModel = "Robolectric",
            androidRelease = "14",
            androidSdkInt = 34,
            locale = Locale.US,
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
            dockIconSizeDp = 4,
            appListSortOrder = AppListSortOrder.Alphabetical,
            isAgendaEnabled = false,
            dockedAppIds = (0 until 2_000).map { "0:com.example.package$it/.LaunchActivity" },
            widgetPages = listOf(emptyList()),
            log = { budget -> logBudget = budget; listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello") },
        )

        assertTrue("the settings section is truncated", payload.contains("details truncated"))
        assertTrue("the log survives", payload.contains("current hello"))
        // The log's budget still holds a whole process-exit batch with room for
        // recent lines: a smaller one would drop the oldest exits, which are
        // what explains how the last run ended.
        assertTrue("budget $logBudget", logBudget > ProcessExits.maxBatchChars())
        assertTrue("fits the app's share", payload.length <= DebugReport.APP_SECTION_CHARS)
    }

    @Test
    fun anOversizedLogKeepsItsNewestLines() {
        val log = (0 until 300).map { "line-$it " + "x".repeat(600) } + "the newest event"
        val payload = buildBugReportPayload(
            nowMillis = 1_700_000_000_000L,
            versionName = "1.0",
            versionCode = 1L,
            buildType = "debug",
            applicationId = "app.typelauncher",
            isDebuggable = true,
            deviceManufacturer = "Generic",
            deviceModel = "Robolectric",
            androidRelease = "14",
            androidSdkInt = 34,
            locale = Locale.US,
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
            dockIconSizeDp = 4,
            appListSortOrder = AppListSortOrder.Alphabetical,
            isAgendaEnabled = false,
            dockedAppIds = emptyList(),
            widgetPages = listOf(emptyList()),
            log = fixedLog(log),
        )

        assertTrue("the newest line survives", payload.contains("the newest event"))
        assertTrue("the oldest line is dropped", !payload.contains("line-0 "))
        // The heading says older lines are dropped unconditionally, because
        // whether any were is not knowable here: the log bounds itself before
        // the report sees it.
        assertTrue("and says so", payload.contains("older lines are dropped"))
        assertTrue("fits the app's share", payload.length <= DebugReport.APP_SECTION_CHARS)
    }

    @Test
    fun everySectionAtItsLargestStaysUnderTheShareCeiling() {
        val payload = buildBugReportPayload(
            nowMillis = 1_700_000_000_000L,
            versionName = "1.0",
            versionCode = 1L,
            buildType = "debug",
            applicationId = "app.typelauncher",
            isDebuggable = true,
            deviceManufacturer = "Generic",
            deviceModel = "Robolectric",
            androidRelease = "14",
            androidSdkInt = 34,
            locale = Locale.US,
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
            dockIconSizeDp = 4,
            appListSortOrder = AppListSortOrder.Alphabetical,
            isAgendaEnabled = false,
            dockedAppIds = (0 until 2_000).map { "0:com.example.package$it/.LaunchActivity" },
            widgetPages = listOf(emptyList()),
            log = fixedLog((0 until 300).map { "line-$it " + "x".repeat(600) }),
            appListChanges = (0 until 200).map { "change-$it " + "y".repeat(100) },
        )

        // Within the app's share, so the library never has to cut it from the
        // middle to make room for the earlier runs.
        assertTrue("fits the app's share", payload.length <= DebugReport.APP_SECTION_CHARS)
    }

    @Test
    fun theLogIsOneSection() {
        // Startup context used to get its own "Process start" section, because
        // the ring buffer had always evicted those lines by the time a report
        // was captured. The log restores them ahead of its kept tail now, in
        // order, so the report reads as one sequence: this run from its start.
        val payload = basePayload(
            log = listOf(
                "11-04 09:00:00.000 D processExit reason=packageUpdated",
                "11-04 09:00:01.000 D current hello",
            ),
        )

        assertTrue(payload.contains("processExit reason=packageUpdated"))
        assertTrue(payload.contains("current hello"))
        assertEquals(
            "one log section",
            1,
            Regex("--- Log \\(").findAll(payload).count(),
        )
        assertTrue(
            "in order within it",
            payload.indexOf("processExit reason=packageUpdated") < payload.indexOf("current hello"),
        )
    }

    @Test
    fun theLogHeadingNeverClaimsTheReportIsComplete() {
        // The log bounds itself before the report sees it, so the list handed
        // in is already the kept tail and nothing here can count what was
        // dropped upstream. The heading used to read "N of M shown" with M the
        // size of that list — "120 of 120 shown" on a report that had dropped
        // 180 lines, which claims completeness exactly when it is absent
        // (Codex on PR #706). It now counts what is present and says the log
        // is bounded, with no total to be wrong about.
        val payload = basePayload(
            log = (0 until 120).map { "11-04 09:00:00.000 D line-$it" },
        )

        assertTrue(payload.contains("--- Log (120 lines, newest last"))
        assertTrue("discloses the bound", payload.contains("older lines are dropped"))
        assertTrue("claims no total", !payload.contains("120 of 120"))
        assertTrue("nothing was dropped here", payload.contains("line-0"))
    }

    @Test
    fun anEmptyLogSaysSoAndClaimsNothingMore() {
        // The lines are written from background work, so an empty section is a
        // picture of the moment share was tapped and nothing more. An earlier
        // version read it as "the startup diagnostic never ran", and keeping
        // that claim true drew eight review findings in a row.
        val payload = basePayload(log = emptyList())

        assertTrue(payload.contains("--- Log (0 lines, newest last"))
        assertTrue(payload.contains("(no captured log lines)"))
        assertTrue(
            "counts what is here and claims no total",
            !payload.contains(" of ") || !payload.contains("shown"),
        )
        assertTrue("claims nothing about why it is empty", !payload.contains("(nothing recorded)"))
    }

    @Test
    fun payloadListsOtherSettingsAndHomeWidgets() {
        val payload = basePayload(
            otherSettings = listOf("Keyboard auto-shown" to "true", "Home widgets shown" to "false"),
            homeWidgetIds = listOf(31, 32),
        )
        val settings = payload.substringAfter("--- Settings ---").substringBefore("Docked apps")
        assertTrue("keyboard setting in Settings", settings.contains("Keyboard auto-shown: true"))
        assertTrue("home widgets setting in Settings", settings.contains("Home widgets shown: false"))
        assertTrue("home widget ids", payload.contains("Home widgets (2): 31, 32"))
        assertTrue("no home widgets", basePayload().contains("Home widgets (0): (none)"))
    }

    @Test
    fun payloadIncludesIconCacheCountersOnlyWhenSupplied() {
        assertTrue(
            "no section without stats",
            !basePayload().contains("--- Icon cache ---"),
        )
        val payload = basePayload(
            iconCache = AppIconLoader.CacheStats(entries = 128, bytes = 6_458_904, hits = 32_949, misses = 15_301),
        )
        assertTrue(payload.contains("--- Icon cache ---"))
        assertTrue(payload.contains("Entries: 128 (6458904 bytes)"))
        assertTrue(payload.contains("Lookups: 32949 hits, 15301 misses"))
    }

    @Test
    fun appListChangesGetTheirOwnSectionAheadOfTheLog() {
        val payload = basePayload(
            appListChanges = listOf("10-06 09:08:35.000 installed com.example.app user=0"),
        )

        val changes = payload.indexOf("--- App list changes (1, newest last) ---")
        assertTrue(changes >= 0)
        assertTrue(payload.contains("installed com.example.app user=0"))
        assertTrue("ahead of the log", changes < payload.indexOf("--- Log ("))
    }

    @Test
    fun anEmptyAppListHistorySaysSo() {
        assertTrue(basePayload().contains("(none since the launcher started)"))
    }

    @Test
    fun aLongAppListHistoryKeepsItsNewestLinesAndStaysShareable() {
        val payload = basePayload(
            log = (0 until 300).map { "line-$it " + "x".repeat(600) },
            appListChanges = (0 until 200).map { "change-$it " + "y".repeat(100) } + "the newest change",
        )

        assertTrue(payload.contains("the newest change"))
        assertTrue("the oldest is dropped", !payload.contains("change-0 "))
        assertTrue("fits the app's share", payload.length <= DebugReport.APP_SECTION_CHARS)
    }

    private fun basePayload(
        log: List<String> = listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello"),
        iconCache: AppIconLoader.CacheStats? = null,
        otherSettings: List<Pair<String, String>> = emptyList(),
        homeWidgetIds: List<Int> = emptyList(),
        appListChanges: List<String> = emptyList(),
    ): String = buildBugReportPayload(
        nowMillis = 1_700_000_000_000L,
        versionName = "1.0",
        versionCode = 1L,
        buildType = "debug",
        applicationId = "app.typelauncher",
        isDebuggable = true,
        deviceManufacturer = "Generic",
        deviceModel = "Robolectric",
        androidRelease = "14",
        androidSdkInt = 34,
        locale = Locale.US,
        zoneId = ZoneId.of("Australia/Sydney"),
        isDockEnabled = false,
        appListLayout = AppListLayout.IconOnly,
        dockIconSizeDp = 4,
        appListSortOrder = AppListSortOrder.Alphabetical,
        isAgendaEnabled = false,
        dockedAppIds = emptyList(),
        widgetPages = listOf(emptyList()),
        log = fixedLog(log),
        iconCache = iconCache,
        otherSettings = otherSettings,
        homeWidgetIds = homeWidgetIds,
        appListChanges = appListChanges,
    )

    @Test
    fun theWholeReportCarriesTheEarlierRunsUncutAndStaysShareable() {
        // The library appends the earlier runs after this app's section and
        // holds the whole report to its ceiling; a section that stays inside
        // its share is never cut from the middle to make room.
        val dir = folder.newFolder()
        File(dir, "androidlog-prev-1.log").writeText(
            (0 until 400).joinToString("\n") { "prev-$it " + "x".repeat(600) } + "\nUncaught exception in thread main\n",
        )
        val sink = DebugFileSink(DebugLog(), dir)
        val section = basePayload(
            log = (0 until 300).map { "line-$it " + "x".repeat(600) },
            appListChanges = (0 until 200).map { "change-$it " + "y".repeat(100) },
        )

        val report = DebugReport.collect(DebugLog(), sink, heading = PREVIOUS_RUN_HEADING) { section }

        assertTrue("stays shareable", report.text.length <= MAX_SHARE_PAYLOAD_CHARS)
        assertTrue("the app's section is whole", report.text.startsWith(section))
        assertTrue(report.text.contains(PREVIOUS_RUN_HEADING))
        assertTrue("the crash at the end of the run survives", report.text.contains("Uncaught exception in thread main"))
    }

    /** The log as [reportLogLines] hands it over: the newest lines within the budget it is given. */
    private fun fixedLog(lines: List<String>): (Int) -> List<String> = { budget -> boundedLogTail(lines, budget) }
}
