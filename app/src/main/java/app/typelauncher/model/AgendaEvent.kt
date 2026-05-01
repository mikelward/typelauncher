package app.typelauncher

internal data class AgendaEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val isAllDay: Boolean,
    val displayTime: String = "",
    val eventId: Long = 0L,
    val calendarColor: Int? = null,
)
