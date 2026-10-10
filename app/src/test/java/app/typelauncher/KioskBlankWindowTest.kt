package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Blank at night"'s window, in minutes after midnight. */
class KioskBlankWindowTest {
    @Test
    fun aWindowWithinOneDayIncludesItsStartButNotItsEnd() {
        assertTrue(isInKioskBlankWindow(0, 0, 360))
        assertTrue(isInKioskBlankWindow(359, 0, 360))
        assertFalse(isInKioskBlankWindow(360, 0, 360))
        assertFalse(isInKioskBlankWindow(23 * 60 + 59, 0, 360))
    }

    @Test
    fun aWindowPastMidnightWrapsAround() {
        val start = 22 * 60
        val end = 6 * 60
        assertTrue(isInKioskBlankWindow(22 * 60, start, end))
        assertTrue(isInKioskBlankWindow(23 * 60 + 59, start, end))
        assertTrue(isInKioskBlankWindow(0, start, end))
        assertTrue(isInKioskBlankWindow(5 * 60 + 59, start, end))
        assertFalse(isInKioskBlankWindow(6 * 60, start, end))
        assertFalse(isInKioskBlankWindow(21 * 60 + 59, start, end))
        assertFalse(isInKioskBlankWindow(12 * 60, start, end))
    }

    @Test
    fun matchingEndsMeanNoWindowNotAllDay() {
        (0 until KIOSK_MINUTES_PER_DAY step 60).forEach { minute ->
            assertFalse(isInKioskBlankWindow(minute, 120, 120))
        }
    }
}
