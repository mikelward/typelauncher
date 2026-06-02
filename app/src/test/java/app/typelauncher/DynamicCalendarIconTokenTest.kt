package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [dynamicCalendarIconToken], the pure transform that folds the
 * current date into the icon cache token of date-aware calendar apps so their
 * icon refreshes at the midnight rollover.
 */
class DynamicCalendarIconTokenTest {
    @Test
    fun nonCalendarPackageTokenIsUnchanged() {
        assertEquals(
            "12345",
            dynamicCalendarIconToken("com.example.mail", "12345", "2026-06-02"),
        )
        assertNull(dynamicCalendarIconToken("com.example.mail", null, "2026-06-02"))
    }

    @Test
    fun calendarPackageAppendsDateToBaseToken() {
        assertEquals(
            "12345|cal:2026-06-02",
            dynamicCalendarIconToken("com.google.android.calendar", "12345", "2026-06-02"),
        )
    }

    @Test
    fun calendarPackageWithNoBaseTokenStillGetsDatedKey() {
        // lastUpdateTime can be absent (token null) — the date alone must still
        // produce a distinct, day-specific cache key.
        assertEquals(
            "|cal:2026-06-02",
            dynamicCalendarIconToken("com.google.android.calendar", null, "2026-06-02"),
        )
    }

    @Test
    fun reStampingDoesNotAccumulateDateSegments() {
        // The stamped token round-trips through AppMetadataStore and comes back
        // already carrying yesterday's segment; re-stamping must replace it, not
        // append a second one.
        val yesterday = dynamicCalendarIconToken("com.google.android.calendar", "12345", "2026-06-01")
        val today = dynamicCalendarIconToken("com.google.android.calendar", yesterday, "2026-06-02")
        assertEquals("12345|cal:2026-06-02", today)
    }

    @Test
    fun differentDaysProduceDifferentTokens() {
        val day1 = dynamicCalendarIconToken("com.google.android.calendar", "12345", "2026-06-01")
        val day2 = dynamicCalendarIconToken("com.google.android.calendar", "12345", "2026-06-02")
        assertNotEquals(
            "A new calendar day must yield a fresh cache key so the dated icon re-resolves",
            day1,
            day2,
        )
    }

    @Test
    fun everyKnownCalendarPackageIsStamped() {
        for (pkg in DYNAMIC_CALENDAR_PACKAGES) {
            val token = dynamicCalendarIconToken(pkg, "12345", "2026-06-02")
            assertEquals("$pkg should be date-stamped", "12345|cal:2026-06-02", token)
        }
    }
}
