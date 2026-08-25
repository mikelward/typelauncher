package app.typelauncher

import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * cold start even before we re-assert it; [collectionState] additionally
 * short-circuits every call here, so an opted-out install stops *building*
 * breadcrumbs and keys rather than merely stopping their upload.
 *
 * The user's choice is applied in exactly one place — [applyCollectionPreference]
 * — as a single ordered transition that owns both SDKs' persisted flags and the
 * unsent-report deletion together. It replaced four mechanisms that each looked
 * right alone and did not compose: an in-process gate, the two flags, a lock
 * that re-read the preference, and a deletion triggered on the edge. Every one
 * of them was added to fix an interaction, and each addition produced the next
 * one — convergence on the latest value erased the deletion request, and the
 * gate could not tell an unread preference from an opt-out.
 */
/**
 * The three SDK side effects the opt-out transition performs, behind an
 * interface so the transition's *ordering and failure semantics* can be tested
 * without Firebase.
 *
 * That seam earns its place: every defect this subsystem has produced lived in
 * the ordering, not in the Firebase calls themselves, and none of it was
 * reachable from a JVM test while the calls were inlined. The Firebase-backed
 * implementation stays a thin pass-through with no logic of its own.
 */
internal interface TelemetrySdk {
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)
    fun setPerformanceCollectionEnabled(enabled: Boolean)
    fun deleteUnsentReports()
}

private object FirebaseTelemetrySdk : TelemetrySdk {
    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
    }

    override fun setPerformanceCollectionEnabled(enabled: Boolean) {
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
    }

    override fun deleteUnsentReports() {
        FirebaseCrashlytics.getInstance().deleteUnsentReports()
    }
}

internal object LauncherTelemetry {
    /** Swapped in tests; production always uses the Firebase-backed one. */
    @Volatile
    private var sdk: TelemetrySdk = FirebaseTelemetrySdk

    @Volatile
    private var sdkAvailableOverride: Boolean? = null

    private val firebaseAvailable: Boolean
        get() = sdkAvailableOverride ?: firebasePresent

