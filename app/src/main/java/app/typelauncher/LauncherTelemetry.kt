package app.typelauncher

import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
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
/**
 * The stored Analytics choice, plus the durable record of a discard an opt-out
 * promised and has not yet made good on.
 *
 * An interface rather than the `() -> Boolean?` this replaced, because the
 * transition needs to *write* as well as read: the debt has to survive the
 * process, so clearing it is part of servicing it. Reading happens inside the
 * transition's lock so a burst of taps converges on what is actually stored.
 */
/**
 * Whether collection waits for an explicit "yes".
 *
 * **This is a trial**, and this constant is its name — one place to look rather
 * than a scattering of conditionals. While it holds, an unanswered question
 * behaves as a refusal: nothing is uploaded until the user taps Allow, and that
 * applies to an install upgrading from a build with the Analytics switch too,
 * since collection needs the preference *and* an answer.
 *
 * **Reverting the trial takes three edits, not this one.** Flipping this alone
 * leaves `DockSettingsStore.isTelemetryEnabled` defaulting to `false` and both
 * `firebase_*_collection_enabled` manifest entries `false` — which is not the
 * old default-on behavior but the new behavior with the card hidden, so nobody
 * would ever be asked and nothing would ever be sent. `TODO.md` under
 * "Decisions needing review" lists all three; follow it rather than this
 * constant's name.
 */
internal const val TELEMETRY_REQUIRES_CONSENT = true

internal interface TelemetryPreferences {
    /** The user's choice, or `null` if it could not be read at all. */
    fun isEnabled(): Boolean?

    /** Whether an opt-out's discard is still outstanding, across processes. */
    fun isDiscardOwed(): Boolean

    /**
     * Stores the opt-out *and* its owed discard in one durable write, returning
     * whether it reached storage. Two writes could be interrupted between, and
     * the resulting "enabled, owed" is serviced by discarding the reports and
     * then resuming collection — the promise kept, the choice lost.
     */
    fun recordOptOut(): Boolean

    /**
     * Records or clears that outstanding discard, returning whether the write
     * reached storage. A `false` means the promise is held in memory only and
     * will not survive this process.
     */
    fun setDiscardOwed(owed: Boolean): Boolean
}

internal interface TelemetrySdk {
    fun setCrashlyticsCollectionEnabled(enabled: Boolean)
    fun setPerformanceCollectionEnabled(enabled: Boolean)
    fun setAnalyticsCollectionEnabled(enabled: Boolean)
    fun deleteUnsentReports()
}

