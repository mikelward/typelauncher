package app.typelauncher

import android.app.Application
import android.os.Build
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
        // injected directory (DebugFileSinkTest), so nothing is lost by not wiring
        // it here in tests. Same spirit as LauncherTelemetry no-opping without a
        // Firebase config.
        if (isRobolectric) return
        // start() enqueues the prior run's rotation on a background worker and
        // installs the chained crash handler. Register the sink AFTER start() so
        // the rotation is ordered before any of this run's writes. All disk work is
        // off the first-frame path ("Fast loading").
        val fileSink = DebugFileSink(this)
        fileSink.start()
        LauncherDebugLog.addSink(fileSink)
        debugFileSink = fileSink
        applyTelemetryPreference()
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
