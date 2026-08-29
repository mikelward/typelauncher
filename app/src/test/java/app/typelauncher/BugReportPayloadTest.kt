package app.typelauncher

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
            recentLog = listOf("11-04 09:00:00.000 D TypeLauncherDebug: hello"),
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
            zoneId = ZoneId.of("Australia/Sydney"),
            isDockEnabled = false,
            appListLayout = AppListLayout.IconOnly,
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
        assertTrue(withPrevious.contains("--- Recent log"))
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
            recentLog = listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello"),
        )

        assertTrue("the settings section is truncated", payload.contains("details truncated"))
        assertTrue("the recent log survives", payload.contains("current hello"))
        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun oversizedRecentLogKeepsItsNewestLines() {
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
            recentLog = log,
        )

        assertTrue("the newest line survives", payload.contains("the newest event"))
        assertTrue("the oldest line is dropped", !payload.contains("line-0 "))
        assertTrue("and says so", payload.contains("older line(s) omitted"))
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
            recentLog = (0 until 300).map { "line-$it " + "x".repeat(600) },
            previousRun = (0 until 400).joinToString("\n") { "prev-$it " + "x".repeat(600) },
        )

        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun payloadCarriesTheProcessStartSectionBetweenTheTwoRuns() {
        // The section exists because the ring buffer had already evicted these
        // lines in every real report, so what matters is that it renders from
        // the pinned list independently of the recent log — and that it sits
        // where the transition reads in order: last run, then how this one
        // began, then this run.
        val payload = basePayload(
            previousRun = "11-04 08:59:59.000 D TypeLauncherDebug: home ready",
            pinnedLog = listOf("11-04 09:00:00.000 D TypeLauncherDebug: processExit reason=packageUpdated"),
        )

        assertTrue(payload.contains("--- Process start (as of capture) ---"))
        assertTrue(payload.contains("processExit reason=packageUpdated"))
        assertTrue(
            "sits after the previous run and before this run's log",
            payload.indexOf("--- Previous run") <
                payload.indexOf("--- Process start (as of capture) ---"),
        )
        assertTrue(
            payload.indexOf("--- Process start (as of capture) ---") < payload.indexOf("--- Recent log"),
        )
    }

    @Test
    fun processStartSectionIsLabelledASnapshotAndClaimsNothingMore() {
        // These lines are written from background work, so the section is a
        // picture of the moment share was tapped and nothing more. An earlier
        // version asserted an empty section meant the startup diagnostic had not
        // run, and keeping that assertion true drew eight review findings in a
        // row; the heading now says what the section actually is.
        val payload = basePayload(previousRun = null, pinnedLog = emptyList())

        assertTrue(payload.contains("--- Process start (as of capture) ---"))
        assertTrue(payload.contains("(nothing captured)"))
        assertTrue("claims nothing about why it is empty", !payload.contains("(nothing recorded)"))
    }

    @Test
    fun oversizedProcessStartSectionKeepsItsNewestLines() {
        // The section is truncated from its head, so its lines have to be
        // chronological — the exit that explains this start is the newest one,
        // and dropping it while labelling it "older" is the one outcome this
        // section must not produce (Codex on PR #689).
        val pinned = (0 until 40).map { "line-$it " + "x".repeat(600) } +
            "11-04 09:00:00.000 D TypeLauncherDebug: processExit reason=packageUpdated"

        val payload = basePayload(previousRun = null, pinnedLog = pinned)

        assertTrue("the newest line survives", payload.contains("processExit reason=packageUpdated"))
        assertTrue("the oldest line is dropped", !payload.contains("line-0 "))
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
        pinnedLog: List<String> = emptyList(),
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
        recentLog = listOf("11-04 09:00:01.000 D TypeLauncherDebug: current hello"),
        previousRun = previousRun,
        pinnedLog = pinnedLog,
        iconCache = iconCache,
    )
}