private object FirebaseTelemetrySdk : TelemetrySdk {
    override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = enabled
    }

    override fun setPerformanceCollectionEnabled(enabled: Boolean) {
        FirebasePerformance.getInstance().isPerformanceCollectionEnabled = enabled
    }

    override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
        // The app context via FirebaseApp rather than a field: this is an
        // `object` with no context of its own, and Firebase is already
        // initialized by the time any of these run (its own ContentProvider
        // does it before Application.onCreate).
        FirebaseAnalytics.getInstance(FirebaseApp.getInstance().applicationContext)
            .setAnalyticsCollectionEnabled(enabled)
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

    /**
     * In-process generations order the opt-outs *within* a run; [durableDebt]
     * is what carries one across a process death. Either can owe a discard,
     * and both have to be clear before collection may resume.
     */
    @Volatile
    private var durableDebt: Boolean = false

    private fun deletionOwed(): Boolean = servicedOptOuts.get() < optOuts.get() || durableDebt

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
     *
     * That record is written *here*, durably, and not left to the transition:
     * on a rapid off→on the stored preference ends up back at `true`, so a
     * process death before the transition ran would leave the next launch
     * reading "enabled, nothing owed" with the off period's reports still on
     * disk. The promise has to be on disk before the asynchronous gap, which
     * is the one thing only this call — the opt-out event itself — can do.
     *
     * It is the single blocking write this class makes from the main thread,
     * and it is deliberate. The caller is a Settings tap, not a cold start, a
     * scroll, or a transition; one boolean into an already-loaded
     * `SharedPreferences` is the cost, and an `apply()` still in flight when
     * the process dies loses exactly the case the record exists for.
     */
    fun setCollectionGate(enabled: Boolean, preferences: TelemetryPreferences) {
        collectionState = if (enabled) CollectionState.Enabled else CollectionState.Disabled
        if (!enabled) {
            optOuts.incrementAndGet()
            durableDebt = true
            try {
                if (!preferences.recordOptOut()) {
                    LauncherDebugLog.warning("opt-out not persisted: the write did not land")
                }
            } catch (error: RuntimeException) {
                // In-memory still holds within this process; what is lost is
                // only the ability to carry the choice across a restart.
                LauncherDebugLog.failure(error, "could not persist the opt-out and its owed discard")
            }
        }
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
    fun applyCollectionPreference(preferences: TelemetryPreferences) = synchronized(collectionLock) {
        // A null reading means "don't know" — leave both SDKs' persisted flags
        // and this state exactly as they are. Guessing `true` here would take an
        // unreadable preference and use it to overwrite a stored opt-out with
        // an explicit opt-in, silently revoking a choice the user made.
        val enabled = preferences.isEnabled()
        if (enabled == null) {
            // Not knowing the *choice* is not the same as not knowing whether a
            // promise is outstanding, and the debt is the half that has to be
            // kept either way. Returning here without looking left an inherited
            // discard unserviced while the SDKs' own persisted flags stayed
            // enabled from before — collecting, against a promise to delete.
            // So service it, and stop first, exactly as the opt-out path does.
            // Nothing here decides the choice: the flags are only ever written
            // *off*, which is the direction an unreadable preference may safely
            // take.
            if (firebaseAvailable && (readDiscardOwed(preferences) || deletionOwed())) {
                // Crashlytics specifically, here as everywhere: a debt
                // cleared while Crashlytics is still collecting records a
                // promise as kept that was not, and the other two SDKs do not
                // bear on it.
                if (applySdkFlags(enabled = false).crashlytics) dischargeOwedDeletion(preferences)
            }
            return@synchronized
        }
        collectionState = if (enabled) CollectionState.Enabled else CollectionState.Disabled
        if (!enabled) optOuts.incrementAndGet()
        // Pick up a discard this process never promised — one recorded by a
        // previous run that died before it could be serviced. This is the whole
        // point of persisting it: without this read, the stored preference
        // reading `true` is all the next launch would see.
        if (readDiscardOwed(preferences) || !enabled) recordDiscardOwed(preferences)
        if (!firebaseAvailable) return@synchronized

        if (enabled) {
            // Stop before discharging, on this path too. A rapid off→on means
            // the opt-out never got a transition of its own, so the persisted
            // flags are still enabled from before it — and requesting the
            // delete while they are on leaves the owed report uploadable for
            // the length of that IPC. "Stop, then discard" is the rule on the
            // way out; it has to hold here as well, and only when a debt is
            // actually owed, so an ordinary opt-in costs no extra IPCs.
            // Captured, because the ordering rule below rests on it: if the
            // stop did not take, discharging now deletes with collection
            // still live, which is the window "stop, then discard" exists to
            // close. A false here falls to the `else` branch, which writes
            // `false` again and holds collection off until a later
            // transition succeeds.
            val stopped = if (deletionOwed()) applySdkFlags(enabled = false).crashlytics else true
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
            if (stopped &&
                dischargeOwedDeletion(preferences) &&
                collectionState == CollectionState.Enabled
            ) {
                val servicing = optOuts.get()
                val enableApplied = applySdkFlags(enabled = true).all
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
                    if (applySdkFlags(enabled = false).crashlytics) dischargeOwedDeletion(preferences)
                    LauncherDebugLog.event("analytics opt-in reversed: an opt-out landed mid-transition")
                } else if (!enableApplied) {
                    // Said explicitly rather than left to the per-flag failure
                    // line (Codex, PR #702). Deliberately *not* symmetric with
                    // the opt-out: a flag that fails to go on collects less
                    // than the user allowed, which harms nobody, and the two
                    // available symmetries are both worse — reverting the
                    // stored preference throws away an answer they gave, and
                    // holding the other SDKs off punishes them for a third
                    // one's failure. `collectionState` stays `Enabled` because
                    // it is accurate for what it gates: this app's own
                    // breadcrumbs, keys and traces, whose SDKs did take the
                    // flag. The startup re-assert retries from the stored
                    // preference on the next launch.
                    LauncherDebugLog.warning(
                        "analytics opt-in incomplete: a flag did not take, so less is collected " +
                            "than allowed until the next launch re-asserts it",
                    )
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
            val stoppedFlags = applySdkFlags(enabled = false)
            // **Keyed on Crashlytics alone, and deliberately not on all three**
            // (Codex, PR #702). The debt is a promise to discard *Crashlytics*
            // reports, and its only precondition is that Crashlytics has
            // stopped writing them. Gating it on every flag meant a
            // persistently-throwing Performance or Analytics setter skipped
            // the deletion on every retry — not deferring the promise but
            // breaking it, since nothing would ever discharge it.
            if (stoppedFlags.crashlytics) dischargeOwedDeletion(preferences)
            if (!stoppedFlags.all) {
                // The retry for the other two is the stored preference, which
                // already reads off, so the startup re-assert re-applies them
                // on the next launch — the same durable path the opt-in
                // direction leans on. What is left is the rest of this
                // process, and it is named rather than left to be inferred:
                // Analytics feeds itself, so a stuck flag there means it may
                // still be collecting behind a switch that reads off.
                LauncherDebugLog.warning(
                    "analytics opt-out incomplete: a flag did not take, so collection may " +
                        "continue until the next launch re-asserts it",
                )
            }
        }
    }

    /**
     * Sets all three SDKs' persisted flags, each in its own `try`: sharing one
     * means a throw from the first silently leaves the rest as they were and
     * reports the opt-out as successful.
     *
     * **Returns whether every flag actually took**, and the caller has to act
     * on it, because for one of the three [collectionState] is not a backstop
     * (Codex, PR #702). Crashlytics and Performance are *fed* by this app —
     * breadcrumbs, recorded exceptions, traces this code starts — so a stuck
     * flag on either is harmless once the wrapper is `Disabled`: nothing will
     * hand them anything. That was the whole justification for logging and
     * carrying on, and Analytics does not fit it. It records its own automatic
     * events with no call site of ours to gate, so a failed opt-out leaves it
     * collecting against a stored preference and a UI that both read off.
     *
     * There is no second lever to reach for — the setter is the only switch —
     * so the answer is to refuse to call the opt-out finished: the caller
     * leaves the discard debt owed, which both re-runs this on the next
     * transition and blocks a re-enable until it has taken. The window that
     * remains is the rest of this process, and it is named in the log rather
     * than left to be inferred.
     */
    private fun applySdkFlags(enabled: Boolean): SdkFlags {
        var crashlytics = true
        var others = true
        try {
            sdk.setCrashlyticsCollectionEnabled(enabled)
        } catch (error: RuntimeException) {
            crashlytics = false
            LauncherDebugLog.failure(error, "crash reporting opt-in/out not applied")
        }
        try {
            sdk.setPerformanceCollectionEnabled(enabled)
        } catch (error: RuntimeException) {
            others = false
            LauncherDebugLog.failure(error, "performance opt-in/out not applied")
        }
        // Third of three, behind the same single answer. The consent card
        // offers crash reports and anonymous analytics together, so a build
        // where one could be live while the others were not would answer a
        // question nobody was asked.
        try {
            sdk.setAnalyticsCollectionEnabled(enabled)
        } catch (error: RuntimeException) {
            others = false
            LauncherDebugLog.failure(error, "analytics opt-in/out not applied")
        }
        return SdkFlags(crashlytics = crashlytics, all = crashlytics && others)
    }

    /**
     * Which of the three flags took.
     *
     * Two fields rather than one boolean because two different decisions read
     * this and they have different preconditions (Codex, PR #702). Discarding
     * unsent reports is safe once **Crashlytics** is stopped — the other two
     * SDKs have nothing to do with whether a Crashlytics report can still be
     * written — while [all] is what says the opt-out as a whole is complete.
     *
     * Collapsing them made a persistent failure in an unrelated SDK skip the
     * deletion on every retry, forever, which breaks the policy's promise
     * outright rather than delaying it.
     */
    private data class SdkFlags(val crashlytics: Boolean, val all: Boolean)

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
    private fun dischargeOwedDeletion(preferences: TelemetryPreferences): Boolean {
        val servicing = optOuts.get()
        // Asks the invariant, not just the in-process generations: a discard
        // inherited from a previous process has no opt-out in this run's
        // counters, so a generation comparison alone would call it settled.
        if (!deletionOwed()) return true
        return try {
            sdk.deleteUnsentReports()
            // Advance only to the generation this call actually serviced. An
            // opt-out that arrived while the delete was in flight bumped
            // [optOuts] past it and keeps its own debt for the next transition.
            servicedOptOuts.updateAndGet { maxOf(it, servicing) }
            // Clear the durable marker only when this delete settled
            // everything owed. An opt-out that landed while the delete was in
            // flight wrote its *own* promise to disk; overwriting it with
            // `false` here would erase a debt this call never serviced. The
            // in-process generations would still hold — but they die with the
            // process, and a crash before that opt-out's own transition ran
            // would then leave the next launch with nothing owed, which is the
            // hole the durable record exists to close.
            if (servicedOptOuts.get() >= optOuts.get()) {
                clearDiscardOwed(preferences)
                // Re-check *after* the write, not only before it.
                // [setCollectionGate] is lock-free by design, so an opt-out can
                // land between the comparison above and this clear and have its
                // just-written marker overwritten — leaving the next process
                // with nothing owed. Restoring it here is the same compensating
                // write the enable path makes for the SDK flags, and for the
                // same reason: closing the window in place beats leaving it to
                // a transition a crash can beat. Ordering makes it total — this
                // read happens after the clear's write, so an opt-out whose own
                // write lands later wins on its own, and one whose write landed
                // earlier is restored here. Re-recording is idempotent.
                if (servicedOptOuts.get() < optOuts.get()) recordDiscardOwed(preferences)
            }
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

    /**
     * The durable half of the debt, kept in [durableDebt] once read so the rest
     * of the transition need not touch storage, and mirrored back on change.
     *
     * A storage failure is treated as "owed": refusing to resume collection is
     * the safe reading of "we cannot tell whether we kept our promise".
     */
    private fun readDiscardOwed(preferences: TelemetryPreferences): Boolean =
        try {
            preferences.isDiscardOwed()
        } catch (error: RuntimeException) {
            LauncherDebugLog.failure(error, "could not read whether a report discard is owed")
            true
        }.also { durableDebt = durableDebt || it }

    private fun recordDiscardOwed(preferences: TelemetryPreferences) {
        durableDebt = true
        try {
            // A `false` return is the same loss as a throw: the promise holds
            // in memory, so this process still refuses to resume collection,
            // but it will not survive a restart. Nothing here can repair
            // that — a failed write cannot record that it failed — so the
            // handling is to say so rather than to treat it as durable.
            if (!preferences.setDiscardOwed(true)) {
                LauncherDebugLog.warning("owed report discard not persisted: the write did not land")
            }
        } catch (error: RuntimeException) {
            // In-memory still holds within this process; what is lost is only
            // the ability to carry the promise across a restart.
            LauncherDebugLog.failure(error, "could not persist that a report discard is owed")
        }
    }

    private fun clearDiscardOwed(preferences: TelemetryPreferences) {
        durableDebt = false
        try {
            if (!preferences.setDiscardOwed(false)) {
                LauncherDebugLog.warning("owed report discard not cleared: the write did not land")
            }
        } catch (error: RuntimeException) {
            // Leaving it set costs one redundant discard next launch, which is
            // harmless; clearing it when it did not stick would be the unsafe
            // direction, so this failure is only logged.
            LauncherDebugLog.failure(error, "could not clear the owed report discard")
        }
    }

    /** Test-only: returns this process's view of the user's choice. */
    internal fun collectionStateForTest(): CollectionState = collectionState

    /** Test-only: whether an opt-out's report deletion is still outstanding. */
    internal fun isDeletionOwedForTest(): Boolean = deletionOwed()

    /** Test-only: puts the wrapper back to its pre-read state. */
    internal fun resetForTest() {
        collectionState = CollectionState.Unknown
        optOuts.set(0)
        servicedOptOuts.set(0)
        durableDebt = false
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

/**
 * [TelemetryPreferences] backed by the real settings store.
 *
 * Each accessor is guarded on its own. An unreadable *choice* means "change
 * nothing", because assuming a value would let a corrupt preferences file
 * overwrite a stored opt-out with an opt-in the user never made. An unreadable
 * *discard record* means "owed", because not knowing whether a promise was
 * kept is not a reason to resume collection.
 */
internal class StoredTelemetryPreferences(
    private val store: DockSettingsStore,
) : TelemetryPreferences {
    override fun isEnabled(): Boolean? = try {
        // An unanswered question is not a "yes". The single read is what makes
        // the whole feature reversible: with [TELEMETRY_REQUIRES_CONSENT] off,
        // this is the stored preference and nothing else, exactly as before.
        store.isTelemetryEnabled &&
            (!TELEMETRY_REQUIRES_CONSENT || store.isTelemetryChoiceAnswered)
    } catch (error: RuntimeException) {
        // Rare: corrupt XML, direct-boot. Both SDKs already hold the user's
        // last choice in their own persisted flags and this only re-asserts it.
        LauncherDebugLog.failure(error, "telemetry preference unreadable, leaving it unchanged")
        null
    }

    override fun isDiscardOwed(): Boolean = store.isReportDiscardOwed

    override fun recordOptOut(): Boolean = store.recordTelemetryOptOut()

    override fun setDiscardOwed(owed: Boolean): Boolean = store.setReportDiscardOwed(owed)
}
