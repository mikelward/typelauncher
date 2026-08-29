package app.typelauncher

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
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
 *
 * Every line here is pinned ([LauncherDebugLog.pinnedEvent]), because each is
 * written once at startup and read hours later: in every report so far the ring
 * buffer had already evicted them by the time the user shared one, which left
 * this diagnostic answering nothing on exactly the reports it exists for. The
 * failures are pinned too, as a sanitized status line beside the throwable's own
 * ring entry — a section that has lost the record of a failed query reads as a
 * complete diagnostic that simply found nothing.
 */
internal fun logRecentProcessExits(context: Context) {
    val activityManager = context.getSystemService<ActivityManager>() ?: run {
        LauncherDebugLog.pinnedEvent("processExits unavailable reason=%s", safe("noActivityManager"))
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
        // And pin the fact of it, sanitized and without the stack trace the
        // line above carries. Otherwise, once the ring turns over, the Process
        // start section shows the later home-resolution line with no exit
        // records beside it — an incomplete startup diagnostic that reads as a
        // complete one (Codex on PR #689).
        LauncherDebugLog.pinnedEvent("processExits unavailable reason=%s", safe("queryFailed"))
        return
    }
    if (exits.isEmpty()) {
        LauncherDebugLog.pinnedEvent("processExits none")
    } else {
        logExitRecords(exits)
    }
    // Last, deliberately. The exit records are the thing this function exists
    // to capture, and they are already in hand by now — so anything that can
    // fail runs only after they are safely in the log, never ahead of them.
    logOwnPackageTimestamps(context)
}

/** See [logRecentProcessExits]; split out so a later failure cannot preempt it. */
private fun logExitRecords(exits: List<ApplicationExitInfo>) {
    // Oldest first, reversing the order the platform returns them in, so these
    // lines read chronologically like every other line in the log and end on
    // the exit that explains this start. Not only for reading: the pinned
    // section is truncated from its head when it overflows its budget, which
    // with newest-first would have discarded the most recent exits and labelled
    // them "older" (Codex on PR #689).
    exits.reversed().forEach { info ->
        LauncherDebugLog.pinnedEvent(
            "processExit reason=%s importance=%s status=%s timestamp=%s description=%s",
            safe(exitReasonName(info.reason)),
            safe(processImportanceName(info.importance)),
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

/**
 * Maps an [ApplicationExitInfo.importance] to a stable, readable name.
 *
 * Importance is the priority Android had assigned the process when it died, and
 * that is the half of an exit record that decides what the death meant. A
 * background process being reclaimed is routine and costs the user nothing —
 * the launcher lives in the background all day. Foreground importance means the
 * system was not treating the launcher as idle at that moment.
 *
 * It is **not** proof an Activity was on screen: a broadcast receiver handling
 * a package change reaches that importance for the duration of its callback
 * with nothing visible, and a launcher is sent those all day. Read it as "the
 * system counted this as user-aware work", not as "the user was looking" — the
 * on-screen case is the one that also drops the activity with no saved state,
 * but importance alone does not establish it. A process kept alive by a
 * foreground service is a separate value (`foregroundService`) rather than a
 * second sense of this one.
 *
 * Named rather than numeric for the same reason as [exitReasonName]: the raw
 * values are sparse constants (100, 125, 230, …) that mean nothing to a reader
 * without the SDK to hand, and a report is read wherever it lands.
 */
internal fun processImportanceName(importance: Int): String = when (importance) {
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> "foreground"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> "foregroundService"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_TOP_SLEEPING -> "topSleeping"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE -> "visible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> "perceptible"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE -> "service"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> "cached"
    ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE -> "gone"
    else -> "unrecognized($importance)"
}

/**
 * Records when this package was last updated, next to the exit records above.
 *
 * The two together are what test the leading explanation for the launcher being
 * absent from Home: while the installer is swapping the APK there is no home
 * activity to resolve to, and the home role still names a package the system
 * cannot currently reach — so it asks the user which launcher to use, without
 * anything having been revoked. That window leaves no trace in the launcher,
 * because the launcher is not running during it. What it does leave behind is a
 * previous-process exit whose timestamp sits alongside this package's own update
 * time; if the two line up, the window is confirmed rather than assumed, and if
 * they do not, the explanation is somewhere else.
 *
 * Both are numbers, which the type rule would carry to the Crashlytics mirror
 * on its own, and neither is fixed vocabulary: they say when *this* user
 * installed and last updated the launcher. Marked [sensitive] for the same
 * reason as the exit timestamp they are read against — the correlation is done
 * on the device, in the log the user reviews before sharing, so withholding
 * them from the mirror costs the diagnostic nothing.
 */
private fun logOwnPackageTimestamps(context: Context) {
    try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        LauncherDebugLog.pinnedEvent(
            "ownPackage lastUpdateTime=%s firstInstallTime=%s",
            sensitive(info.lastUpdateTime),
            sensitive(info.firstInstallTime),
        )
    } catch (exception: PackageManager.NameNotFoundException) {
        // Querying our own package cannot normally miss; if it somehow does,
        // the exit records above still stand on their own, so report and carry
        // on rather than losing them to it.
        LauncherDebugLog.failure(exception, "ownPackage query failed")
        LauncherDebugLog.pinnedEvent("ownPackage unavailable reason=%s", safe("notFound"))
    } catch (exception: RuntimeException) {
        // The lookup is a binder call, so it can also fail as a RuntimeException
        // — a dead system_server mid-restart being the realistic case, which is
        // exactly the sort of moment this diagnostic is read about. Caught for
        // the same reason as above: this is the optional half, and it must not
        // take the exit records down with it.
        LauncherDebugLog.failure(exception, "ownPackage query failed")
        LauncherDebugLog.pinnedEvent("ownPackage unavailable reason=%s", safe("queryFailed"))
    }
}
