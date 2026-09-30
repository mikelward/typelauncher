package app.typelauncher

import android.content.Context
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.ProcessExits

/**
 * Records why the launcher's recent processes ended, through androidlog's shared [ProcessExits]:
 * the package's install and update times, then the last few exits oldest first, as pinned lines.
 *
 * The debug log already knows when a run ended in an *uncaught exception*: the file sink's crash
 * marker is written from the handler itself. What it cannot see is every other way a process
 * dies: an ANR, a native crash, an out-of-memory reclaim, or the installer stopping us to swap the
 * APK. Those leave no in-process trace, so the next run's log simply restarts with no explanation.
 * The platform keeps that explanation, and asking it is what separates "the launcher crashed" from
 * "the system killed the launcher".
 *
 * Pinned, because each line is written once at startup and read hours later: in every report
 * before pinning, the ring had already evicted them by the time the user shared one. The exit's
 * time and the package's update time are logged together because an exit whose time lines up
 * with the update is the installer swapping the APK rather than a bug. Both reach the Crashlytics
 * mirror, since a mirror missing either half can't make that call.
 *
 * The platform's free-text description is included (`includeDescription`), cut to one bounded
 * line. It is passed as an ordinary argument, so the floor keeps it on the device, where the user
 * reviews it before sharing. It can name another package, the installer that stopped us or a
 * dependency that died.
 */
internal fun logRecentProcessExits(context: Context, log: DebugLog = LauncherDebugLog) {
    ProcessExits.logRecent(context, log, includeDescription = true)
}