    private val firebasePresent: Boolean by lazy {
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
     * What this process knows about the user's "Analytics" choice.
     *
     * Three states rather than a boolean, because "we have not read the
     * preference yet" and "the user said no" call for different behavior and a
     * gate cannot tell them apart:
     *
     * - [Unknown] until the stored preference has been read. Breadcrumbs and
     *   custom keys are dropped, because Crashlytics retains them locally even
     *   while uploads are off, so anything recorded now on an opted-out install
     *   would become eligible to send if collection were ever enabled in this
     *   process. Traces still run — see [startTrace].
     * - [Enabled] / [Disabled] once it is known.
     *
     * The old two-state gate had to pick one behavior for the startup window
     * and got one of them wrong whichever way it was set: closed dropped every
     * cold-start trace on a slow start, and open recorded breadcrumbs for
     * users who had opted out.
     */
    internal enum class CollectionState { Unknown, Enabled, Disabled }

    @Volatile
    private var collectionState: CollectionState = CollectionState.Unknown

    /** Crashlytics breadcrumbs, keys and non-fatals: only once known-enabled. */
    private val reporting: Boolean
        get() = firebaseAvailable && collectionState == CollectionState.Enabled

    /**
     * Counts opt-outs. A crash report Crashlytics has stored but not uploaded,
     * which an opt-out promised to discard, is owed whenever
     * [servicedOptOuts] lags this.
     *
     * A counter rather than a flag, because a flag cannot say *which* opt-out a
     * deletion was servicing. `deleteUnsentReports()` is a blocking IPC, and
     * the user can opt out again while it is in flight; clearing a flag on the
     * way out then erases the newer opt-out's debt as well as the old one's —
     * the same shape as the defect this rework exists to fix, one level down.
     * Servicing advances [servicedOptOuts] only to the generation actually
     * discharged, so an opt-out that arrived mid-delete keeps its own debt.
     *
     * Atomics rather than state under [collectionLock] because
     * [setCollectionGate] records the debt from the main thread and must not
     * block behind a transition that is mid-IPC.
     */
    private val optOuts = AtomicLong(0)
    private val servicedOptOuts = AtomicLong(0)

    private fun deletionOwed(): Boolean = servicedOptOuts.get() < optOuts.get()

    /** Serializes the transition below across the toggle and startup. */
    private val collectionLock = Any()

    /** One-shot latch so a broken SDK can't produce a warning per custom key. */
    private val customKeyFailureReported = AtomicBoolean(false)

    /**
     * Records the user's choice in memory, instantly, and nothing else.
     *
     * Lock-free on purpose: the caller is the Settings toggle on the main
     * thread, and it needs reporting to stop *before* it does anything else —
     * persisting the preference, updating the switch, or logging the change,
     * all of which can themselves produce telemetry. Taking [collectionLock]
     * here would block that behind an in-flight transition holding two IPCs.
     *
     * Turning off also records the deletion debt, so the promise survives even
     * if the user turns Analytics straight back on before the SDK half runs.
     */
    fun setCollectionGate(enabled: Boolean) {
        collectionState = if (enabled) CollectionState.Enabled else CollectionState.Disabled
        if (!enabled) optOuts.incrementAndGet()
    }

    /**
     * The one place the user's choice is applied to the SDKs — a single ordered
     * transition that owns the persisted flags *and* the report deletion.
     *
     * Two callers race: the Settings toggle and `TypeLauncherApp`'s startup
     * re-assert, which is not on the ViewModel and so cannot be serialized
     * there. Reading the preference inside the lock rather than taking a value
     * means whichever call runs last applies whatever is actually stored, so a
     * burst of taps converges on the last one instead of a stale value winning.
     *
     * The order is the point, and it differs by direction:
     *
     * - **Turning off**: stop the SDKs, *then* discard. Nothing new can be
     *   written after the discard.
     * - **Turning on**: discard anything owed, *then* start the SDKs. This is
     *   what makes a rapid off→on safe — reports from the off period are gone
     *   before upload is possible again, rather than the re-enable racing a
     *   deletion that convergence had already discarded as unnecessary.
     *
     * Blocks; both SDK calls are IPC. Call it off the main thread.
     */
    fun applyCollectionPreference(currentPreference: () -> Boolean?) = synchronized(collectionLock) {
        // A null reading means "don't know" — leave both SDKs' persisted flags
        // and this state exactly as they are. Guessing `true` here would take an
        // unreadable preference and use it to overwrite a stored opt-out with
        // an explicit opt-in, silently revoking a choice the user made.
        val enabled = currentPreference() ?: return@synchronized
        collectionState = if (enabled) CollectionState.Enabled else CollectionState.Disabled
        if (!enabled) optOuts.incrementAndGet()
        if (!firebaseAvailable) return@synchronized

        if (enabled) {
            // Stop before discharging, on this path too. A rapid off→on means
            // the opt-out never got a transition of its own, so the persisted
            // flags are still enabled from before it — and requesting the
            // delete while they are on leaves the owed report uploadable for
            // the length of that IPC. "Stop, then discard" is the rule on the
            // way out; it has to hold here as well, and only when a debt is
            // actually owed, so an ordinary opt-in costs no extra IPCs.
            if (deletionOwed()) applySdkFlags(enabled = false)
            // Re-enabling is *conditional* on the debt being discharged. If the
            // delete throws, the reports an earlier opt-out promised to discard
            // are still on disk, and turning upload back on would send them —
            // which a later retry cannot undo, because a report already sent
            // cannot be retracted. So collection stays off until the promise is
            // kept, and the next transition tries again.
            // Re-checked after the discharge, not just before it: the delete is
            // a blocking IPC and the user can opt out while it is in flight, so
            // a transition that set out to enable can find that decision already
            // superseded. Turning the persisted flags on now would leave them
            // enabled against a switch that reads off — and they survive the
            // process, so a crash before the queued opt-out transition runs
            // would start the next process collecting.
            if (dischargeOwedDeletion() && collectionState == CollectionState.Enabled) {
                val servicing = optOuts.get()
                applySdkFlags(enabled = true)
                // The check above cannot cover the write itself: an opt-out can
                // land between it and here, or midway through the two flags,
                // and those flags are persisted — they survive the process, so
                // a crash before the queued opt-out transition runs would start
                // the next process collecting. Compensating right here closes
                // that rather than leaving it to a transition a crash can beat.
                // Reversal is safe to do unconditionally on a stale enable: the
                // worst case is re-applying `false` over flags already `false`.
                // `deletionOwed()` is the invariant itself — never leave the
                // SDKs enabled with a discharge outstanding — where the two
                // clauses beside it are proxies for it. An off→on landing in
                // any of the gaps between the discharge, this snapshot and the
                // writes leaves the generations matching and the state enabled
                // while a newer debt stands, so the proxies pass and only the
                // invariant catches it.
                if (optOuts.get() != servicing ||
                    collectionState != CollectionState.Enabled ||
                    deletionOwed()
                ) {
                    applySdkFlags(enabled = false)
                    dischargeOwedDeletion()
                    LauncherDebugLog.event("analytics opt-in reversed: an opt-out landed mid-transition")
                }
            } else {
                // Not enabling is not the same as being off. On a rapid off→on
                // both queued transitions read the final `true`, so the opt-out
                // never got a transition of its own and the persisted flags are
                // still enabled from before it. Merely declining to write `true`
                // leaves them on and the owed report uploadable — so write
                // `false` and close the wrapper, and leave both that way until a
                // later transition discharges the debt.
                collectionState = CollectionState.Disabled
                applySdkFlags(enabled = false)
                LauncherDebugLog.warning(
                    "analytics held off: an opt-out is unserviced or superseded this opt-in",
                )
            }
        } else {
            applySdkFlags(enabled = false)
            dischargeOwedDeletion()
        }
    }

    /**
     * Sets both SDKs' persisted flags, each in its own `try`: sharing one means
     * a throw from the first silently leaves the second as it was and reports
     * the opt-out as successful.
     *
     * A failure is logged rather than swallowed, and it cannot leave this
     * process feeding a still-enabled SDK: [collectionState] is already
     * `Disabled` by the time this runs, which stops breadcrumbs, keys, and —
     * since the tri-state distinguishes an opt-out from an unread preference —
     * traces as well. That last part is what a failed *Performance* opt-out
     * needed: its flag may be stuck on, but nothing here will start another
     * trace to feed it.
     */
    private fun applySdkFlags(enabled: Boolean) {
        try {
            sdk.setCrashlyticsCollectionEnabled(enabled)
        } catch (error: RuntimeException) {
            LauncherDebugLog.failure(error, "crash reporting opt-in/out not applied")
        }
        try {
            sdk.setPerformanceCollectionEnabled(enabled)
        } catch (error: RuntimeException) {
            LauncherDebugLog.failure(error, "performance opt-in/out not applied")
        }
    }

    /**
     * Drops crash reports Crashlytics has stored but not uploaded, if an
     * opt-out owed it, and clears the debt only once that has actually happened.
     *
     * Disabling collection stops the *upload*, not the recording: a crash that
     * happens while Analytics is off is still written locally and becomes
     * eligible to send the moment collection is re-enabled. `PRIVACY.md`
     * promises nothing is collected or sent while the switch is off, so those
     * reports are deleted — when the user turns the switch off, and again via
     * the startup re-assert at the next launch after a crash during the off
     * period, which is when that crash actually lands on disk.
     *
     * Leaving the debt set when the delete throws is what stops a failure being
     * silently forgotten: the next transition tries again.
     *
     * **There is nothing to await.** `deleteUnsentReports()` returns `void` in
     * Crashlytics 20.1.0 — `checkForUnsentReports()` is the only member of that
     * trio returning a `Task` — so clearing the debt here means *the call was
     * made and did not throw*, not that the reports are gone. Whatever the SDK
     * does internally afterwards, it exposes no completion signal, so a
     * request-before-re-enable ordering is the strongest guarantee available.
     * Reviewers have twice read this as an ignored `Task`; it isn't one. Worth
     * revisiting only if a `Task`-returning overload ever appears.
     */
    private fun dischargeOwedDeletion(): Boolean {
        val servicing = optOuts.get()
        if (servicedOptOuts.get() >= servicing) return true
        return try {
            sdk.deleteUnsentReports()
            // Advance only to the generation this call actually serviced. An
            // opt-out that arrived while the delete was in flight bumped
            // [optOuts] past it and keeps its own debt for the next transition.
            servicedOptOuts.updateAndGet { maxOf(it, servicing) }
            // ...and report success only if nothing is *still* owed. Returning
            // `true` for "the delete I started did not throw" was wrong: an
            // off→on pair landing while the call was blocked leaves a newer
            // debt that this delete never serviced, and the caller would take
            // the `true` as licence to re-enable upload over it. The caller's
            // own generation check cannot catch that — by then the newer
            // generation is the current one, so its before/after comparison
            // comes out equal.
            !deletionOwed()
        } catch (error: RuntimeException) {
            LauncherDebugLog.failure(error, "pending crash reports not discarded on opt-out")
            false
        }
    }

    /**
     * Whether a trace may run: Firebase present, and the user has not opted out.
     * Read at every point a trace touches the SDK, not only at creation — a
     * trace started before an opt-out is still live when it lands.
     */
    internal val tracingAllowed: Boolean
        get() = firebaseAvailable && collectionState != CollectionState.Disabled

    /**
     * The opt-out generation a trace handle pins itself to, so it can tell an
     * uninterrupted run from one that spanned an opt-out. See [FirebaseTrace].
     */
    internal val optOutGeneration: Long
        get() = optOuts.get()

    /** Test-only: returns this process's view of the user's choice. */
    internal fun collectionStateForTest(): CollectionState = collectionState

    /** Test-only: whether an opt-out's report deletion is still outstanding. */
    internal fun isDeletionOwedForTest(): Boolean = deletionOwed()

    /** Test-only: puts the wrapper back to its pre-read state. */
    internal fun resetForTest() {
        collectionState = CollectionState.Unknown
        optOuts.set(0)
        servicedOptOuts.set(0)
        sdk = FirebaseTelemetrySdk
        sdkAvailableOverride = null
    }

    /** Test-only: runs the transition against [replacement] instead of Firebase. */
    internal fun useSdkForTest(replacement: TelemetrySdk) {
        sdk = replacement
        sdkAvailableOverride = true
    }

    /**
     * Attaches a custom key to every subsequent crash report. Keys are the
     * structured half of a report — the settings and counts that a stack trace
     * alone can't tell us — and are what makes a crash triageable without the
     * user ever sharing a bug report. Cheap (an in-memory map write in the SDK)
     * but still off the first-frame path by convention; see [setCustomKeys].
     *
     * **Privacy**: values must stay inside the same floor the breadcrumbs keep
     * (`PRIVACY.md`) — settings, enum choices, and counts only, never a package
     * name, app or contact label, search query, or widget content. The keys
     * shipped are built in one place ([launcherTelemetryKeys]) so that floor is
     * reviewable and testable rather than spread across call sites.
     */
    fun setCustomKey(key: String, value: String) {
        // Dropped, not buffered, while the gate is closed. Buffering was tried
        // so the foreground key written before the startup preference read
        // could be replayed — but the buffer could not tell "preference not
        // loaded yet" from "user has opted out", so state recorded during an
        // opt-out was replayed on opt-in, which is exactly what `PRIVACY.md`
        // promises does not happen. A key missing until the next state change
        // is a diagnostic gap; replaying one is a broken promise.
        if (!reporting) return
        try {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        } catch (error: RuntimeException) {
            // Latched: a broken SDK would otherwise log once per key per
            // publish. The key name is ours and the value is already inside the
            // privacy floor, but neither is worth repeating — the failure is
            // what matters, and losing it entirely would let the crash-context
            // feature disappear in production with nothing to show for it.
            if (customKeyFailureReported.compareAndSet(false, true)) {
                LauncherDebugLog.failure(error, "crash-report custom keys not being recorded")
            }
        }
    }

    /** Bulk [setCustomKey], for the snapshot published after a state change. */
    fun setCustomKeys(keys: Map<String, String>) {
        if (!reporting) return
        keys.forEach { (key, value) -> setCustomKey(key, value) }
    }

    fun startTrace(name: String): TraceHandle {
        // Gated on a *known* opt-out, not merely on "not known to be enabled".
        // A trace handed a `NoopTrace` can never be revived, so refusing while
        // the preference is still unread would silently drop the cold-start,
        // initial-load and icon-restore traces on exactly the slow starts they
        // exist to measure. Refusing once the user has actually opted out is
        // different, and is what makes the opt-out hold even if Performance's
        // own persisted flag failed to take.
        // Read *before* the gate check and before the trace is started. Both
        // of those take time — `start()` is a real call — and an opt-out
        // landing in between would otherwise be captured as this handle's own
        // generation, so a later opt-in would find them equal and revive a
        // trace that spans the opt-out. Capturing early can only retire the
        // handle more eagerly, never less, which is the safe direction to err.
        val startedAtGeneration = optOutGeneration
        if (!tracingAllowed) return NoopTrace
        return try {
            FirebaseTrace(
                trace = FirebasePerformance.getInstance().newTrace(name).also { it.start() },
                startedAtGeneration = startedAtGeneration,
            )
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

/**
 * Forwards to a live Performance trace, and stops forwarding the moment the
 * user opts out.
 *
 * The check is repeated at each forwarding point rather than only at creation,
 * because a trace outlives the tap that ends collection: a cold-start, agenda
 * or icon-load trace begun a moment earlier is still open when the switch
 * flips, and would otherwise go on feeding attributes and metrics to the SDK
 * and report its duration on `stop()`.
 *
 * Declining to call `stop()` is what actually withholds it — Performance
 * reports a trace when it stops, so a trace never stopped is never reported.
 * That makes a registry of live handles unnecessary: each one retires itself by
 * consulting the same state everything else here consults, with nothing to keep
 * in sync and no new mechanism in a subsystem that has had enough of them. The
 * abandoned `Trace` is collected with its handle.
 */
private class FirebaseTrace(
    private val trace: Trace,
    private val startedAtGeneration: Long,
) : TraceHandle {
    /**
     * A trace may report only if collection is allowed *and* no opt-out has
     * happened since it started.
     *
     * The second half is what an opt-out-and-back-in needs. A trace open across
     * that cycle would otherwise find collection allowed again at `stop()` and
     * report a duration spanning the period the user had switched off — and the
     * metrics accumulated during it. Pinning the handle to the generation it
     * started on retires it permanently instead: a later opt-in cannot revive a
     * measurement that straddles an opt-out.
     */
    private fun mayReport(): Boolean =
        LauncherTelemetry.tracingAllowed &&
            LauncherTelemetry.optOutGeneration == startedAtGeneration

    override fun stop() {
        // Not stopping is what withholds it: Performance reports a trace when
        // it stops, so one never stopped is never reported.
        if (!mayReport()) return
        try {
            trace.stop()
        } catch (_: RuntimeException) {
        }
    }

    override fun setAttribute(key: String, value: String) {
        if (!mayReport()) return
        try {
            trace.putAttribute(key, value)
        } catch (_: RuntimeException) {
        }
    }

    override fun incrementMetric(name: String, value: Long) {
        if (!mayReport()) return
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
