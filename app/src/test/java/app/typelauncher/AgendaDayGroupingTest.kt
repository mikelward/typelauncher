package app.typelauncher

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the day-header grouping behind the agenda LazyColumn. The headers
 * are LazyColumn keys (`header:<date>`), so emitting the same date twice is
 * not a cosmetic problem — it throws `IllegalArgumentException` at
 * composition and crashes the launcher.
 */
class AgendaDayGroupingTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 6, 9)

    private fun millisAt(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun timedEvent(title: String, begin: Long, end: Long) = AgendaEvent(
        title = title,
        beginMillis = begin,
        endMillis = end,
        isAllDay = false,
        eventId = begin,
    )

    @Test
    fun ongoingEventFromYesterdayGroupsUnderToday() {
        val yesterday = today.minusDays(1)
        val ongoing = timedEvent(
            "Overnight",
            begin = millisAt(yesterday, 23, 30),
            end = millisAt(today, 0, 30),
        )

        val entries = buildAgendaListEntries(listOf(ongoing), zone, today)

        assertEquals(
            listOf(AgendaListEntry.DayHeader(today), AgendaListEntry.EventRow(ongoing)),
            entries,
        )
    }

    @Test
    fun allDayPlusOngoingOvernightEventDoesNotDuplicateTodayHeader() {
        // Regression test for the duplicate-key crash: the organizer returns
        // all-day events first (grouped under today), then timed events by
        // start time — including a still-ongoing event that began yesterday.
        // Before the clamp, this shape emitted header dates [today,
        // yesterday, today]: the second `header:<today>` is a duplicate
        // LazyColumn key.
        val yesterday = today.minusDays(1)
        val allDay = AgendaEvent(
            title = "Holiday",
            beginMillis = 0L,
            endMillis = 0L,
            isAllDay = true,
            eventId = 1L,
        )
        val ongoing = timedEvent(
            "Overnight",
            begin = millisAt(yesterday, 23, 30),
            end = millisAt(today, 0, 30),
        )
        val morning = timedEvent(
            "Standup",
            begin = millisAt(today, 9, 0),
            end = millisAt(today, 9, 30),
        )

        val entries = buildAgendaListEntries(listOf(allDay, ongoing, morning), zone, today)

        val headerDates = entries.filterIsInstance<AgendaListEntry.DayHeader>().map { it.date }
        assertEquals("every day header must be unique", headerDates.distinct(), headerDates)
        assertEquals(listOf(today), headerDates)
    }

    @Test
    fun upcomingEventsStillGroupByStartDay() {
        val tomorrow = today.plusDays(1)
        val todayEvent = timedEvent(
            "Standup",
            begin = millisAt(today, 9, 0),
            end = millisAt(today, 9, 30),
        )
        val tomorrowEvent = timedEvent(
            "Dentist",
            begin = millisAt(tomorrow, 10, 0),
            end = millisAt(tomorrow, 11, 0),
        )

        val entries = buildAgendaListEntries(listOf(todayEvent, tomorrowEvent), zone, today)

        assertEquals(
            listOf(
                AgendaListEntry.DayHeader(today),
                AgendaListEntry.EventRow(todayEvent),
                AgendaListEntry.DayHeader(tomorrow),
                AgendaListEntry.EventRow(tomorrowEvent),
            ),
            entries,
        )
    }
}
