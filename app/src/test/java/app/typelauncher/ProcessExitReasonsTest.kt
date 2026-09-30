package app.typelauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager

/**
 * The launcher's wiring of androidlog's shared `ProcessExits`, driven through
 * the real platform queries (Robolectric's `ActivityManager`). The reason and
 * importance names, the order and the description bound are the library's,
 * and `ProcessExitsTest` there covers them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProcessExitReasonsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearLog() {
        LauncherDebugLog.resetForTest()
    }

    private fun seedExit(
        reason: Int,
        importance: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        timestamp: Long = 1_700_000_000_000L,
        description: String = "stopped by the installer",
    ) {
        val exitInfo = ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
            .setReason(reason)
            .setImportance(importance)
            .setTimestamp(timestamp)
            .setDescription(description)
            .build()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        shadowOf(activityManager).addApplicationExitInfo(exitInfo)
    }

    private fun loggedLines(): List<String> = LauncherDebugLog.snapshot()

    @Test
    fun recordsEachRecentExitWithItsReasonNamed() {
        // The library's tests prove the names are right; this proves the
        // query actually runs and its answers reach the log. Without it the
        // suite stays green if the collection is deleted, asks for the wrong
        // package, or drops its results on the floor — which is the whole
        // feature.
        seedExit(ApplicationExitInfo.REASON_CRASH)
        seedExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED)

        logRecentProcessExits(context)

        val exitLines = loggedLines().filter { it.contains("processExit ") }
        assertEquals(2, exitLines.size)
        assertTrue(exitLines.any { it.contains("reason=crash") })
        assertTrue(exitLines.any { it.contains("reason=packageUpdated") })
        // Oldest first, ending on the exit that explains this start. The
        // platform hands them back newest-first, and the shared report
        // truncates its pinned section from the head — so leaving them in the
        // platform's order would have dropped the most recent exit and called
        // it older (Codex on PR #689). The shadow returns them in the order
        // they were added, so the second seeded exit is the newest.
        assertTrue(
            exitLines.toString(),
            exitLines.last().contains("reason=packageUpdated"),
        )
        // The platform's own account of the death rides along, and the
        // on-device log carries it in full — that is what the report is read
        // for. (It is withheld from the Crashlytics mirror; the mirror test
        // below drives that.)
        assertTrue(
            exitLines.toString(),
            exitLines.all { it.contains("description=stopped by the installer") },
        )
        // Importance says whether the launcher was on screen when it died,
        // which is what separates a routine background reclaim from the
        // process dying out from under someone looking at it.
        assertTrue(exitLines.toString(), exitLines.all { it.contains("importance=foreground") })
    }

    @Test
    fun recordsTheExitsEvenWhenThePackageLookupCannotRun() {
        // The package timestamps are the optional half; the exit records are
        // the point, so a failed lookup must not cost them. The package name is
        // forced to one that does not resolve, which is what a failing lookup
        // looks like from here.
        seedExit(ApplicationExitInfo.REASON_LOW_MEMORY)

        logRecentProcessExits(NonResolvingPackageContext(context))

        assertTrue(
            loggedLines().toString(),
            loggedLines().any { it.contains("processExit reason=lowMemory") },
        )
        assertTrue(
            loggedLines().toString(),
            loggedLines().any { it.contains("ownPackage query failed") },
        )
        // And pinned, so the failure outlives the ring buffer alongside the
        // records it sits beside. Without it a report whose ring has turned
        // over restores the startup lines with no package timestamps among
        // them and nothing saying why, which reads as a complete diagnostic
        // (Codex on PR #689).
        assertTrue(
            LauncherDebugLog.pinnedSnapshot().toString(),
            LauncherDebugLog.pinnedSnapshot().any { it.contains("ownPackage unavailable reason=notFound") },
        )
    }

    /**
     * A context whose package name resolves to nothing, so the package-info
     * lookup fails while the exit-reason query — which is asked by the same
     * name but answered from the shadow's own store — still returns records.
     */
    private class NonResolvingPackageContext(
        base: android.content.Context,
    ) : android.content.ContextWrapper(base) {
        override fun getPackageName(): String = "app.typelauncher.absent"
    }

    @Test
    fun everyRecordedStartupLineIsPinned() {
        // The whole point of the section: these are written once at startup and
        // read hours later, by which time the ring has evicted them.
        seedExit(ApplicationExitInfo.REASON_PACKAGE_UPDATED)

        logRecentProcessExits(context)

        val pinned = LauncherDebugLog.pinnedSnapshot()
        assertTrue(pinned.toString(), pinned.any { it.contains("processExit reason=packageUpdated") })
        assertTrue(pinned.toString(), pinned.any { it.contains("ownPackage lastUpdateTime=") })
    }

    @Test
    fun aFullBatchOfLongExitsReachesTheReportAfterTheRingHasDroppedIt() {
        // What the report's pinned budget has to hold: every record the
        // collector keeps, each with a description far past its bound, pushed
        // out of the ring by a busy run before anyone shares a report.
        repeat(ProcessExits.DEFAULT_MAX_RECORDS) {
            seedExit(ApplicationExitInfo.REASON_ANR, description = "Input dispatching timed out ".repeat(100))
        }
        logRecentProcessExits(context)
        repeat(DebugLog.DEFAULT_MAX_ENTRIES + 50) { LauncherDebugLog.event("busy %s", it) }
        // Only the pinned copy can carry them now.
        assertTrue(LauncherDebugLog.snapshot().none { it.contains("processExit ") })

        val report = reportLogLines()

        assertEquals(
            report.take(10).toString(),
            ProcessExits.DEFAULT_MAX_RECORDS,
            report.count { it.contains("processExit reason=anr") },
        )
        assertTrue(report.any { it.contains("ownPackage lastUpdateTime=") })
        assertTrue(report.last().endsWith("busy ${DebugLog.DEFAULT_MAX_ENTRIES + 49}"))
    }

    @Test
    fun saysSoWhenThePlatformHasNoExitRecords() {
        // A fresh install, or a device that has pruned its records. The line
        // matters because its absence would otherwise be ambiguous with the
        // query having failed or never run.
        logRecentProcessExits(context)

        assertTrue(loggedLines().any { it.contains("processExits none") })
        assertFalse(loggedLines().any { it.contains("processExit reason=") })
    }

    // The correlation these lines exist for (an exit whose time matches the
    // package's update time is the installer swapping the APK, not a bug) is
    // only makeable if both times reach the mirror. Driven through a fresh log
    // with an off-device sink, so this asserts what the mirror is actually
    // handed rather than how a format string would render.
    @Test
    fun theProcessAndPackageTimesReachTheCrashlyticsMirrorButTheDescriptionDoesNot() {
        seedExit(ApplicationExitInfo.REASON_CRASH)
        val offDevice = mutableListOf<String>()
        val log = DebugLog().apply { addSink({ offDevice += it }, DebugLog.Destination.OFF_DEVICE) }

        logRecentProcessExits(context, log)

        val exitLine = offDevice.single { it.contains("processExit ") }
        assertTrue(exitLine, exitLine.contains("reason=crash"))
        assertTrue(exitLine, exitLine.contains("timestamp=2023-Nov-14T22:13:20Z"))
        // The platform's own text can name another package, so it stays on the device.
        assertFalse(exitLine, exitLine.contains("stopped by the installer"))
        val packageLine = offDevice.single { it.contains("ownPackage ") }
        assertTrue(packageLine, packageLine.contains("lastUpdateTime="))
        assertFalse(packageLine, packageLine.contains("•••"))
    }
}
