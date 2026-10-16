package app.typelauncher

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The quiet minute that brings the kiosk display back, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskReturnTimerTest {
    @Test
    fun returnsOnlyAfterAFullQuietPeriod() = runTest {
        var returns = 0
        val timer = KioskReturnTimer(backgroundScope, quietMs = 60_000) { returns += 1 }
        timer.restart()
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(0, returns)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, returns)
        // Once, not again a minute later.
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, returns)
    }

    @Test
    fun aTouchStartsTheQuietPeriodOver() = runTest {
        var returns = 0
        val timer = KioskReturnTimer(backgroundScope, quietMs = 60_000) { returns += 1 }
        timer.restart()
        advanceTimeBy(50_000)
        timer.onInteraction()
        advanceTimeBy(50_000)
        runCurrent()
        assertEquals(0, returns)
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(1, returns)
    }

    @Test
    fun aTouchCountsNothingWhileStoppedAndTimeAwayIsNotQuiet() = runTest {
        var returns = 0
        val timer = KioskReturnTimer(backgroundScope, quietMs = 60_000) { returns += 1 }
        // A touch on the display itself, or elsewhere in the launcher: no count started.
        timer.onInteraction()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, returns)

        timer.restart()
        advanceTimeBy(30_000)
        // An app in front.
        timer.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(0, returns)
    }
}
