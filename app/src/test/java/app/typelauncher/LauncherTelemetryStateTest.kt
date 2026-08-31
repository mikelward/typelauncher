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
    /**
     * The store the *gate* writes its durable record into. Tests that also
     * want to read that record back — or carry it across a simulated restart —
     * pass this same instance to `applyCollectionPreference`.
     */
    private val prefs = FakePreferences()

    @After
    fun reset() = LauncherTelemetry.resetForTest()

    @Test
    fun theChoiceIsUnknownUntilThePreferenceHasBeenRead() {
        LauncherTelemetry.resetForTest()

        assertEquals(LauncherTelemetry.CollectionState.Unknown, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun readingThePreferenceResolvesTheChoice() {
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))
        assertEquals(LauncherTelemetry.CollectionState.Enabled, LauncherTelemetry.collectionStateForTest())

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // An unreadable preference must not overwrite a stored opt-out with an
    // opt-in the user never chose.
    @Test
    fun anUnreadablePreferenceChangesNothing() {
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = null))

        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun theGateShutsSynchronouslyAheadOfTheSdkHalf() {
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        LauncherTelemetry.setCollectionGate(false, prefs)

        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    @Test
    fun turningOffOwesAReportDeletion() {
        LauncherTelemetry.setCollectionGate(false, prefs)

        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // The defect this rework exists for. Off then straight back on: both queued
    // applications used to read the final `true` and neither deleted anything,
    // so reports from the off period stayed and became sendable again. The debt
    // is an event, not a value recomputed from the latest preference.
    @Test
    fun aRapidOffThenOnStillOwesTheDeletion() {
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        LauncherTelemetry.setCollectionGate(false, prefs)
        LauncherTelemetry.setCollectionGate(true, prefs)

        assertEquals(LauncherTelemetry.CollectionState.Enabled, LauncherTelemetry.collectionStateForTest())
        assertTrue(
            "an opt-out's promise outlives the user changing their mind",
            LauncherTelemetry.isDeletionOwedForTest(),
        )
    }

    // The hole this PR exists to close, at the layer it actually opens: the
    // debt has to reach disk at the opt-out itself. Recording it inside the
    // background transition left a rapid off→on plus a process death — the
    // exact case — with the preference back at `true` and no marker written.
    @Test
    fun optingOutRecordsTheDebtOnDiskBeforeAnyTransitionRuns() {
        LauncherTelemetry.setCollectionGate(false, prefs)

        assertTrue("the promise is durable the moment it is made", prefs.owed)
    }

    @Test
    fun aRapidOffThenOnLeavesTheDebtOnDiskForTheNextProcess() {
        LauncherTelemetry.setCollectionGate(false, prefs)
        LauncherTelemetry.setCollectionGate(true, prefs)
        // The opt-in's preference write is the ViewModel's, not the gate's:
        // only an opt-out is stored durably, together with its debt.
        prefs.enabled = true

        assertTrue("the stored preference reads on; the marker is what remembers", prefs.owed)

        // The process dies here — no transition ever ran. A fresh one starts
        // with only what is on disk.
        LauncherTelemetry.resetForTest()
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(prefs)

        assertEquals(
            "the inherited debt is discharged before collection resumes",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=true", "performance=true", "analytics=true"),
            sdk.calls,
        )
        assertFalse(prefs.owed)
    }

    // `commit()` reports a write that never landed by returning `false`. The
    // promise then holds in memory only, so this process must still refuse to
    // resume collection — nothing can make the failed write durable.
    @Test
    fun aFailedRecordStillStopsThisProcessFromResuming() {
        val unwritable = FakePreferences(enabled = true, writeFails = true)

        LauncherTelemetry.setCollectionGate(false, unwritable)

        assertFalse("the write did not land", unwritable.owed)
        assertTrue("but the debt is still owed here", LauncherTelemetry.isDeletionOwedForTest())
    }

    // An opt-out landing while the delete is in flight writes its own promise
    // to disk; the older discharge must not overwrite it. The in-process
    // generations already blocked the re-enable, but they die with the process.
    @Test
    fun aDeleteDoesNotClearADebtItNeverServiced() {
        val store = FakePreferences(enabled = true)
        val sdk = object : TelemetrySdk {
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit
            override fun setPerformanceCollectionEnabled(enabled: Boolean) = Unit
            override fun setAnalyticsCollectionEnabled(enabled: Boolean) = Unit
            override fun deleteUnsentReports() {
                // The user opts out again while this call is blocked.
                LauncherTelemetry.setCollectionGate(false, store)
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, store)

        LauncherTelemetry.applyCollectionPreference(store)

        assertTrue("the newer opt-out's promise survives the older discharge", store.owed)
        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // The narrower shape of the same race: the opt-out lands after the
    // generation comparison has already passed, so the clear goes ahead and
    // overwrites a marker that was written moments earlier.
    @Test
    fun aDebtRecordedAfterTheGenerationCheckIsRestored() {
        val store = FakePreferences(enabled = true)
        var optedOut = false
        val sdk = object : TelemetrySdk {
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) = Unit
            override fun setPerformanceCollectionEnabled(enabled: Boolean) = Unit
            override fun setAnalyticsCollectionEnabled(enabled: Boolean) = Unit
            override fun deleteUnsentReports() = Unit
        }
        LauncherTelemetry.useSdkForTest(sdk)
        // Opting out from inside the store's own write is the only way to land
        // an opt-out *between* the comparison and the clear it guards.
        store.onWrite = { written ->
            if (!written && !optedOut) {
                optedOut = true
                LauncherTelemetry.setCollectionGate(false, store)
            }
        }
        LauncherTelemetry.setCollectionGate(false, store)

        LauncherTelemetry.applyCollectionPreference(store)

        assertTrue("the clear must not outlive the opt-out that raced it", store.owed)
        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // A death between "a discard is owed" and "the user said no" used to leave
    // the next launch reading enabled-and-owed, which it honors by deleting the
    // reports and then resuming collection — promise kept, choice lost.
    @Test
    fun anOptOutStoresTheChoiceAndItsDebtTogether() {
        val store = FakePreferences(enabled = true)

        LauncherTelemetry.setCollectionGate(false, store)

        assertEquals(false, store.enabled)
        assertTrue(store.owed)

        // The process dies here; a fresh one sees only what is stored.
        LauncherTelemetry.resetForTest()
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(store)

        assertEquals(
            "the choice survives with the debt, so nothing re-enables",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete"),
            sdk.calls,
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // An unreadable *choice* is not an unreadable *promise*. Returning early
    // left an inherited discard unserviced while the SDKs' own persisted flags
    // stayed enabled from before — collecting, against a promise to delete.
    @Test
    fun anUnreadableChoiceStillServicesAnInheritedDebt() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = null, owed = true))

        assertEquals(listOf("crashlytics=false", "performance=false", "analytics=false", "delete"), sdk.calls)
    }

    // ...but with nothing owed it still changes nothing at all.
    @Test
    fun anUnreadableChoiceWithNothingOwedTouchesNoFlags() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = null, owed = false))

        assertEquals(emptyList<String>(), sdk.calls)
    }

    @Test
    fun turningOnWithNothingOwedOwesNothing() {
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }


    /** Stands in for the persisted store, including across a simulated restart. */
    private class FakePreferences(
        var enabled: Boolean? = true,
        var owed: Boolean = false,
        val readThrows: Boolean = false,
        val writeFails: Boolean = false,
    ) : TelemetryPreferences {
        override fun isEnabled(): Boolean? = enabled

        override fun isDiscardOwed(): Boolean =
            if (readThrows) throw IllegalStateException("prefs unreadable") else owed

        /** Lets a test interleave work with a write, to reach a real race. */
        var onWrite: ((Boolean) -> Unit)? = null

        override fun recordOptOut(): Boolean {
            if (writeFails) return false
            // One transaction: both or neither, which is the property under test.
            enabled = false
            owed = true
            onWrite?.invoke(true)
            return true
        }

        override fun setDiscardOwed(owed: Boolean): Boolean {
            if (writeFails) return false
            this.owed = owed
            onWrite?.invoke(owed)
            return true
        }
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

        override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
            calls += "analytics=$enabled"
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

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))

        assertEquals(listOf("crashlytics=false", "performance=false", "analytics=false", "delete"), sdk.calls)
    }

    // Turning on: discharge what an earlier opt-out owed *before* upload is
    // possible again.
    @Test
    fun turningOnDiscardsBeforeReEnabling() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, prefs)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "the flags are stopped before the discard on this path too, since a rapid " +
                "off→on leaves them enabled from before the opt-out",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=true", "performance=true", "analytics=true"),
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
        LauncherTelemetry.setCollectionGate(false, prefs)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "upload must not be re-enabled over undeleted reports, and the flags are written off",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=false", "performance=false", "analytics=false"),
            sdk.calls,
        )
        assertTrue("the debt survives for the next transition", LauncherTelemetry.isDeletionOwedForTest())
    }

    @Test
    fun aLaterTransitionRetriesTheFailedDiscardAndThenReEnables() {
        val sdk = RecordingSdk(deleteThrows = true)
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, prefs)
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        sdk.deleteThrows = false
        sdk.calls.clear()
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=true", "performance=true", "analytics=true"),
            sdk.calls,
        )
        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }

    // A Crashlytics failure must not skip the Performance or Analytics calls:
    // sharing one try block reported a half-applied opt-out as successful.
    //
    // It must not clear the debt either (Codex, PR #702), and the reason is
    // specific to this SDK rather than general: *Crashlytics* writes the
    // reports the debt promises to discard, so a failed Crashlytics stop means
    // deleting now would clear a promise it can immediately break again. The
    // other two failing does not have that property — see
    // `anAnalyticsFailureStillDiscardsTheReports`.
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

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                calls += "analytics=$enabled"
            }
            override fun deleteUnsentReports() {
                calls += "delete"
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))

        assertEquals(listOf("performance=false", "analytics=false"), sdk.calls)
        assertTrue(
            "Crashlytics is still collecting, so the debt stands and a later transition retries it",
            LauncherTelemetry.isDeletionOwedForTest(),
        )
    }

    // Codex, PR #702, twice over. An Analytics failure must not hold the
    // *Crashlytics* deletion hostage: the debt is a promise to discard
    // Crashlytics reports, and its only precondition is that Crashlytics has
    // stopped writing them. Gating it on all three meant a persistently
    // throwing setter in an unrelated SDK skipped the delete on every retry —
    // breaking the promise outright rather than deferring it.
    @Test
    fun anAnalyticsFailureStillDiscardsTheReports() {
        val sdk = object : TelemetrySdk {
            val calls = mutableListOf<String>()
            override fun setCrashlyticsCollectionEnabled(enabled: Boolean) {
                calls += "crashlytics=$enabled"
            }
            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                throw IllegalStateException("analytics unavailable")
            }
            override fun deleteUnsentReports() {
                calls += "delete"
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))

        // The other two still applied — one SDK's failure never skips another
        // — and Crashlytics stopping is what makes the delete safe.
        assertEquals(listOf("crashlytics=false", "performance=false", "delete"), sdk.calls)
        assertFalse(
            "Crashlytics stopped, so the promise was kept and the debt is discharged",
            LauncherTelemetry.isDeletionOwedForTest(),
        )
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

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                throw IllegalStateException("performance unavailable")
            }
            override fun deleteUnsentReports() = Unit
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = false))

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

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                calls += "analytics=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                // Stands in for the user tapping the switch off while this
                // blocking call is in flight.
                LauncherTelemetry.setCollectionGate(false, prefs)
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, prefs)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "upload must not be re-enabled against a switch that reads off",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=false", "performance=false", "analytics=false"),
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
        LauncherTelemetry.setCollectionGate(false, prefs)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

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
                    LauncherTelemetry.setCollectionGate(false, prefs)
                }
            }

            override fun setPerformanceCollectionEnabled(enabled: Boolean) {
                calls += "performance=$enabled"
            }

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                calls += "analytics=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "the enable is reversed in the same transition, not left to the next one",
            listOf("crashlytics=true", "performance=true", "analytics=true", "crashlytics=false", "performance=false", "analytics=false", "delete"),
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
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))
        assertTrue("tracing runs while opted in", LauncherTelemetry.tracingAllowed)

        LauncherTelemetry.setCollectionGate(false, prefs)

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
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))
        val startedAt = LauncherTelemetry.optOutGeneration

        LauncherTelemetry.setCollectionGate(false, prefs)
        LauncherTelemetry.setCollectionGate(true, prefs)

        assertTrue("the gate itself is open again", LauncherTelemetry.tracingAllowed)
        assertTrue(
            "but the generation moved, so a trace from before is retired",
            LauncherTelemetry.optOutGeneration != startedAt,
        )
    }

    @Test
    fun anUninterruptedTraceKeepsItsGeneration() {
        LauncherTelemetry.useSdkForTest(RecordingSdk())
        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))
        val startedAt = LauncherTelemetry.optOutGeneration

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

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
        LauncherTelemetry.setCollectionGate(false, prefs)
        LauncherTelemetry.setCollectionGate(true, prefs)
        sdk.calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "the flags must be written off, not merely left unwritten",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=false", "performance=false", "analytics=false"),
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

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                calls += "analytics=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                // The user taps off, then straight back on, while this blocks.
                LauncherTelemetry.setCollectionGate(false, prefs)
                LauncherTelemetry.setCollectionGate(true, prefs)
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, prefs)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertEquals(
            "upload must not be re-enabled over a debt this delete never serviced",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=false", "performance=false", "analytics=false"),
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

            override fun setAnalyticsCollectionEnabled(enabled: Boolean) {
                calls += "analytics=$enabled"
            }

            override fun deleteUnsentReports() {
                calls += "delete"
                if (!tripped) {
                    tripped = true
                    // Lands after this delete completes, standing in for the
                    // gap between the discharge returning and the snapshot.
                    LauncherTelemetry.setCollectionGate(false, prefs)
                    LauncherTelemetry.setCollectionGate(true, prefs)
                }
            }
        }
        LauncherTelemetry.useSdkForTest(sdk)
        LauncherTelemetry.setCollectionGate(false, prefs)
        calls.clear()

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true))

        assertTrue(
            "the SDKs must not be left enabled with a discharge outstanding",
            // `analytics=false` is the last of the three flag writes now, so it
            // is the one that can be left at the end alongside `delete`.
            calls.last() == "analytics=false" || calls.last() == "delete",
        )
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // The case this exists for. A previous process opted out, promised the
    // discard, and died before servicing it — leaving the stored preference
    // reading `true`. Nothing in memory knows a discard was ever owed, so only
    // the persisted record can tell this process to make good on it.
    @Test
    fun aDiscardOwedByAPreviousProcessIsServicedAtStartup() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)
        val inherited = FakePreferences(enabled = true, owed = true)

        LauncherTelemetry.applyCollectionPreference(inherited)

        assertEquals(
            "the inherited discard is serviced before collection resumes",
            listOf("crashlytics=false", "performance=false", "analytics=false", "delete", "crashlytics=true", "performance=true", "analytics=true"),
            sdk.calls,
        )
        assertFalse("and the record is cleared once it succeeds", inherited.owed)
        assertFalse(LauncherTelemetry.isDeletionOwedForTest())
    }

    @Test
    fun anOptOutRecordsTheDiscardDurably() {
        val preferences = FakePreferences(enabled = false)
        LauncherTelemetry.useSdkForTest(RecordingSdk())

        LauncherTelemetry.applyCollectionPreference(preferences)

        assertFalse("serviced in this process, so the record is cleared again", preferences.owed)
        assertEquals(LauncherTelemetry.CollectionState.Disabled, LauncherTelemetry.collectionStateForTest())
    }

    // A failed discard must leave the durable record set, so the next process
    // retries rather than inheriting a clean slate.
    @Test
    fun aFailedDiscardLeavesTheRecordSetForTheNextProcess() {
        LauncherTelemetry.useSdkForTest(RecordingSdk(deleteThrows = true))
        val preferences = FakePreferences(enabled = false)

        LauncherTelemetry.applyCollectionPreference(preferences)

        assertTrue("the promise outlives this process", preferences.owed)
        assertTrue(LauncherTelemetry.isDeletionOwedForTest())
    }

    // Not knowing whether the promise was kept is not a reason to resume.
    @Test
    fun anUnreadableDiscardRecordIsTreatedAsOwed() {
        val sdk = RecordingSdk()
        LauncherTelemetry.useSdkForTest(sdk)

        LauncherTelemetry.applyCollectionPreference(FakePreferences(enabled = true, readThrows = true))

        assertTrue("a discard is attempted rather than assumed unnecessary", sdk.calls.contains("delete"))
    }
}
