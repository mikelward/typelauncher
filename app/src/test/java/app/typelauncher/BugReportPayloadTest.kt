package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
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
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = true,
            appListLayout = AppListLayout.NameBeside,
            dockIconSizeDp = 5,
            appListSortOrder = AppListSortOrder.Usage,
            isAgendaEnabled = true,
            dockedAppIds = listOf("0:com.example/.LaunchActivity", "0:com.example2/.LaunchActivity"),
            widgetPages = listOf(listOf(11), listOf(22)),
            log = listOf("11-04 09:00:00.000 D TypeLauncherDebug: hello"),
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
            log = emptyList(),
        )

        assertTrue("notes empty dock", payload.contains("Docked apps (0):"))
        assertTrue("notes none placeholder for dock", payload.contains("(none)"))
        assertTrue("notes none widgets", payload.contains("Widgets (0): (none)"))
        assertTrue("notes empty log", payload.contains("(no captured log lines)"))
    }

    @Test
    fun payloadIncludesPreviousRunOnlyWhenPresent() {
        assertTrue(
            "no previous-run section without a prior run",
            !basePayload(previousRun = null).contains("Previous run"),
        )
        val withPrevious = basePayload(
            previousRun = "11-04 08:59:59.000 D TypeLauncherDebug: home ready\n" +
                "11-04 09:00:00.000 W TypeLauncherDebug: Uncaught exception in thread main",
        )
        assertTrue(withPrevious.contains("--- Previous run (ended without a clean exit) ---"))
        assertTrue(withPrevious.contains("Uncaught exception in thread main"))
        // The current run's log still follows the previous-run section.
        assertTrue(withPrevious.contains("--- Log ("))
        assertTrue(withPrevious.contains("current hello"))
    }

    @Test
    fun oversizedPreviousRunKeepsItsNewestLines() {
        // The file is oldest-first and the crash entry is last; an over-cap
        // previous run must keep the tail, not the head.
        val big = (0 until 400).joinToString("\n") { "line-$it " + "x".repeat(600) } +
            "\n11-04 09:00:00.000 W TypeLauncherDebug: Uncaught exception in thread main"
        val payload = basePayload(previousRun = big)
        assertTrue("the crash entry at the end survives", payload.contains("Uncaught exception in thread main"))
        assertTrue("the oldest line is dropped", !payload.contains("line-0 "))
    }

    @Test
    fun oversizedSettingsSectionStillLeavesRoomForTheLog() {
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
            log = listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello"),
        )

        assertTrue("the settings section is truncated", payload.contains("details truncated"))
        assertTrue("the log survives", payload.contains("current hello"))
        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
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
            log = log,
        )

        assertTrue("the newest line survives", payload.contains("the newest event"))
        assertTrue("the oldest line is dropped", !payload.contains("line-0 "))
        // The heading says older lines are dropped unconditionally, because
        // whether any were is not knowable here: the log bounds itself before
        // the report sees it.
        assertTrue("and says so", payload.contains("older lines are dropped"))
        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun aSingleOversizedPreviousRunLineIsClampedNotPassedThrough() {
        // `cacheDir` survives app upgrades, so a prior-run file written before
        // the per-entry cap existed can hold one arbitrarily long line. Keeping
        // it whole (the old "always keep the newest line" rule) would blow the
        // ceiling this bounding exists to enforce.
        val payload = basePayload(previousRun = "x".repeat(500_000))

        assertTrue("the line is clamped", payload.contains("…(truncated)"))
        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
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
            log = (0 until 300).map { "line-$it " + "x".repeat(600) },
            previousRun = (0 until 400).joinToString("\n") { "prev-$it " + "x".repeat(600) },
        )

        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun theLogIsOneSectionAndFollowsThePreviousRun() {
        // Startup context used to get its own "Process start" section, because
        // the ring buffer had always evicted those lines by the time a report
        // was captured. The log restores them ahead of its kept tail now, in
        // order, so the report reads as one sequence: last run, then this one
        // from its start.
        val payload = basePayload(
            previousRun = "11-04 08:59:59.000 D home ready",
            log = listOf(
                "11-04 09:00:00.000 D processExit reason=packageUpdated",
                "11-04 09:00:01.000 D current hello",
            ),
        )

        assertTrue(payload.contains("processExit reason=packageUpdated"))
        assertTrue(payload.contains("current hello"))
        assertTrue(
            "one log section, after the previous run",
            payload.indexOf("--- Previous run") < payload.indexOf("--- Log ("),
        )
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
            previousRun = null,
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
        val payload = basePayload(previousRun = null, log = emptyList())

        assertTrue(payload.contains("--- Log (0 lines, newest last"))
        assertTrue(payload.contains("(no captured log lines)"))
        assertTrue(
            "counts what is here and claims no total",
            !payload.contains(" of ") || !payload.contains("shown"),
        )
        assertTrue("claims nothing about why it is empty", !payload.contains("(nothing recorded)"))
    }

    @Test
    fun payloadIncludesIconCacheCountersOnlyWhenSupplied() {
        assertTrue(
            "no section without stats",
            !basePayload(previousRun = null).contains("--- Icon cache ---"),
        )
        val payload = basePayload(
            previousRun = null,
            iconCache = AppIconLoader.CacheStats(entries = 128, bytes = 6_458_904, hits = 32_949, misses = 15_301),
        )
        assertTrue(payload.contains("--- Icon cache ---"))
        assertTrue(payload.contains("Entries: 128 (6458904 bytes)"))
        assertTrue(payload.contains("Lookups: 32949 hits, 15301 misses"))
    }

    private fun basePayload(
        previousRun: String?,
        log: List<String> = listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello"),
        iconCache: AppIconLoader.CacheStats? = null,
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
        log = log,
        previousRun = previousRun,
        iconCache = iconCache,
    )
}
