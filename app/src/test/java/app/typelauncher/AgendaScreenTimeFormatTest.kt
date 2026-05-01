package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaScreenTimeFormatTest {
    @Test
    fun timeRange_separatesWithBreakableSpacesAroundDash() {
        val formatted = formatTimeForRow("12:00 PM – 1:00 PM")
        assertEquals("12:00 PM – 1:00 PM", formatted)
        assertTrue(
            "the only regular spaces remaining are around the en-dash",
            formatted.count { it == ' ' } == 2,
        )
        assertFalse(
            "internal whitespace inside each time is non-breaking",
            formatted.startsWith("12:00 ") || formatted.endsWith(" PM"),
        )
    }

    @Test
    fun timeRange_normalisesAsciiHyphenToEnDashWithBreakableSpaces() {
        val formatted = formatTimeForRow("12:00-13:00")
        assertEquals("12:00 – 13:00", formatted)
    }

    @Test
    fun timeRange_compactDashWithoutSpaces_isStillSplitAroundDash() {
        val formatted = formatTimeForRow("12:00–13:00")
        assertEquals("12:00 – 13:00", formatted)
    }

    @Test
    fun singleTime_replacesSpacesWithNonBreakingSpaces() {
        val formatted = formatTimeForRow("9:30 AM")
        assertEquals("9:30 AM", formatted)
        assertFalse("no plain space allowed inside a single time", formatted.contains(' '))
    }

    @Test
    fun allDayLabel_doesNotIntroduceBreakOpportunities() {
        val formatted = formatTimeForRow("All day")
        assertEquals("All day", formatted)
    }
}
