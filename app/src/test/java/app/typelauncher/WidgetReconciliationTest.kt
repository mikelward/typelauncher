package app.typelauncher

import android.appwidget.AppWidgetManager
import app.typelauncher.WidgetProfileKind.NON_PERSONAL
import app.typelauncher.WidgetProfileKind.OTHER
import app.typelauncher.WidgetProfileKind.PERSONAL
import app.typelauncher.WidgetProfileKind.WORK
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetReconciliationTest {
    @Test
    fun returnsAllocatedIdsMissingFromTheStore() {
        // 7 and 9 were allocated on the host but the launcher never persisted
        // them (crash mid-add, or the historical configure-cancel leak).
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(5, 7, 8, 9),
            knownWidgetIds = listOf(5, 8),
        )

        assertEquals(listOf(7, 9), orphans)
    }

    @Test
    fun returnsEmptyWhenEverythingAllocatedIsTracked() {
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(1, 2, 3),
            knownWidgetIds = listOf(3, 2, 1),
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun neverSweepsTheInFlightPendingId() {
        // A reconciliation racing an active add must not delete the widget whose
        // bind/configure is still in flight — it isn't persisted yet but is not
        // an orphan either.
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(4, 11),
            knownWidgetIds = listOf(4),
            pendingWidgetId = 11,
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun neverSweepsAnIdBeingBound() {
        // An add that has allocated its ID but hasn't finished the bind IPC
        // tracks it as bindingWidgetId, not pendingWidgetId yet. A sweep racing
        // that window must spare it just like a pending one.
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(4, 12),
            knownWidgetIds = listOf(4),
            bindingWidgetId = 12,
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun ignoresInvalidWidgetIds() {
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(AppWidgetManager.INVALID_APPWIDGET_ID, 6),
            knownWidgetIds = emptyList(),
        )

        assertEquals(listOf(6), orphans)
    }

    @Test
    fun misboundReportsRestoredWidgetsBoundInADifferentProfileFromTheirRecord() {
        val records = mapOf(
            // Remembered as work, restored by the platform as personal: misbound.
            1 to WORK,
            // Remembered as work and bound in a work profile: fine.
            2 to WORK,
            // Remembered as personal and bound personal: fine.
            3 to PERSONAL,
            // Remembered as work but not resolvable yet (provider not
            // reinstalled): nothing is known, leave it alone.
            4 to WORK,
            // Remembered as personal, bound work — the other direction counts too.
            6 to PERSONAL,
            // A private-space widget the restore bound to the personal user is
            // the same mistake, and is caught the same way.
            7 to OTHER,
            // A private-space widget still bound in a private space: fine.
            8 to OTHER,
            // Remembered as work, bound in a private space: what a clone
            // profile's widget looks like after an upgrade to API 35 (before
            // it, every non-personal profile was recorded as work) — not a
            // restore mistake, so not released.
            9 to WORK,
            // Nor the reverse: the personal line is the only certain one.
            10 to OTHER,
            // A record from before API 35 says only "not personal": bound
            // personal is the restore mistake; bound in any non-personal
            // profile is fine.
            11 to NON_PERSONAL,
            12 to NON_PERSONAL,
        )
        val bound = mapOf(
            1 to PERSONAL, 2 to WORK, 3 to PERSONAL, 5 to PERSONAL, 6 to WORK, 7 to PERSONAL, 8 to OTHER, 9 to OTHER, 10 to WORK,
            11 to PERSONAL, 12 to OTHER,
        )

        val misbound = misboundRestoredWidgetIds(
            knownWidgetIds = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12),
            recordProfileKind = records::get,
            boundProfileKind = bound::get,
        )

        // 5 has a binding but no record (a legacy widget): skipped.
        assertEquals(listOf(1, 6, 7, 11), misbound)
    }

    @Test
    fun misboundNeverActsOnAnUnresolvedBinding() {
        // A work record whose binding resolves to nothing is not certain
        // enough for the destructive path, whatever the provider state.
        val misbound = misboundRestoredWidgetIds(
            knownWidgetIds = listOf(1),
            recordProfileKind = { WORK },
            boundProfileKind = { null },
        )

        assertEquals(emptyList<Int>(), misbound)
    }

    @Test
    fun restoreOfferOffersAnUnresolvedWorkRecordOnceItsProviderIsBackAndReclaimsOneThatResolves() {
        // 1: work record, unresolved, provider back in a work profile → offer.
        // 2: work record, unresolved, provider not back → nothing.
        // 3: personal record, unresolved → nothing (says nothing about it).
        // 4: work record, already offered (stranded), still unresolved → no
        //    second offer.
        // 5: work record, already offered, now resolves as work (its profile
        //    came back) → reclaim.
        // 6: work record, not stranded, resolves as work → nothing to do.
        // 7: no record → skipped.
        // 8: private-space record, unresolved → nothing: only a work record
        //    is ever offered on a work provider's return.
        // 9: work record, already offered, resolves in a private space (a
        //    clone profile's widget after an upgrade to API 35) → its
        //    binding is back and non-personal, so reclaimed like 5.
        // 10: work record, already offered, resolves personal → the
        //    platform's misbinding; left offered for the tap to retire.
        // 11: a pre-API-35 "not personal" record, unresolved, provider back
        //    → offered like a work one (the tap decides the home).
        val records = mapOf(1 to WORK, 2 to WORK, 3 to PERSONAL, 4 to WORK, 5 to WORK, 6 to WORK, 8 to OTHER, 9 to WORK, 10 to WORK, 11 to NON_PERSONAL)
        val bound = mapOf(5 to WORK, 6 to WORK, 9 to OTHER, 10 to PERSONAL)
        val providerBack = setOf(1, 2, 3, 4, 5, 6, 8, 9, 10, 11) - 2

        val changes = restoreOfferChanges(
            knownWidgetIds = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
            strandedWidgetIds = setOf(4, 5, 9, 10),
            recordProfileKind = records::get,
            boundProfileKind = bound::get,
            workProviderHasReturned = providerBack::contains,
        )

        assertEquals(RestoreOfferChanges(offer = listOf(1, 11), reclaim = listOf(5, 9)), changes)
    }

    @Test
    fun sweepReadsEachBindingOnceSoBothJudgementsSeeTheSameSnapshot() {
        // A binding that resolves between the release pass's read and the
        // offer pass's read would be judged by neither: the first sees
        // nothing, the second a resolved (personal) binding it never
        // releases. One read per id, shared by both, closes that gap.
        val records = mapOf(1 to WORK, 2 to WORK, 3 to WORK, 4 to PERSONAL)
        val reads = mutableListOf<Int>()
        // 1 answers null the first time and PERSONAL on any later read — the
        // race the single snapshot exists to remove.
        val answers = mapOf(2 to WORK, 4 to WORK)
        val boundProfileKind: (Int) -> WidgetProfileKind? = { id ->
            reads += id
            if (id == 1 && reads.count { it == 1 } > 1) PERSONAL else answers[id]
        }

        val decisions = sweepDecisions(
            candidates = listOf(1, 2, 3, 4),
            strandedWidgetIds = setOf(2),
            recordProfileKind = records::get,
            boundProfileKind = boundProfileKind,
            workProviderHasReturned = { true },
        )

        assertEquals("one read per candidate", listOf(1, 2, 3, 4), reads)
        // 1: unresolved in the one snapshot → offered (not released on a
        //    later, different read). 2: stranded work, resolves work →
        //    reclaimed. 3: unresolved, provider back → offered. 4: personal
        //    record bound work → released, and not also offered.
        assertEquals(SweepDecisions(release = listOf(4), offer = listOf(1, 3), reclaim = listOf(2)), decisions)
    }
}
