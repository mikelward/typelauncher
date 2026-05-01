package app.typelauncher

import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Thin wrapper over Firebase Crashlytics + Performance Monitoring that no-ops
 * when no `FirebaseApp` is initialized in the current process. The Firebase
 * SDKs auto-initialize via a manifest-merged `ContentProvider` only when
 * `app/google-services.json` is present at build time (see
 * `app/build.gradle.kts`); forks and Robolectric tests therefore skip
 * initialization, and every entry point here checks `firebaseAvailable`
 * before touching Firebase classes.
 */
internal object LauncherTelemetry {
    private val firebaseAvailable: Boolean by lazy {
        try {
            FirebaseApp.getInstance()
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: NoClassDefFoundError) {
            false
        }
    }

    fun startTrace(name: String): TraceHandle {
        if (!firebaseAvailable) return NoopTrace
        return try {
            FirebaseTrace(FirebasePerformance.getInstance().newTrace(name).also { it.start() })
        } catch (_: RuntimeException) {
            NoopTrace
        }
    }

    fun log(message: String) {
        if (!firebaseAvailable) return
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (_: RuntimeException) {
            // Crashlytics swallows most failures internally; treat anything
            // that escapes as best-effort and drop it so the app never crashes
            // because telemetry crashed.
        }
    }

    fun recordException(throwable: Throwable) {
        if (!firebaseAvailable) return
        try {
            FirebaseCrashlytics.getInstance().recordException(throwable)
        } catch (_: RuntimeException) {
        }
    }
}

internal interface TraceHandle {
    fun stop()
    fun setAttribute(key: String, value: String)
    fun incrementMetric(name: String, value: Long = 1L)
}

private class FirebaseTrace(private val trace: Trace) : TraceHandle {
    override fun stop() {
        try {
            trace.stop()
        } catch (_: RuntimeException) {
        }
    }

    override fun setAttribute(key: String, value: String) {
        try {
            trace.putAttribute(key, value)
        } catch (_: RuntimeException) {
        }
    }

    override fun incrementMetric(name: String, value: Long) {
        try {
            trace.incrementMetric(name, value)
        } catch (_: RuntimeException) {
        }
    }
}

private object NoopTrace : TraceHandle {
    override fun stop() = Unit
    override fun setAttribute(key: String, value: String) = Unit
    override fun incrementMetric(name: String, value: Long) = Unit
}

internal inline fun <T> traceBlock(name: String, block: (TraceHandle) -> T): T {
    val trace = LauncherTelemetry.startTrace(name)
    return try {
        block(trace)
    } finally {
        trace.stop()
    }
}
