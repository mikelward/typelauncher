package app.typelauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The opt-out's state machine.
 *
 * No Firebase is initialized in a JVM test, so the SDK half of the transition
 * short-circuits and is *not* covered here — that needs a device carrying the
 * config. What is covered is the part that had the defects: which state this
 * process believes it is in, and whether an opt-out's promise to discard
 * unsent reports survives to be kept.
 */
// Robolectric, not plain JUnit: resolving `firebaseAvailable` calls
// `FirebaseApp.getInstance()`, which reaches `android.os.Process.myPid` and
// throws "not mocked" off-device. Under Robolectric it throws the
// `IllegalStateException` the production code expects for "no Firebase app in
// this process", which is the state a fork or a test build is really in.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherTelemetryStateTest {
    @After
    fun reset() = LauncherTelemetry.resetForTest()

    @Test
    fun theChoiceIsUnknownUntilThePreferenceHasBeenRead() {
        LauncherTelemetry.resetForTest()

        assertEquals(LauncherTelemetry.CollectionState.Unknown, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun readingThePreferenceResolvesTheChoice() {
        LauncherTelemetry.applyCollectionPreference { true }
        assertEquals(LauncherTelemetry.CollectionState.Enabled, LauncherTelemetry.collectionStateForTest())

        LauncherTelemetry.applyCollectionPreference { false }
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // An unreadable preference must not overwrite a stored opt-out with an
    // opt-in the user never chose.
    @Test
    fun anUnreadablePreferenceChangesNothing() {
        LauncherTelemetry.applyCollectionPreference { false }

        LauncherTelemetry.applyCollectionPreference { null }

        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun theGateShutsSynchronouslyAheadOfTheSdkHalf() {
        LauncherTelemetry.applyCollectionPreference { true }

        LauncherTelemetry.setCollectionGate(false)

        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun turningOffOwesAReportDeletion() {
        LauncherTelemetry.setCollectionGate(false)

        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // The defect this rework exists for. Off then straight back on: both queued
    // applications used to read the final `true` and neither deleted anything,
    // so reports from the off period stayed and became sendable again. The debt
    // is an event, not a value recomputed from the latest preference.
    @Test
    fun aRapidOffThenOnStillOwesTheDeletion() {
        LauncherTelemetry.applyCollectionPreference { true }

        LauncherTelemetry.setCollectionGate(false)
        LauncherTelemetry.setCollectionGate(true)

        assertEquals(LauncherTelemetry.CollectionState.Enabled, LauncherTelemetry.collectionStateForTest())
        assertTrue(
            "an opt-out's promise outlives the user changing their mind",
            LauncherTelemetry.isDeletionOwedForTest(),
        )
    }

    @Test
    fun turningOnWithNothingOwedOwesNothing() {
        LauncherTelemetry.applyCollectionPreference { true }

        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }

    /** Records the transition's side effects in order, and can be made to fail. */
    private class RecordingSdk(
        var deleteThrows: Boolean = false,
    ) : TelemetrySdk {
        val calls = mutableListOf<String>()

        override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
            calls += "crashlytics=$enabled"
        }

        override fun setPerformanceCollectionEnabled(enabled: Boolean) {
            calls += "performance=$enabled"
        }

        override fun deleteUnsentReports() {
            calls += "delete"
            if (deleteThrows) throw IllegalStateException("delete failed")
        }
    }

    // Turning off: stop the SDKs, then discard — nothing new can be written
    // after the discard.
    @Test
    fun turningOffStopsCollectionBeforeDiscarding() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference { false }

        assertEquals(listOf("crashlytics=false", "performance=false", "delete"), sdk.calls)
    }

    // Turning on: discharge what an earlier opt-out owed *before* upload is
    // possible again.
    @Test
    fun turningOnDiscardsBeforeReEnabling() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "the flags are stopped before the discard on this path too, since a rapid " +
                "off→on leaves them enabled from before the opt-out",
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=true", "performance=true"),
            sdk.calls,
        )
    }

    // The defect Codex found on this PR: discharging and then re-enabling
    // regardless meant a throwing delete left the opt-out period's reports on
    // disk and made them uploadable — which no later retry can undo, because a
    // report already sent cannot be retracted.
    @Test
    fun aFailedDiscardLeavesCollectionOff() {
        val sdk = RecordingSdk(deleteThrows = true)
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "upload must not be re-enabled over undeleted reports, and the flags are written off",
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=false", "performance=false"),
            sdk.calls,
        )
        assertTrue("the debt survives for the next transition", LauncherTelemetry.isDeletionOwedForTest())
    }

    @Test
    fun aLaterTransitionRetriesTheFailedDiscardAndThenReEnables() {
        val sdk = RecordingSdk(deleteThrows = true)
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        LauncherTelemetry.applyCollectionPreference { true }

        sdk.deleteThrows = false
        sdk.calls.clear()
        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=true", "performance=true"),
            sdk.calls,
        )
        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }

    // A Crashlytics failure must not skip the Performance call: sharing one
    // try block reported a half-applied opt-out as successful.
    @Test
    fun aCrashlyticsFailureStillOptsPerformanceOut() {
        val sdk = object : TelemetrySdk {
            val calls = mutableListOf<String>()
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                throw IllegalStateException("crashlytics unavailable")
            }
            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }
            override fun deleteUnsentReports() {
                calls += "delete"
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference { false }

        assertEquals(listOf("performance=false", "delete"), sdk.calls)
    }

    // A failed Performance opt-out leaves its flag stuck on, so the in-process
    // state is what has to stop traces feeding it.
    @Test
    fun aKnownOptOutStopsTracesEvenWhenThePerformanceFlagFails() {
        val sdk = object : TelemetrySdk {
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit
            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                throw IllegalStateException("performance unavailable")
            }
            override fun deleteUnsentReports() = Unit
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference { false }

        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // The race Codex found: `deleteUnsentReports()` is a blocking IPC, and the
    // user can opt out again while it is in flight. Clearing a flag on the way
    // out erased that newer opt-out's debt *and* re-enabled both persisted
    // flags against a switch that already read off — and those flags survive
    // the process, so a crash before the queued opt-out transition ran would
    // start the next process collecting.
    @Test
    fun anOptOutDuringTheDeleteKeepsItsOwnDebtAndBlocksTheReEnable() {
        val calls = mutableListOf<String>()
        val sdk = object : TelemetrySdk {
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                calls += "crashlytics=$enabled"
            }

            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                // Stands in for the user tapping the switch off while this
                // blocking call is in flight.
                LauncherTelemetry.setCollectionGate(false)
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "upload must not be re-enabled against a switch that reads off",
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=false", "performance=false"),
            calls,
        )
        assertTrue(
            "the newer opt-out's debt is its own and survives",
            LauncherTelemetry.isDeletionOwedForTest(),
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // The ordinary path must still settle: one opt-out, serviced once, clears.
    @Test
    fun servicingAnOptOutWithNoNewerOneClearsTheDebt() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)

        LauncherTelemetry.applyCollectionPreference { true }

        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }

    // The window the mid-delete test cannot reach: an opt-out landing *after*
    // the supersede check, while the persisted flags are being written. Those
    // flags survive the process, so leaving the repair to the queued transition
    // loses the race against a crash.
    @Test
    fun anOptOutDuringTheFlagWriteIsCompensatedImmediately() {
        val calls = mutableListOf<String>()
        val sdk = object : TelemetrySdk {
            var trippedOptOut = false

            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                calls += "crashlytics=$enabled"
                if (enabled && !trippedOptOut) {
                    trippedOptOut = true
                    // Stands in for the user tapping the switch off between the
                    // supersede check and this write.
                    LauncherTelemetry.setCollectionGate(false)
                }
            }

            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "the enable is reversed in the same transition, not left to the next one",
            listOf("crashlytics=true", "performance=true", "crashlytics=false", "performance=false", "delete"),
            calls,
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
        assertFalse("the reversal discharges the new debt too", LauncherTelemetry.isDeletionOwedForTest())
    }

    // A trace outlives the tap that ends collection: one begun a moment before
    // the opt-out is still open when the switch flips, and would otherwise go
    // on feeding the SDK and report its duration on stop().
    @Test
    fun aTraceInFlightWhenTheUserOptsOutStopsReporting() {
        LauncherTelemetry.useSdkForTest(RecordingSdk())
        LauncherTelemetry.applyCollectionPreference { true }
        assertTrue("tracing runs while opted in", LauncherTelemetry.tracingAllowed)

        LauncherTelemetry.setCollectionGate(false)

        assertFalse("an in-flight trace consults the same state", LauncherTelemetry.tracingAllowed)
    }

    // But an unread preference must not stop them: a NoopTrace can never be
    // revived, so refusing here would drop every cold-start trace on a slow
    // start — exactly the starts the traces exist to measure.
    @Test
    fun tracingRunsWhileThePreferenceIsStillUnread() {
        LauncherTelemetry.resetForTest()
        LauncherTelemetry.useSdkForTest(RecordingSdk())

        assertEquals(LauncherTelemetry.CollectionState.Unknown, LauncherTelemetry.collectionStateForTest())
        assertTrue(LauncherTelemetry.tracingAllowed)
    }

    // A trace open across an off→on cycle must not report: at stop() the gate
    // reads allowed again, so only the generation it started on distinguishes
    // an uninterrupted run from one spanning the period the user switched off.
    @Test
    fun aTraceSpanningAnOptOutIsRetiredEvenAfterOptingBackIn() {
        LauncherTelemetry.useSdkForTest(RecordingSdk())
        LauncherTelemetry.applyCollectionPreference { true }
        val startedAt = LauncherTelemetry.optOutGeneration

        LauncherTelemetry.setCollectionGate(false)
        LauncherTelemetry.setCollectionGate(true)

        assertTrue("the gate itself is open again", LauncherTelemetry.tracingAllowed)
        assertTrue(
            "but the generation moved, so a trace from before is retired",
            LauncherTelemetry.optOutGeneration != startedAt,
        )
    }

    @Test
    fun anUninterruptedTraceKeepsItsGeneration() {
        LauncherTelemetry.useSdkForTest(RecordingSdk())
        LauncherTelemetry.applyCollectionPreference { true }
        val startedAt = LauncherTelemetry.optOutGeneration

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(startedAt, LauncherTelemetry.optOutGeneration)
        assertTrue(LauncherTelemetry.tracingAllowed)
    }

    // Not enabling is not the same as being off. On a rapid off→on both queued
    // transitions read the final `true`, so the opt-out never got a transition
    // of its own and the persisted flags are still enabled from before it.
    @Test
    fun aFailedDischargeActivelyDisablesRatherThanJustDecliningToEnable() {
        val sdk = RecordingSdk(deleteThrows = true)
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        LauncherTelemetry.setCollectionGate(true)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "the flags must be written off, not merely left unwritten",
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=false", "performance=false"),
            sdk.calls,
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // An off→on pair landing while the delete is blocked leaves a newer debt
    // the in-flight call never serviced. Reporting "did not throw" as success
    // let the caller re-enable upload over it, and its own generation check
    // could not catch that — the newer generation is current by then, so the
    // before/after comparison comes out equal.
    @Test
    fun anOffOnPairDuringTheDeleteLeavesDebtAndBlocksTheReEnable() {
        val calls = mutableListOf<String>()
        val sdk = object : TelemetrySdk {
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                calls += "crashlytics=$enabled"
            }

            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                // The user taps off, then straight back on, while this blocks.
                LauncherTelemetry.setCollectionGate(false)
                LauncherTelemetry.setCollectionGate(true)
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertEquals(
            "upload must not be re-enabled over a debt this delete never serviced",
            listOf("crashlytics=false", "performance=false", "delete", "crashlytics=false", "performance=false"),
            calls,
        )
        assertTrue("the newer debt survives", LauncherTelemetry.isDeletionOwedForTest())
    }

    // An off→on pair landing *after* the discharge returns but before the
    // generation snapshot leaves both proxies satisfied — generations match and
    // the state is enabled — while a newer debt stands. Only checking the
    // invariant itself catches it.
    @Test
    fun anOffOnPairAfterTheDischargeStillBlocksTheReEnable() {
        val calls = mutableListOf<String>()
        val sdk = object : TelemetrySdk {
            var tripped = false

            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                calls += "crashlytics=$enabled"
            }

            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                if (!tripped) {
                    tripped = true
                    // Lands after this delete completes, standing in for the
                    // gap between the discharge returning and the snapshot.
                    LauncherTelemetry.setCollectionGate(false)
                    LauncherTelemetry.setCollectionGate(true)
                }
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference { true }

        assertTrue(
            "the SDKs must not be left enabled with a discharge outstanding",
            calls.last() == "performance=false" || calls.last() == "delete",
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }
}
