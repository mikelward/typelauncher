package app.typelauncher

import android.appwidget.AppWidgetManager

/**
 * Computes the widget IDs the [android.appwidget.AppWidgetHost] has allocated
 * that the launcher no longer tracks, so a startup pass can delete them and
 * release their bound instances in `AppWidgetService`.
 *
 * Two leak classes accumulate over a device's lifetime, both unswept until now:
 *  - **Crashes / process death mid-add.** [WidgetAddFlow] deletes the allocated
 *    ID on every abandoned path, but if the launcher is killed *during* the
 *    bind → configure → add handshake the in-flight ID is already allocated on
 *    the host yet never persisted to [WidgetStore].
 *  - **Configure-cancel predating the fix.** Widgets bound and left allocated
 *    by the historical configure-cancel bug stay allocated forever; nothing
 *    ever reconciles them away.
 *
 * The pass compares the host's allocated IDs against [knownWidgetIds] (the
 * persisted set the launcher renders) and returns the difference. Two in-flight
 * IDs are always excluded so a reconciliation that races an active add can't
 * delete a widget mid-handshake:
 *  - [pendingWidgetId] — the ID of a bind/configure currently in flight.
 *  - [bindingWidgetId] — an ID that has been allocated but whose bind IPC has
 *    not yet completed, so it isn't `pendingWidgetId` yet. `bindWidget` runs
 *    that IPC off the main thread and only marks the ID pending afterwards
 *    (marking it earlier would let a cancelled coroutine persist an
 *    unresolvable pending ID), which opens a window where the ID is allocated
 *    on the host but not yet tracked anywhere the sweep would spare.
 *
 * At cold start both are `INVALID_APPWIDGET_ID` and exclude nothing.
 */
internal fun orphanedAllocatedWidgetIds(
    allocatedIds: IntArray,
    knownWidgetIds: Collection<Int>,
    pendingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
    bindingWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID,
): List<Int> {
    val known = knownWidgetIds.toHashSet()
    return allocatedIds.filter { id ->
        id != AppWidgetManager.INVALID_APPWIDGET_ID &&
            id != pendingWidgetId &&
            id != bindingWidgetId &&
            id !in known
    }
}

/**
 * Tracked widgets whose live binding sits in a different profile from the one
 * their [WidgetProviderRecord] remembers — a work-profile widget the platform's
 * backup restore re-bound to the personal copy of the same provider.
 *
 * The platform backs a widget up by its provider's component name alone, with
 * no user attached, and on the new device resolves that name against the
 * host's own (personal) user. A work-profile Calendar widget therefore comes
 * back as a working *personal* Calendar widget, in the right slot, with
 * nothing on the host side to say anything went wrong — and the launcher's
 * restore placeholder never appears, because the ID did get a binding. The
 * remembered record is the only witness. [recordProfileKind] reports the
 * kind of profile the record remembers for an ID (`null` for a widget with
 * no record, or a record written before the kind existed — a legacy widget —
 * which is skipped: there is nothing to compare against), and
 * [boundProfileKind] the kind of profile its current binding lives in
 * (`null` for an ID with no resolvable binding — unbound, a provider not yet
 * reinstalled, or a profile that is paused or locked — which is left alone
 * here: nothing certain is known, and this is the one destructive judgement;
 * see [restoreOfferChanges] for what an unresolved binding does get). A
 * mismatch is one across the personal / non-personal line
 * ([WidgetProfileKind.conflictsWith]): a work *or* private-space widget the
 * restore bound to the personal user, or the reverse. A work record against
 * a private-space binding is not one — before API 35 the two kinds were
 * indistinguishable and recorded as work, so that pair is what an OS upgrade
 * looks like, not a restore. The caller releases each returned binding and
 * treats the ID as stranded so it renders as the re-bindable placeholder the
 * user expected in the first place.
 */
internal fun misboundRestoredWidgetIds(
    knownWidgetIds: Collection<Int>,
    recordProfileKind: (Int) -> WidgetProfileKind?,
    boundProfileKind: (Int) -> WidgetProfileKind?,
): List<Int> = knownWidgetIds.filter { id ->
    val remembered = recordProfileKind(id) ?: return@filter false
    val bound = boundProfileKind(id) ?: return@filter false
    remembered.conflictsWith(bound)
}

/** Widgets to newly [offer] restore for, and stranded widgets to [reclaim] (see [restoreOfferChanges]). */
internal data class RestoreOfferChanges(val offer: List<Int>, val reclaim: List<Int>)

