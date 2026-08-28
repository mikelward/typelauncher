package app.typelauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import androidx.core.content.getSystemService

/**
 * How many prior process exits to read. The launcher is a long-lived process
 * that the system restarts routinely, so a handful is enough to cover "what
 * happened around the time the user noticed" without turning startup into a
 * log dump.
 */
private const val MAX_EXIT_RECORDS = 5

/**
 * Records why the launcher's recent processes ended.
 *
 * The debug log already knows when a run ended in an *uncaught exception* —
 * [DebugFileSink]'s crash marker is written from the handler itself. What it
 * cannot see is every other way a process dies: an ANR, a native crash, an
 * out-of-memory reclaim, or the installer stopping us to swap the APK. Those
 * leave no in-process trace at all, so from the next run's point of view they
 * are indistinguishable from each other and from a clean exit — the log simply
 * restarts with no explanation.
 *
 * The platform keeps that explanation, so ask it rather than guessing. This is
 * what separates "the launcher crashed" from "the system killed the launcher",
 * which is the distinction between a bug of ours and one of the platform's, and
 * the one a report currently cannot make.
 *
 * The exit reason and the process importance are fixed vocabulary — every user
 * on the same failure produces the same values — so they are marked [safe] and
 * reach the Crashlytics mirror. Two things deliberately do not. The system's
 * free-text [ApplicationExitInfo.description] is composed by the platform and
 * can name another package (the installer that stopped us, a dependency that
 * died), so it is passed as a plain [String] and the default-withhold rule in
 * [LogValue] keeps it on device. The exit *timestamp* is a number, which that
 * rule would otherwise let through, but it is not fixed vocabulary either — it
 * varies per user and records when their launcher died — so it is marked
 * [sensitive]. Both stay in full in the on-device log, where the user reviews
 * them before sharing, which is where their diagnostic value lives anyway.
 */
internal fun logRecentProcessExits(context: Context) {
    val activityManager = context.getSystemService<ActivityManager>() ?: run {
        LauncherDebugLog.event("processExits unavailable reason=%s", safe("noActivityManager"))
        return
    }
    val exits = try {
        // pid 0 means "any process of this package" — the launcher runs one, but
        // asking by pid would miss exactly the abrupt deaths this is here for.
        activityManager.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXIT_RECORDS)
    } catch (exception: RuntimeException) {
        // A denial or a dead system_server leaves us no worse off than before
        // this existed: the rest of the log is unaffected, so report and return
        // rather than letting the failure escape into cold start.
        LauncherDebugLog.failure(exception, "processExits query failed")
        return
    }
    if (exits.isEmpty()) {
        LauncherDebugLog.event("processExits none")
        return
    }
    // Newest first, which is how the platform returns them and the order a
    // reader wants: the most recent exit is the one that explains this start.
    exits.forEach { info ->
        LauncherDebugLog.event(
            "processExit reason=%s importance=%s status=%s timestamp=%s description=%s",
            safe(exitReasonName(info.reason)),
            safe(info.importance),
            safe(info.status),
            // The exit time is a number, which the type rule would let through
            // to the mirror on its own — but it is not fixed vocabulary: it
            // varies with whoever is holding the phone and records when their
            // launcher died. Withheld from the mirror and kept in full on
            // device, where lining it up against the package's update time is
            // the whole point of having it.
            sensitive(info.timestamp),
            info.description,
        )
    }
}

/**
 * Maps an [ApplicationExitInfo] reason to a stable, readable name.
 *
 * Named rather than numeric because the number is the thing a reader has to go
 * look up, and a bug report is read by whoever it reaches — not only by someone
 * with the SDK constants to hand. An unrecognized reason keeps its number so a
 * future platform addition degrades to something still diagnosable instead of
 * collapsing into "unknown".
 */
internal fun exitReasonName(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "anr"
    ApplicationExitInfo.REASON_CRASH -> "crash"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashNative"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependencyDied"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessiveResourceUsage"
    ApplicationExitInfo.REASON_EXIT_SELF -> "exitSelf"
    ApplicationExitInfo.REASON_FREEZER -> "freezer"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initializationFailure"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "lowMemory"
    ApplicationExitInfo.REASON_OTHER -> "other"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "packageStateChange"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "packageUpdated"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permissionChange"
    ApplicationExitInfo.REASON_SIGNALED -> "signaled"
    ApplicationExitInfo.REASON_UNKNOWN -> "unknown"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "userRequested"
    ApplicationExitInfo.REASON_USER_STOPPED -> "userStopped"
    else -> "unrecognized($reason)"
}
