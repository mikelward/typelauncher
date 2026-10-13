package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The test behind "Stay on while lights are on": the room counts as lit from
 * the light level up, until it is well under it, and only changes are news.
 */
class KioskRoomLightTrackerTest {
    @Test
    fun theFirstReadingSaysWhereTheRoomStarts() {
        assertEquals(true, KioskRoomLightTracker(thresholdLux = 20f).onReading(150f))
        assertEquals(false, KioskRoomLightTracker(thresholdLux = 20f).onReading(2f))
        // Under the level but not far under it is dark too.
        assertEquals(false, KioskRoomLightTracker(thresholdLux = 20f).onReading(15f))
    }

    @Test
    fun theLightsComingOnAndGoingOffAreEachNewsOnce() {
        val tracker = KioskRoomLightTracker(thresholdLux = 20f)
        assertEquals(false, tracker.onReading(2f))
        assertEquals(true, tracker.onReading(150f))
        // Staying lit, or getting brighter, is not news.
        assertNull(tracker.onReading(150f))
        assertNull(tracker.onReading(400f))
        assertEquals(false, tracker.onReading(3f))
        assertNull(tracker.onReading(1f))
    }

    @Test
    fun aRoomStaysLitUntilItIsWellUnderTheLevel() {
        val tracker = KioskRoomLightTracker(thresholdLux = 20f)
        tracker.onReading(150f)
        // Just under the level, the lights are still on: a lamp dimmed a
        // little, or the sensor's own wobble.
        assertNull(tracker.onReading(17f))
        assertNull(tracker.onReading(21f))
        assertEquals(false, tracker.onReading(13f))
    }

    @Test
    fun aReadingHoveringJustUnderTheLevelInADarkRoomStaysDark() {
        val tracker = KioskRoomLightTracker(thresholdLux = 20f)
        tracker.onReading(2f)
        assertNull(tracker.onReading(17f))
        assertNull(tracker.onReading(19f))
        assertEquals(true, tracker.onReading(20f))
    }

    @Test
    fun aRoomThatCanNoLongerBeReadCountsAsDark() {
        val tracker = KioskRoomLightTracker(thresholdLux = 20f)
        assertEquals(true, tracker.onReading(150f))

        // The hold is released, once.
        assertEquals(false, tracker.onUnavailable())
        assertEquals(null, tracker.onUnavailable())
        // The sensor's next reading is news again, so a lit room holds the display anew.
        assertEquals(true, tracker.onReading(150f))
    }

    @Test
    fun aDarkRoomThatCanNoLongerBeReadIsNoNews() {
        val tracker = KioskRoomLightTracker(thresholdLux = 20f)
        assertEquals(false, tracker.onReading(2f))

        assertEquals(null, tracker.onUnavailable())
    }

    @Test
    fun theSliderSpansTheRangeEndToEnd() {
        assertEquals(KIOSK_LIGHT_WAKE_MIN_LUX, kioskLightWakeLuxAt(0f), 0f)
        assertEquals(KIOSK_LIGHT_WAKE_MAX_LUX, kioskLightWakeLuxAt(1f), 0f)
        assertEquals(0f, kioskLightWakeSliderPosition(KIOSK_LIGHT_WAKE_MIN_LUX), 1e-6f)
        assertEquals(1f, kioskLightWakeSliderPosition(KIOSK_LIGHT_WAKE_MAX_LUX), 1e-6f)
    }

    @Test
    fun theSliderGivesTheDarkEndRoomToBeSet() {
        // Logarithmic: a third of the way along is still only 10 lux, so the
        // levels that tell a dark room from a lamp-lit one aren't a sliver.
        assertEquals(10f, kioskLightWakeLuxAt(1f / 3f), 0f)
        assertEquals(100f, kioskLightWakeLuxAt(2f / 3f), 0f)
    }

    @Test
    fun aLevelSurvivesTheSliderRoundTrip() {
        listOf(1f, 3f, 20f, 150f, 1000f).forEach { lux ->
            assertEquals(lux, kioskLightWakeLuxAt(kioskLightWakeSliderPosition(lux)), 0f)
        }
    }

    @Test
    fun aLevelIsKeptInRangeAndWhole() {
        assertEquals(KIOSK_LIGHT_WAKE_MIN_LUX, kioskLightWakeLux(0.2f), 0f)
        assertEquals(KIOSK_LIGHT_WAKE_MAX_LUX, kioskLightWakeLux(50_000f), 0f)
        assertEquals(13f, kioskLightWakeLux(12.6f), 0f)
        assertEquals(KIOSK_LIGHT_WAKE_DEFAULT_LUX, kioskLightWakeLux(Float.NaN), 0f)
    }
}
