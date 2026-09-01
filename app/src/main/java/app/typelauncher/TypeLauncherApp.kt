package app.typelauncher

import android.app.Application
import android.os.Build
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.LogcatSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The launcher's [Application]. Its job today is to install on-device debug-log
 * persistence early — before the first activity — so the log survives the
 * process ending (a crash *or* a silent kill) and can be shared in a bug report
 * or surfaced by the post-crash prompt on the next launch. Crashlytics
 * ([LauncherTelemetry]) still installs its own uncaught handler; the crash
 * handler here chains to it.
 *
 * It also asks the platform why the previous processes ended
 * ([logRecentProcessExits]), which is the only way to see the deaths that leave
 * no in-process trace — an ANR, a native crash, an out-of-memory reclaim, or
 * the installer stopping us for an update.
 */
class TypeLauncherApp : Application() {

    /**
     * On-device persistence of the debug log, so it survives a crash or a silent
     * kill and can be shared next launch (read by [BugReport]) or surfaced by the
     * post-crash prompt. Set in [onCreate]; null only before it runs.
     */
    internal var debugFileSink: DebugFileSink? = null
        private set

    /**
     * A process-lifetime scope for work that must finish even though the screen
     * that started it is gone. Sharing a bug report is the case that needs it:
     * the hand-off suspends (payload build, screenshot capture) and then opens
     * the share sheet, and opening the sheet *is* leaving the screen — on a
     * composition-bound scope the launcher would cancel its own share partway
     * through, so a report the user asked for silently never arrived and the
     * post-crash prompt stayed up.
     *
     * `SupervisorJob` so one failed job can't cancel the next; `Dispatchers.Default`
     * because the callers hop to IO / Main themselves for the parts that need it.
     */
    internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Skip under Robolectric: the file sink installs a process-global chained
        // UncaughtExceptionHandler, and the test harness builds a fresh Application
        // per test, so re-installing across the run would grow that chain
        // unboundedly (each link pinning a torn-down sandbox) and destabilize the
        // JVM. The sink is a production concern and is unit-tested directly with an
        // injected directory (the library's own suite), so nothing is lost by not wiring
        // it here in tests. Same spirit as LauncherTelemetry no-opping without a
        // Firebase config.
        if (isRobolectric) return
        // start() enqueues the prior run's rotation on a background worker and
        // installs the chained crash handler. Register the sink AFTER start() so
        // the rotation is ordered before any of this run's writes. All disk work is
        // off the first-frame path ("Fast loading").
        val fileSink = DebugFileSink(LauncherDebugLog, this)
        fileSink.start()
        // Carry prior runs written by the launcher's own former sink onto the
        // names this sink reads, so the swap does not orphan logs already on the
        // device. On its own thread, off the first-frame path: it lists the
        // cache directory and renames files.
        //
        // Started after the sink exists so it can ask for the crash state to be
        // re-derived when it finishes. Without that, a carry-over landing after
        // the view model's one startup recompute would publish a crashed legacy
        // run to nobody: renaming a file behind the sink notifies no listener,
        // and the only other recompute is the one after a share, so the card
        // could stay down until the process restarted (Codex, PR #708).
        carryOverLegacyDebugLogsInBackground(fileSink)
        // Three sinks, each declaring which side of the device boundary it is,
        // so the library decides what each is handed rather than every call
        // site rendering both forms by hand.
        LauncherDebugLog.addSink(LogcatSink(LAUNCHER_DEBUG_TAG), DebugLog.Destination.DEVICE)
        LauncherDebugLog.addSink(fileSink, DebugLog.Destination.DEVICE)
        LauncherDebugLog.addSink(CrashlyticsBreadcrumbSink, DebugLog.Destination.OFF_DEVICE)
        debugFileSink = fileSink
        logProcessExitReasons()
        applyTelemetryPreference()
    }

    /**
     * Runs [carryOverLegacyDebugLogs] on its own short-lived daemon thread.
     *
     * A bare thread rather than [appScope]: this must not wait on a dispatcher
     * that cold start is already contending for, it runs exactly once per
     * process, and it must never keep the process alive. Silent when there was
     * nothing to carry over, which is every start after the first.
     */
    private fun carryOverLegacyDebugLogsInBackground(fileSink: DebugFileSink) {
        val thread = Thread({
            // cacheDir touches the filesystem and can create the directory, so
            // it is resolved here rather than on the caller's thread.
            val outcome = runCatching { carryOverLegacyDebugLogs(cacheDir) }
                .onFailure { LauncherDebugLog.failure(it, "Legacy debug logs could not be carried over") }
                .getOrNull() ?: return@Thread
            if (!outcome.didSomething) return@Thread
            // Files appeared behind the sink, which notifies nobody. Ask for the
            // crash state to be re-derived so a carried-over crash raises its
            // card on this launch rather than the next one. Returns at once --
            // the listing happens on the sink's worker.
            if (outcome.carried > 0) fileSink.requestCrashRecompute()
            LauncherDebugLog.event(
                "debug log: carried over %s earlier run(s) from the former sink, %s failed, listed=%s",
                outcome.carried,
                outcome.failed,
                outcome.listed,
            )
        }, "typelauncher-log-carryover")
        thread.isDaemon = true
        runCatching { thread.start() }
            .onFailure { LauncherDebugLog.failure(it, "Legacy debug-log carry-over could not be started") }
    }

    /**
     * Asks the platform why the launcher's recent processes ended and writes the
     * answer into this run's log.
     *
     * Off the main thread because it is an `ActivityManager` IPC and `onCreate`
     * is the cold-start path ("Fast loading"). Ordering against the sink's own
     * worker does not matter: this run's log file is written from the buffer,
     * and the rotation that preserves the *previous* run is already enqueued
     * ahead of any write.
     */
    private fun logProcessExitReasons() {
        appScope.launch(Dispatchers.IO) {
            logRecentProcessExits(this@TypeLauncherApp)
            // Alongside them, and last: how this run found Home resolving,
            // beside the exits that explain how the previous one ended.
            HomeResolution.record(this@TypeLauncherApp, moment = "processStart")
        }
    }

    /**
     * Re-asserts the user's "Analytics" choice on both Firebase SDKs.
     * They persist the flag themselves, so an opt-out already holds from the
     * moment the process starts and this is belt-and-braces — which is why it
     * can afford to run off the main thread: reading `SharedPreferences` and
     * touching Firebase are both disk / IPC work, and `onCreate` is on the
     * cold-start path ("Fast loading"). It also re-arms [LauncherTelemetry]'s
     * own in-process gate, which starts permissive so a crash in this very
     * window is still reported for an opted-in install.
     *
     * That window is no longer a hole for someone who has *not* consented: both
     * SDKs default off in the manifest, so nothing collects before this runs.
     * It stays covered for someone who *has* — the runtime setters persist an
     * override, so an install that once tapped Allow starts collecting at
     * auto-initialization, without waiting for this coroutine.
     */
    private fun applyTelemetryPreference() {
        appScope.launch(Dispatchers.IO) {
            // Narrow catch, not runCatching: a blanket Throwable would swallow
            // CancellationException and break structured concurrency.
            // Through the same serialization point the Settings toggle uses,
            // reading the preference inside its lock: this call and a user
            // opt-out can otherwise interleave, and a slow startup "enable"
            // could resume mid-sequence and re-enable an SDK after the opt-out
            // had already finished.
            LauncherTelemetry.applyCollectionPreference(
                StoredTelemetryPreferences(DockSettingsStore(this@TypeLauncherApp)),
            )
        }
    }

    private val isRobolectric: Boolean
        get() = "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)
}