/**
 * The non-destructive half of the work-profile check: a *work* record whose
 * binding won't resolve while [workProviderHasReturned] — its provider is
 * installed in a work profile that is available now — is *offered* restore
 * (published as stranded, so its card becomes "Tap to restore"), but its
 * binding is left exactly as it is. A valid work binding resolves once its
 * profile is available, so one that still doesn't is most likely the
 * platform's personal-side binding to a provider the personal user doesn't
 * have (a work-only app), which no later install will ever make right — yet
 * "most likely" is not certain across the separate services these reads come
 * from, so nothing is released on it: the user's tap is what retires the old
 * binding (see `MainActivity`'s restore path), and until then the widget is
 * offered, not touched. The offer is also reversible: a stranded work widget
 * whose binding resolves after all, in any non-personal profile (its profile
 * came back — read as a private space after an OS upgrade, see
 * [WidgetProfileKind.conflictsWith]), is [RestoreOfferChanges.reclaim]ed
 * — un-stranded — so its card returns to the live widget. A host-freed
 * stranded ID never resolves, so it is never reclaimed by mistake.
 */
internal fun restoreOfferChanges(
    knownWidgetIds: Collection<Int>,
    strandedWidgetIds: Set<Int>,
    recordProfileKind: (Int) -> WidgetProfileKind?,
    boundProfileKind: (Int) -> WidgetProfileKind?,
    workProviderHasReturned: (Int) -> Boolean,
): RestoreOfferChanges {
    val offer = mutableListOf<Int>()
    val reclaim = mutableListOf<Int>()
    for (id in knownWidgetIds) {
        val remembered = recordProfileKind(id)
        // A record from before API 35 says only "not personal", which is
        // enough to be offered: the tap decides whether a home is certain.
        if (remembered != WidgetProfileKind.WORK && remembered != WidgetProfileKind.NON_PERSONAL) continue
        val bound = boundProfileKind(id)
        when {
            bound == null && id !in strandedWidgetIds && workProviderHasReturned(id) -> offer += id
            bound != null && bound != WidgetProfileKind.PERSONAL && id in strandedWidgetIds -> reclaim += id
        }
    }
    return RestoreOfferChanges(offer, reclaim)
}

/** What one misbound sweep does: bindings to [release], ids to [offer] restore, stranded ids to [reclaim]. */
internal data class SweepDecisions(val release: List<Int>, val offer: List<Int>, val reclaim: List<Int>)

/**
 * One sweep's judgement over [candidates], from *one* read of each binding.
 * [misboundRestoredWidgetIds] and [restoreOfferChanges] each consult the
 * bound profile, and a binding that resolves between two reads — a provider
 * mid-transition — would be judged by neither: unresolved for the release
 * pass, resolved for the offer pass, with nothing queued to look again. So
 * [boundProfileKind] is read once per id here and both passes see that
 * snapshot; a binding that changes after it is a providers-changed callback
 * away from the next sweep. Ids the caller fails to release stay as they
 * are, judged again next run — an offer never applies to a resolved binding,
 * so nothing is lost by computing offers before the releases land.
 */
internal fun sweepDecisions(
    candidates: Collection<Int>,
    strandedWidgetIds: Set<Int>,
    recordProfileKind: (Int) -> WidgetProfileKind?,
    boundProfileKind: (Int) -> WidgetProfileKind?,
    workProviderHasReturned: (Int) -> Boolean,
): SweepDecisions {
    val bound: Map<Int, WidgetProfileKind?> = candidates.associateWith(boundProfileKind)
    val release = misboundRestoredWidgetIds(
        knownWidgetIds = candidates.filter { id -> id !in strandedWidgetIds },
        recordProfileKind = recordProfileKind,
        boundProfileKind = bound::get,
    )
    val offers = restoreOfferChanges(
        knownWidgetIds = candidates.filter { id -> id !in release },
        strandedWidgetIds = strandedWidgetIds,
        recordProfileKind = recordProfileKind,
        boundProfileKind = bound::get,
        workProviderHasReturned = workProviderHasReturned,
    )
    return SweepDecisions(release, offers.offer, offers.reclaim)
}

/**
 * Schedules one background sweep at a time and never loses a request: the
 * misbound-widget sweep is asked for by the startup reconciliation and by
 * every providers-changed callback, and a request that lands while a sweep
 * is already querying — or before the startup sweep has published its own
 * result — must run *after* it, not be dropped. Dropping it is how a provider
 * that came back mid-sweep would go unjudged until the next process start;
 * running it concurrently is how the startup sweep's `setStrandedWidgetIds`
 * could overwrite an id the misbound sweep had just stranded. Main-thread
 * confined: every call comes from the main thread.
 *
 * Each method returns whether the caller should start a run *now*.
 */
internal class CoalescingSweepGate {
    private var open = false
    private var inFlight = false
    private var requested = false

    /** A sweep is wanted. Starts one if the gate is open and none is running; otherwise queues it. */
    fun request(): Boolean {
        if (!open || inFlight) {
            requested = true
            return false
        }
        return start()
    }

    /** The startup sweep has published; releases a queued request, if any. */
    fun open(): Boolean {
        open = true
        return if (requested && !inFlight) start() else false
    }

    /** The running sweep ended; starts the run a request queued meanwhile, if any. */
    fun finish(): Boolean {
        inFlight = false
        return if (requested) start() else false
    }

    private fun start(): Boolean {
        inFlight = true
        requested = false
        return true
    }
}
