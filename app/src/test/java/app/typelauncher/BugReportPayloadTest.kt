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
            recentLog = listOf("2026-08-26T09:00:00.000-04:00 D TypeLauncherDebug: hello"),
        ).text

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
        ).text

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
            previousRun = "2026-08-26T20:45:26.000-04:00 D TypeLauncherDebug: home ready\n" +
                "2026-08-26T20:45:27.090-04:00 W TypeLauncherDebug: Uncaught exception in thread main",
        )
        assertTrue(withPrevious.contains("--- Previous run (ended without a clean exit, no crash recorded) ---"))
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
            "\n2026-08-26T20:45:27.090-04:00 W TypeLauncherDebug: Uncaught exception in thread main"
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
            recentLog = listOf("2026-08-26T09:00:01.000-04:00 D TypeLauncherDebug: current hello"),
        ).text

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
        ).text

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
            previousRuns = listOf(previousRunOf((0 until 400).joinToString("\n") { "prev-$it " + "x".repeat(600) })),
        ).text

        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun aCrashedRunIsLabeledAsOneAndAKilledRunIsNot() {
        // The old report gave both the same "ended without a clean exit" heading,
        // so a user holding a crash report could not tell whether anything had
        // actually crashed — the banner said one thing and the log said nothing.
        val payload = basePayloadOf(
            listOf(
                previousRunOf("D TypeLauncherDebug: killed while backgrounded", id = "run-killed"),
                previousRunOf(
                    "W TypeLauncherDebug: Uncaught exception in thread main",
                    crashed = true,
                    id = "run-crashed",
                ),
            ),
        ).text

        assertTrue(payload.contains("--- Previous run (crashed) ---"))
        assertTrue(payload.contains("--- Previous run (ended without a clean exit, no crash recorded) ---"))
    }

    @Test
    fun aCrashedRunKeepsItsPlaceWhenOrdinaryRunsWouldFillTheBudget() {
        // Ordinary runs used to be able to crowd the crash out entirely: the
        // budget was spent newest-first across one concatenated blob, so a few
        // later cold starts left the report with no crash in it while the banner
        // was still offering one.
        val fat = { tag: String -> (0 until 400).joinToString("\n") { "$tag-$it " + "x".repeat(600) } }
        val payload = basePayloadOf(
            listOf(
                previousRunOf(
                    "W TypeLauncherDebug: Uncaught exception in thread main",
                    crashed = true,
                    id = "run-crashed",
                ),
                previousRunOf(fat("later-a"), id = "run-later-a"),
                previousRunOf(fat("later-b"), id = "run-later-b"),
            ),
        ).text

        assertTrue("the crash survives", payload.contains("Uncaught exception in thread main"))
        assertTrue("and is labeled as one", payload.contains("--- Previous run (crashed) ---"))
        assertTrue("stays shareable", payload.length <= MAX_SHARE_PAYLOAD_CHARS)
    }

    @Test
    fun onlyRunsCarriedInFullAreSafeToConsume() {
        // What the report doesn't carry, the share must not delete: the on-device
        // log is the only copy of a crash there is.
        val fat = (0 until 400).joinToString("\n") { "later-$it " + "x".repeat(600) }
        val payload = basePayloadOf(
            // Oldest first, so the small run is the newest and is served first.
            listOf(
                previousRunOf(fat, id = "run-fat-a"),
                previousRunOf(fat, id = "run-fat-b"),
                previousRunOf("D TypeLauncherDebug: small and whole", id = "run-small"),
            ),
        )

        assertTrue("the run carried whole is consumable", "run-small" in payload.consumableRunIds)
        assertTrue(
            "a run the report trimmed or omitted is not",
            payload.consumableRunIds.none { it.startsWith("run-fat") },
        )
    }

    @Test
    fun aClampedSingleLineRunIsNotTreatedAsCarriedInFull() {
        // An over-budget line comes back clamped rather than dropped, so the run
        // still has exactly one line. Comparing line counts called that "carried
        // in full" and deleted a file most of whose content never reached the
        // report — the very data loss this change exists to stop.
        val payload = basePayloadOf(listOf(previousRunOf("x".repeat(500_000), id = "run-huge")))

        assertTrue("the line is clamped", payload.text.contains("…(truncated)"))
        assertTrue("so the run is not safe to delete", payload.consumableRunIds.isEmpty())
        // Nor is it reported: the run's only line was cut, so nothing about it
        // reached the reader intact and its prompt has to stand.
        assertTrue("and not reported either", payload.reportedRunIds.isEmpty())
    }

    @Test
    fun aTrimmedCrashIsReportedEvenThoughItCannotBeConsumed() {
        // A crash bigger than the section budget can never be carried whole. It
        // still delivers the crash — the uncaught-exception entry is the newest
        // line, and trimming drops the oldest — so it counts as reported.
        val fat = (0 until 400).joinToString("\n") { "crash-$it " + "x".repeat(600) } +
            "\n2026-08-26T20:45:27.090-04:00 W TypeLauncherDebug: Uncaught exception in thread main"
        val payload = basePayloadOf(listOf(previousRunOf(fat, crashed = true, id = "run-crashed")))

        assertTrue("the crash itself survives", payload.text.contains("Uncaught exception in thread main"))
        assertTrue("it is not deletable", payload.consumableRunIds.isEmpty())
        assertTrue("but the card can stand down", "run-crashed" in payload.reportedRunIds)
    }

    @Test
    fun aRunTheSinkAlreadyTrimmedIsNeverConsumable() {
        // The sink's own read is bounded too, and a legacy file can exceed it.
        // The report then compares what it rendered against an already-trimmed
        // list, which would call the run carried in full and delete a file most
        // of which never reached the report.
        val payload = basePayloadOf(
            listOf(
                PreviousRunLog(
                    id = "run-trimmed-at-read",
                    crashed = true,
                    lines = listOf("W TypeLauncherDebug: Uncaught exception in thread main"),
                    truncatedAtRead = true,
                ),
            ),
        )

        assertTrue("it renders", payload.text.contains("Uncaught exception in thread main"))
        assertTrue("but it is not safe to delete", payload.consumableRunIds.isEmpty())
        assertTrue("though it did reach the reader", "run-trimmed-at-read" in payload.reportedRunIds)
    }

    @Test
    fun aCrashLeftOnlyTheTruncationMarkerIsNotTreatedAsReported() {
        // Crashed runs are served newest-first, so a newer crash can leave an
        // older one a sliver of budget. boundedLogTail then returns the marker
        // and nothing else — counting that as reported would stand the older
        // crash's card down having shared none of it.
        val fat = (0 until 400).joinToString("\n") { "newer-$it " + "x".repeat(600) } +
            "\n2026-08-26T09:00:00.000-04:00 W TypeLauncherDebug: Uncaught exception in the newer run"
        val payload = basePayloadOf(
            listOf(
                // Long enough not to fit the sliver the newer crash leaves, so it
                // comes back clamped rather than whole.
                previousRunOf(
                    "2026-08-26T20:45:26.000-04:00 W TypeLauncherDebug: Uncaught exception in the older run " +
                        "y".repeat(2_000),
                    crashed = true,
                    id = "run-older-crash",
                ),
                previousRunOf(fat, crashed = true, id = "run-newer-crash"),
            ),
        )

        assertTrue("the newer crash is delivered", "run-newer-crash" in payload.reportedRunIds)
        assertTrue(
            "the older crash, squeezed out, is not",
            "run-older-crash" !in payload.reportedRunIds,
        )
        assertTrue("and neither is deletable", payload.consumableRunIds.isEmpty())
    }

    @Test
    fun aCrashCarryingOnlyStackFramesIsNotTreatedAsReported() {
        // A logged throwable is one entry across many lines — the header naming
        // the exception, then its frames — and the file is read back by
        // splitting on newlines. So a budget that reaches only the deepest
        // frames would once have counted as delivering the crash, standing its
        // prompt down while omitting the exception's identity.
        val crashEntry = listOf(
            "2026-08-26T20:45:27.090-04:00 W TypeLauncherDebug: Uncaught exception in thread main",
            "java.lang.IllegalStateException: something went wrong " + "m".repeat(200),
            "\tat app.typelauncher.Example.top(Example.kt:1) " + "f".repeat(200),
            "\tat app.typelauncher.Example.bottom(Example.kt:2) " + "f".repeat(200),
        )
        val fat = (0 until 400).joinToString("\n") { "newer-$it " + "x".repeat(600) } +
            "\n2026-08-26T20:45:28.000-04:00 W TypeLauncherDebug: Uncaught exception in the newer run"
        val payload = basePayloadOf(
            listOf(
                PreviousRunLog(id = "run-older-crash", crashed = true, lines = crashEntry),
                previousRunOf(fat, crashed = true, id = "run-newer-crash"),
            ),
        )

        assertTrue("the newer crash is delivered whole", "run-newer-crash" in payload.reportedRunIds)
        assertTrue(
            "the older crash, cut inside its stack trace, is not",
            "run-older-crash" !in payload.reportedRunIds,
        )
    }

    @Test
    fun anUnseenCrashOutranksOneAlreadyDelivered() {
        // A seen crash is still a crash for labeling, but ranking it alongside an
        // unseen one let an oversized already-reported crash take the whole
        // budget on every later share, so the unseen one was never carried and
        // its prompt could never be cleared by sharing.
        val fat = (0 until 400).joinToString("\n") { "seen-$it " + "x".repeat(600) } +
            "\n2026-08-26T20:45:28.000-04:00 W TypeLauncherDebug: Uncaught exception, already reported"
        val payload = basePayloadOf(
            listOf(
                PreviousRunLog(
                    id = "run-seen-crash",
                    crashed = true,
                    crashAlreadySeen = true,
                    lines = fat.split("\n"),
                ),
                PreviousRunLog(
                    id = "run-unseen-crash",
                    crashed = true,
                    lines = listOf("2026-08-26T20:45:29.000-04:00 W TypeLauncherDebug: Uncaught exception nobody has seen"),
                ),
            ),
        )

        assertTrue("the unseen crash is served first", "run-unseen-crash" in payload.reportedRunIds)
        assertTrue("and it is carried whole", "run-unseen-crash" in payload.consumableRunIds)
    }

    @Test
    fun aReportCarryingNoPriorRunConsumesNothing() {
        assertTrue(basePayloadOf(emptyList()).consumableRunIds.isEmpty())
    }

    private fun basePayload(previousRun: String?): String = basePayloadOf(
        previousRun?.let { listOf(previousRunOf(it)) }.orEmpty(),
    ).text

    private fun basePayloadOf(previousRuns: List<PreviousRunLog>): BugReport.Payload = buildBugReportPayload(
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
        recentLog = listOf("2026-08-26T09:00:01.000-04:00 D TypeLauncherDebug: current hello"),
        previousRuns = previousRuns,
    )

    /** One prior run's file, as [DebugFileSink] hands it to the report. */
    private fun previousRunOf(
        text: String,
        crashed: Boolean = false,
        id: String = "debug-prev-1",
    ): PreviousRunLog = PreviousRunLog(id = id, crashed = crashed, lines = text.split("\n"))
}
