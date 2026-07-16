package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the typed-search event row's time column. Unlike the agenda — which
 * groups rows under day headers and so shows time only — search results are a
 * flat, headerless list, so a future match has to carry its own day/date. The
 * format is: time only for today, an abbreviated weekday over the start time
 * for the next six days, and an abbreviated month + day from a week out (when
 * the weekday would repeat within the search window).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgendaSearchTimeFormatTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 7, 16) // a Thursday
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The formatter keeps spaces inside each half non-breaking so the 64dp
    // column never wraps a single label mid-token. Which non-breaking space is
    // used varies by source (the date half is forced to U+00A0; the time half
    // arrives from the platform already carrying a narrow U+202F before AM/PM),
    // so structure assertions normalize every space variant to a plain space,
    // and noBreakingSpaceSurvivesInFutureLabel locks the anti-wrap contract.
    private fun String.normalizeSpaces(): String =
        replace(' ', ' ').replace(' ', ' ')

    private lateinit var previousTimeZone: TimeZone
    private lateinit var previousLocale: Locale

    @Before
    fun pinTimeZoneAndLocale() {
        previousTimeZone = TimeZone.getDefault()
        previousLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreTimeZoneAndLocale() {
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    private fun nowMillis(): Long = millisAt(today, 10, 0)

    private fun millisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun timedEvent(date: LocalDate, hour: Int, minute: Int = 0) = AgendaEvent(
        title = "Event",
        beginMillis = millisAt(date, hour, minute),
        endMillis = millisAt(date, hour + 1, minute),
        isAllDay = false,
        eventId = date.toEpochDay(),
    )

    private fun allDayEvent(date: LocalDate) = AgendaEvent(
        title = "Holiday",
        // All-day instances come back from the provider at UTC midnight.
        beginMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        isAllDay = true,
        eventId = date.toEpochDay(),
    )

    @Test
    fun upcomingTodayEventStacksTodayLabelOverStartTime() {
        // Starts later today (now is 10:00): not yet in progress, so it gets
        // the "Today" label rather than a range.
        val result = timedEvent(today, 14).formatSearchTime(context, nowMillis())

        assertEquals("Today\n2:00 PM", result.normalizeSpaces())
    }

    @Test
    fun inProgressTodayEventShowsRange() {
        // Happening right now (9:00–11:00, now is 10:00): the range keeps it
        // from reading as a start time, so it never looks like a future event.
        val result = timedEvent(today, 9).copy(endMillis = millisAt(today, 11))
            .formatSearchTime(context, nowMillis())

        val normalized = result.normalizeSpaces()
        assertFalse("in-progress event must not carry a day label", normalized.contains("Today"))
        assertTrue("start time present", normalized.contains("9:00"))
        assertTrue("end time present", normalized.contains("11:00"))
    }

    @Test
    fun inProgressOvernightEventShowsRangeNotFutureLookingStart() {
        // Began 10:00 PM yesterday, ends 11:00 AM today, now is 10:00: still in
        // progress. Must not render "Today\n10:00 PM" (reads as tonight) — the
        // range makes clear it's ongoing.
        val ongoing = AgendaEvent(
            title = "Overnight",
            beginMillis = millisAt(today.minusDays(1), 22),
            endMillis = millisAt(today, 11),
            isAllDay = false,
            eventId = 1,
        )

        val normalized = ongoing.formatSearchTime(context, nowMillis()).normalizeSpaces()

        assertFalse("ongoing event must not carry a day label", normalized.contains("Today"))
        assertTrue("start time present", normalized.contains("10:00"))
        assertTrue("end time present", normalized.contains("11:00"))
    }

    @Test
    fun tomorrowStacksWeekdayOverStartTime() {
        val result = timedEvent(today.plusDays(1), 9).formatSearchTime(context, nowMillis())

        assertEquals("Fri\n9:00 AM", result.normalizeSpaces())
    }

    @Test
    fun eventLaterThisWeekUsesWeekday() {
        val result = timedEvent(today.plusDays(3), 12).formatSearchTime(context, nowMillis())

        assertEquals("Sun\n12:00 PM", result.normalizeSpaces())
    }

    @Test
    fun sevenDaysAheadSwitchesToDateBecauseWeekdayRepeats() {
        // today + 7 is the next Thursday; a bare "Thu" would collide with today,
        // so the format must fall through to the month + day.
        val result = timedEvent(today.plusDays(7), 9).formatSearchTime(context, nowMillis())

        assertEquals("Jul 23\n9:00 AM", result.normalizeSpaces())
    }

    @Test
    fun eventWellIntoTheFutureUsesAbbreviatedMonthAndDay() {
        val result = timedEvent(today.plusDays(9), 12).formatSearchTime(context, nowMillis())

        assertEquals("Jul 25\n12:00 PM", result.normalizeSpaces())
    }

    @Test
    fun futureAllDayEventStacksDateOverAllDayLabel() {
        val result = allDayEvent(today.plusDays(3)).formatSearchTime(context, nowMillis())

        assertEquals("Sun\nAll day", result.normalizeSpaces())
    }

    @Test
    fun todayAllDayEventStacksTodayOverAllDayLabel() {
        val result = allDayEvent(today).formatSearchTime(context, nowMillis())

        assertEquals("Today\nAll day", result.normalizeSpaces())
    }

    @Test
    fun noBreakingSpaceSurvivesInFutureLabel() {
        // Every space in a future label must be non-breaking so neither half
        // wraps onto a clipped third line in the 64dp column.
        val result = timedEvent(today.plusDays(9), 9).formatSearchTime(context, nowMillis())

        val halves = result.split("\n")
        assertEquals("date over start time", 2, halves.size)
        halves.forEach { half ->
            assertFalse("half must not contain a breaking space: '$half'", half.contains(' '))
        }
    }
}
