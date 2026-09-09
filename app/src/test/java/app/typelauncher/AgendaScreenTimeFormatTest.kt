package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgendaScreenTimeFormatTest {
    @Test
    fun timeRange_replacesInternalSpacesWithNonBreakingSpacesAndDropsDash() {
        val formatted = formatTimeForRow("12:00 PM \u2013 1:00 PM")
        assertEquals("12:00\u00A0PM\n1:00\u00A0PM", formatted)
        assertEquals(
            "no regular spaces remain; the halves are joined by a newline",
            0,
            formatted.count { it == ' ' },
        )
        assertFalse(
            "internal whitespace inside each time is non-breaking",
            formatted.startsWith("12:00 ") || formatted.endsWith(" PM"),
        )
        assertFalse("the dash separator is dropped", formatted.contains('\u2013'))
    }

    @Test
    fun timeRange_normalisesAsciiHyphenToNewline() {
        val formatted = formatTimeForRow("12:00-13:00")
        assertEquals("12:00\n13:00", formatted)
    }

    @Test
    fun timeRange_compactEnDashWithoutSpaces_getsNewline() {
        val formatted = formatTimeForRow("12:00\u201313:00")
        assertEquals("12:00\n13:00", formatted)
    }

    @Test
    fun singleTime_passesThroughUnchanged() {
        assertEquals("9:30 AM", formatTimeForRow("9:30 AM"))
    }

    @Test
    fun allDayLabel_passesThroughUnchanged() {
        assertEquals("All day", formatTimeForRow("All day"))
    }
}
