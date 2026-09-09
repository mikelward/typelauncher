package app.typelauncher

internal object AgendaEventOrganizer {
    fun forNow(
        events: List<AgendaEvent>,
        nowMillis: Long,
        utcTodayStartMillis: Long,
        utcTomorrowStartMillis: Long,
    ): List<AgendaEvent> {
        val todayAllDayEvents = events.asSequence()
            .filter { event ->
                event.isAllDay &&
                    event.beginMillis < utcTomorrowStartMillis &&
                    event.endMillis > utcTodayStartMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        val intersectingOrUpcoming = events.asSequence()
            .filter { event ->
                !event.isAllDay &&
                    event.endMillis >= nowMillis
            }
            .sortedBy { event -> event.beginMillis }
            .toList()
        return todayAllDayEvents + intersectingOrUpcoming
    }

    /**
     * The typed-search variant of [forNow]. Search must reach *future* all-day
     * events — a next-week vacation or birthday inside the search window —
     * that [forNow]'s intersects-today rule deliberately drops from the agenda
     * page, so all-day events are kept whenever they have not already ended
     * before today. Everything sorts as one chronological sequence (an all-day
     * event sorts at its UTC-midnight begin): the search section re-ranks by
     * match tier and only uses this order as the within-tier tie-break, so the
     * agenda's all-day-first presentation split isn't needed here.
     */
    fun forSearch(
        events: List<AgendaEvent>,
        nowMillis: Long,
        utcTodayStartMillis: Long,
        @Suppress("UNUSED_PARAMETER") utcTomorrowStartMillis: Long,
    ): List<AgendaEvent> = events.asSequence()
        .filter { event ->
            if (event.isAllDay) {
                event.endMillis > utcTodayStartMillis
            } else {
                event.endMillis >= nowMillis
            }
        }
        .sortedBy { event -> event.beginMillis }
        .toList()
}
