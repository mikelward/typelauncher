package app.typelauncher

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kiosk display's idle timer, on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class KioskIdleDimmerTest {
    @Test
    fun dimsOnlyAfterAFullQuietPeriod() = runTest {
        val dimmer = KioskIdleDimmer(backgroundScope, dimAfterMs = 60_000)
        dimmer.start()
        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(dimmer.dimmed.value)
        advanceTimeBy(2)
        runCurrent()
        assertTrue(dimmer.dimmed.value)
    }

    @Test
    fun activityBrightensAtOnceAndRestartsTheQuietPeriod() = runTest {
        val dimmer = KioskIdleDimmer(backgroundScope, dimAfterMs = 60_000)
        dimmer.start()
        advanceTimeBy(61_000)
        runCurrent()
        assertTrue(dimmer.dimmed.value)

        dimmer.onActivity()
        assertFalse(dimmer.dimmed.value)
        advanceTimeBy(59_000)
        runCurrent()
        assertFalse(dimmer.dimmed.value)
        dimmer.onActivity()
        advanceTimeBy(59_000)
        runCurrent()
        assertFalse(dimmer.dimmed.value)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(dimmer.dimmed.value)
    }

    @Test
    fun stopBrightensAndCancelsTheCountdown() = runTest {
        val dimmer = KioskIdleDimmer(backgroundScope, dimAfterMs = 60_000)
        dimmer.start()
        dimmer.stop()
        advanceTimeBy(120_000)
        runCurrent()
        assertFalse(dimmer.dimmed.value)
    }
}
