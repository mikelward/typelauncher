package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AgendaEventOrganizerTest {
    @Test
    fun orders_todayAllDayBefore_intersectingAndUpcomingTimedEvents() {
        val now = 1_000L
        val utcTodayStart = 0L
        val utcTomorrowStart = 86_400_000L
        val events = listOf(
            event("future timed", begin = 2_000L, end = 3_000L, isAllDay = false),
            event("intersecting timed", begin = 500L, end = 1_500L, isAllDay = false),
            event("all day today", begin = 0L, end = utcTomorrowStart, isAllDay = true),
            event("past timed", begin = 100L, end = 900L, isAllDay = false),
        )

        val ordered = AgendaEventOrganizer.forNow(
            events = events,
            nowMillis = now,
            utcTodayStartMillis = utcTodayStart,
            utcTomorrowStartMillis = utcTomorrowStart,
        )

        assertEquals(
            listOf("all day today", "intersecting timed", "future timed"),
            ordered.map { it.title },
        )
    }

    @Test
    fun excludes_allDayEventsOutsideUtcTodayWindow() {
        val now = 10_000L
        val utcTodayStart = 0L
        val utcTomorrowStart = 86_400_000L
        val events = listOf(
            event("yesterday all day", begin = -86_400_000L, end = 0L, isAllDay = true),
            event("tomorrow all day", begin = utcTomorrowStart, end = utcTomorrowStart + 86_400_000L, isAllDay = true),
            event("today all day", begin = 0L, end = utcTomorrowStart, isAllDay = true),
        )

        val ordered = AgendaEventOrganizer.forNow(
            events = events,
            nowMillis = now,
            utcTodayStartMillis = utcTodayStart,
            utcTomorrowStartMillis = utcTomorrowStart,
        )

        assertEquals(listOf("today all day"), ordered.map { it.title })
    }

    @Test
    fun excludes_tomorrowAllDayWhenLocalDateIsStillPreviousUtcDay() {
        val now = 1_000L
        val utcTodayStart = 0L
        val utcTomorrowStart = 86_400_000L
        val events = listOf(
            event("tomorrow all day utc", begin = utcTomorrowStart, end = utcTomorrowStart + 86_400_000L, isAllDay = true),
            event("today all day utc", begin = utcTodayStart, end = utcTomorrowStart, isAllDay = true),
        )

        val ordered = AgendaEventOrganizer.forNow(
            events = events,
            nowMillis = now,
            utcTodayStartMillis = utcTodayStart,
            utcTomorrowStartMillis = utcTomorrowStart,
        )

        assertEquals(listOf("today all day utc"), ordered.map { it.title })
    }

    private fun event(title: String, begin: Long, end: Long, isAllDay: Boolean): MainActivity.AgendaEvent =
        MainActivity.AgendaEvent(
            title = title,
            beginMillis = begin,
            endMillis = end,
            isAllDay = isAllDay,
        )
}
