package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescingSweepGateTest {
    @Test
    fun requestBeforeTheGateOpensIsHeldUntilItOpens() {
        val gate = CoalescingSweepGate()

        assertFalse(gate.request())
        assertTrue(gate.open())
        assertFalse(gate.finish())
    }

    @Test
    fun openingWithNothingQueuedStartsNothing() {
        val gate = CoalescingSweepGate()

        assertFalse(gate.open())
        assertTrue(gate.request())
    }

    @Test
    fun requestDuringARunIsCoalescedIntoOneFollowUpRun() {
        val gate = CoalescingSweepGate()
        gate.open()
        assertTrue(gate.request())

        // Three provider-change callbacks land mid-sweep: none starts a
        // concurrent run, and exactly one follow-up runs when it ends.
        assertFalse(gate.request())
        assertFalse(gate.request())
        assertFalse(gate.request())
        assertTrue(gate.finish())
        assertFalse(gate.finish())
    }

    @Test
    fun openingDuringARunDoesNotStartASecondOne() {
        val gate = CoalescingSweepGate()
        assertFalse(gate.request())
        // The startup sweep opens the gate; a run starts...
        assertTrue(gate.open())
        // ...and a redundant open while it runs neither restarts nor doubles it.
        assertFalse(gate.open())
        assertFalse(gate.finish())
    }
}
