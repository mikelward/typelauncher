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
}
