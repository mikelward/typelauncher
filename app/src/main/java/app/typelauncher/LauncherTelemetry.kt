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
 *
 * Reporting is also gated on the user's Settings toggle ([applyCollectionPreference],
 * persisted as `DockSettingsStore.isTelemetryEnabled`). The Firebase SDKs
 * persist their own enabled flag across launches, so the setting survives a
 * cold start even before we re-assert it; [collectionEnabled] additionally
 * short-circuits every call here, so an opted-out install stops *building*
 * breadcrumbs and keys rather than merely stopping their upload.
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

    /**
     * Mirrors the user's Settings toggle. **Starts closed**, and opens only
     * once [applyCollectionPreference] has read the stored preference.
     *
     * Defaulting open would have been friendlier to the common case — an
     * opted-in user loses the first few milliseconds of breadcrumbs this way —
     * but it is wrong for the case that matters. Crashlytics keeps breadcrumbs
     * and custom keys locally even while uploads are disabled, so anything
     * recorded in that startup window on an opted-out install becomes eligible
     * to send if collection is ever turned back on in the same process. Closed
     * until proven otherwise is the only default an opt-out can honestly have.
     */
    @Volatile
    private var collectionEnabled: Boolean = false

    private val reporting: Boolean
        get() = firebaseAvailable && collectionEnabled

    /** Serializes [applyCollectionPreference] across the toggle and startup. */
    private val collectionLock = Any()


    /**
     * Opens or closes this wrapper's own gate, and nothing else.
     *
     * In-memory and instant, so the caller can shut reporting off *before* it
     * does anything else — persisting the preference, updating the switch, or
     * logging that it changed. Deferring the whole opt-out to a coroutine left
     * a window in which the UI already said "off" while a breadcrumb, a custom
     * key, or a crash could still be recorded and uploaded.
     */
    fun setCollectionGate(enabled: Boolean) {
        collectionEnabled = enabled
    }

    /**
     * Applies the user's "Analytics" choice to both SDKs and to this wrapper,
     * reading it through [currentPreference] inside the lock.
     *
     * Serialized, and it reads rather than takes a value, because two callers
     * race: the toggle and `TypeLauncherApp`'s startup re-assert, which is not
     * on the ViewModel and so cannot be covered by a lock there. Without this,
     * a slow startup "enable" could interleave with a user's opt-out — pausing
     * between the two SDK setters and resuming to re-enable one of them after
     * the opt-out had finished. Reading inside the lock also means whichever
     * call runs last applies whatever is actually stored, so the orderings
     * converge instead of a stale value winning.
     *
     * Blocks; Crashlytics and Performance are both IPC. Call it off the main
     * thread.
     */
    fun applyCollectionPreference(currentPreference: () -> Boolean?) = synchronized(collectionLock) {
        // A null reading means "don't know" — leave both SDKs' persisted flags
        // and this gate exactly as they are. Guessing `true` here would take an
        // unreadable preference and use it to overwrite a stored opt-out with
        // an explicit opt-in, silently revoking a choice the user made.
        val enabled = currentPreference() ?: return@synchronized
        collectionEnabled = enabled
        if (!firebaseAvailable) return@synchronized
        // Each SDK independently: sharing one try block means a throw from the
        // first silently leaves the second enabled, and reports an opt-out as
        // successful. A failure here is logged rather than swallowed — and
        // `collectionEnabled` above has already stopped this process feeding
        // either SDK, so a stuck flag can't be handed new data even if the
        // opt-out didn't take.
        try {
            FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
        } catch (error: RuntimeException) {
            LauncherDebugLog.warning("crash reporting opt-in/out not applied", error)
        }
        try {
            FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
        } catch (error: RuntimeException) {
            LauncherDebugLog.warning("performance opt-in/out not applied", error)
        }
        if (!enabled) discardUnsentReports()
    }

    /**
     * Drops crash reports Crashlytics has stored but not uploaded.
     *
     * Disabling collection stops the *upload*, not the recording: a crash that
     * happens while Analytics is off is still written locally and becomes
     * eligible to send the moment collection is re-enabled. `PRIVACY.md`
     * promises nothing is collected or sent while the switch is off, so those
     * reports are deleted — both when the user turns the switch off and, via
     * `TypeLauncherApp`'s startup re-assert, at the next launch after a crash
     * during the off period (which is when that crash actually lands on disk).
     */
    private fun discardUnsentReports() {
        try {
            FirebaseCrashlytics.getInstance().deleteUnsentReports()
        } catch (error: RuntimeException) {
            LauncherDebugLog.warning("pending crash reports not discarded on opt-out", error)
        }
    }

    fun startTrace(name: String): TraceHandle {
        // Deliberately *not* gated on [collectionEnabled]. Performance
        // Monitoring honors its own persisted `isPerformanceCollectionEnabled`,
        // so the in-process gate buys nothing here — and it costs real data: a
        // trace handed a `NoopTrace` can never be revived, so gating this would
        // silently drop the cold-start, initial-load and icon-restore traces on
        // every start where the preference read hadn't finished yet. Those are
        // exactly the slow starts the traces exist to measure.
        if (!firebaseAvailable) return NoopTrace
        return try {
            FirebaseTrace(FirebasePerformance.getInstance().newTrace(name).also { it.start() })
        } catch (_: RuntimeException) {
            NoopTrace
        }
    }

    fun log(message: String) {
        if (!reporting) return
        try {
            FirebaseCrashlytics.getInstance().log(message)
        } catch (_: RuntimeException) {
            // Crashlytics swallows most failures internally; treat anything
            // that escapes as best-effort and drop it so the app never crashes
            // because telemetry crashed.
        }
    }

    fun recordException(throwable: Throwable) {
        if (!reporting) return
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

internal inline fun <T> androidTrace(label: String, block: () -> T): T {
    android.os.Trace.beginSection(label)
    return try {
        block()
    } finally {
        android.os.Trace.endSection()
    }
}
