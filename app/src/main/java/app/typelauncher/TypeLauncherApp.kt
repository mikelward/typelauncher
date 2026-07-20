package app.typelauncher

import android.app.Application
import android.os.Build

/**
 * The launcher's [Application]. Its job today is to install on-device debug-log
 * persistence early — before the first activity — so the log survives the
 * process ending (a crash *or* a silent kill) and can be shared in a bug report
 * or surfaced by the post-crash banner on the next launch. Crashlytics
 * ([LauncherTelemetry]) still installs its own uncaught handler; the crash
 * handler here chains to it.
 */
class TypeLauncherApp : Application() {

    /**
     * On-device persistence of the debug log, so it survives a crash or a silent
     * kill and can be shared next launch (read by [BugReport]) or surfaced by the
     * post-crash banner. Set in [onCreate]; null only before it runs.
     */
    internal var debugFileSink: DebugFileSink? = null
        private set

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
    }

    private val isRobolectric: Boolean
        get() = "robolectric".equals(Build.FINGERPRINT, ignoreCase = true)
}
