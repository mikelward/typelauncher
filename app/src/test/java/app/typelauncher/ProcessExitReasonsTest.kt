package app.typelauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.annotation.Config

/**
 * The exit reason is the whole diagnostic value of [logRecentProcessExits]: it
 * is what separates a crash of ours from the system killing us, so a mapping
 * that silently mislabels one as the other would make the log confidently
 * wrong rather than merely unhelpful.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ProcessExitReasonsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun clearLog() {
        LauncherDebugLog.clearForTest()
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
        // The mapping tests below prove the names are right; this proves the
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
        // The platform's own account of the death rides along, and the
        // on-device log carries it in full — that is what the report is read
        // for. (It is withheld from the Crashlytics mirror; see LogValueTest
        // for the type rule that does it.)
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
        // the point. Ordering them last is what stops a failure in the former
        // discarding the latter — the records are already fetched by then, so
        // losing them would lose exactly the evidence this is read for. The
        // package name is forced to one that does not resolve, which is what a
        // failing lookup looks like from here.
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
    fun saysSoWhenThePlatformHasNoExitRecords() {
        // A fresh install, or a device that has pruned its records. The line
        // matters because its absence would otherwise be ambiguous with the
        // query having failed or never run.
        logRecentProcessExits(context)

        assertTrue(loggedLines().any { it.contains("processExits none") })
        assertFalse(loggedLines().any { it.contains("processExit reason=") })
    }

    @Test
    fun withholdsTheExitTimestampFromTheCrashlyticsMirror() {
        // The timestamp is a number, so the type rule would carry it off the
        // device on its own. It is not fixed vocabulary — it records when this
        // user's launcher died — so the call site marks it sensitive, and the
        // on-device log keeps it in full because that is where it is read.
        val mirrored = formatLogMessage(
            "processExit timestamp=%s",
            arrayOf<Any?>(sensitive(1_700_000_000_000L)),
            redactSensitive = true,
        )
        val onDevice = formatLogMessage(
            "processExit timestamp=%s",
            arrayOf<Any?>(sensitive(1_700_000_000_000L)),
            redactSensitive = false,
        )

        assertFalse(mirrored.contains("1700000000000"))
        assertTrue(onDevice.contains("1700000000000"))
    }

    @Test
    fun namesTheReasonsThatSeparateOurFailuresFromThePlatformKillingUs() {
        // Ours to fix.
        assertEquals("crash", exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("crashNative", exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("anr", exitReasonName(ApplicationExitInfo.REASON_ANR))
        // Not ours — the system reclaiming or replacing the process. These are
        // the ones no in-process signal can see, which is why this exists.
        assertEquals("lowMemory", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("packageUpdated", exitReasonName(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
        assertEquals(
            "packageStateChange",
            exitReasonName(ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE),
        )
        assertEquals("userRequested", exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test
    fun keepsTheNumberOfAReasonItDoesNotRecognize() {
        // A platform addition should degrade to something still diagnosable
        // rather than collapsing into an indistinguishable "unknown" — which
        // the platform already uses for a reason of its own.
        assertEquals("unrecognized(9999)", exitReasonName(9999))
        assertEquals("unknown", exitReasonName(ApplicationExitInfo.REASON_UNKNOWN))
    }

    @Test
    fun namesThePriorityAndroidAssignedTheProcess() {
        // A background process being reclaimed is routine — the launcher lives
        // there all day. Foreground importance means the system was not
        // treating it as idle, which is the distinction the record exists to
        // make. It is not proof an Activity was on screen.
        assertEquals(
            "foreground",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND),
        )
        assertEquals(
            "visible",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE),
        )
        assertEquals(
            "cached",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED),
        )
        assertEquals(
            "gone",
            processImportanceName(ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE),
        )
        assertEquals("unrecognized(7)", processImportanceName(7))
    }
}
