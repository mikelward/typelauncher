package app.typelauncher

import com.mikelward.androidlog.DebugLog

/**
 * The launcher's off-device channel: every recorded line as a Crashlytics
 * breadcrumb, and a non-fatal for the failures worth one.
 *
 * Registered for [DebugLog.Destination.OFF_DEVICE], so the library hands it the
 * *reduced* rendering — untagged arguments already replaced by the placeholder,
 * and a throwable already stripped to its types and frames by
 * `offDeviceThrowable`. Nothing here has to remember to reduce anything, which
 * is the whole reason this replaced the fan-out `LauncherDebugLog` used to
 * write by hand at four call sites: there, the on-device and off-device
 * renderings were two expressions that had to be kept in step, and a call site
 * added later was safe only if someone remembered.
 */
internal object CrashlyticsBreadcrumbSink : DebugLog.Sink {

    override fun log(line: String) {
        LauncherTelemetry.log(line)
    }

    override fun log(line: String, level: Char, throwable: Throwable?) {
        LauncherTelemetry.log(line)
        // A non-fatal for `W` and not for `E`, which reads backwards until you
        // see why: `E` with a throwable is only ever the uncaught handler
        // (`LauncherDebugLog.recordUncaught`), and Crashlytics' own chained
        // handler already reports that same throwable as a *fatal*. Reporting
        // it here too would count one crash twice, as a fatal and a non-fatal
        // (Codex on PR #592). `W` is `failure`, which has no other reporter.
        if (throwable != null && level == 'W') {
            LauncherTelemetry.recordException(throwable)
        }
    }
}
