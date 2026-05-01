package app.typelauncher

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun AgendaScreen(
    agenda: AgendaUiState,
    innerPadding: PaddingValues,
    onRequestCalendarPermission: () -> Unit,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(AGENDA_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (agenda) {
            AgendaUiState.PermissionRequired -> PermissionCard(onRequestCalendarPermission)
            AgendaUiState.Empty -> EmptyAgendaCard()
            is AgendaUiState.Events -> AgendaEventsCard(
                events = agenda.events,
                onOpenAgendaEvent = onOpenAgendaEvent,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequestCalendarPermission: () -> Unit) {
    SectionCard(
        modifier = Modifier.testTag(AGENDA_PERMISSION_TAG),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(stringResource(R.string.agenda_permission_message), style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick = onRequestCalendarPermission,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.agenda_permission_button))
        }
    }
}

@Composable
private fun EmptyAgendaCard() {
    SectionCard(Modifier.testTag(AGENDA_EMPTY_TAG)) {
        EmptyState(
            icon = Icons.Filled.EventBusy,
            title = stringResource(R.string.agenda_empty_title),
            body = stringResource(R.string.agenda_empty_state),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AgendaEventsCard(
    events: List<AgendaEvent>,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    // Recompute today on every recomposition rather than caching it: the
    // launcher process can survive past midnight, and a memoized value would
    // keep showing yesterday's "Today"/"Tomorrow" labels until the screen is
    // rebuilt for some other reason.
    val today = LocalDate.now(zone)
    SectionCard(modifier.testTag(AGENDA_EVENTS_TAG)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            var lastDate: LocalDate? = null
            events.forEach { event ->
                val eventDate = event.localDate(zone, today)
                if (eventDate != lastDate) {
                    lastDate = eventDate
                    val label = formatDayLabel(context, eventDate, today, zone)
                    item(key = "header:$eventDate") {
                        DayHeader(label = label, date = eventDate)
                    }
                }
                // CalendarContract.Instances returns one row per occurrence of
                // a recurring event; eventId alone is not unique across
                // instances, so the key also includes beginMillis to keep
                // LazyColumn keys distinct.
                item(key = "event:${event.eventId}:${event.beginMillis}") {
                    AgendaEventRow(event = event, onOpenAgendaEvent = onOpenAgendaEvent)
                }
            }
        }
    }
}

@Composable
private fun DayHeader(label: String, date: LocalDate) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$AGENDA_DAY_HEADER_TAG:$date")
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AgendaEventRow(
    event: AgendaEvent,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
) {
    val stripeColor = event.calendarColor
        ?.let { Color(it) }
        ?: MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenAgendaEvent(event) }
            .testTag("$AGENDA_EVENT_ROW_TAG:${event.eventId}")
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatTimeForRow(event.displayTime),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Visible,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .heightIn(min = 28.dp)
                .background(stripeColor, RoundedCornerShape(2.dp)),
        )
        Text(
            text = event.title,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

private val TIME_RANGE_DASH_REGEX = Regex("\\s*[\u2013\u2014-]\\s*")

internal fun formatTimeForRow(rawTime: String): String {
    // For a start/end range, force the whitespace inside each time half to be
    // non-breaking. The only remaining wrap opportunities are the regular
    // spaces flanking the en-dash, so a narrow row breaks at the dash
    // ("12:00\u00A0PM\n\u2013 1:00\u00A0PM") instead of mid-time
    // ("12:00-13:0\n0"). Wide rows still fit on one line. Single-time strings
    // ("9:30 AM", "All day") pass through unchanged so tests and accessibility
    // tooling see the natural text.
    val match = TIME_RANGE_DASH_REGEX.find(rawTime) ?: return rawTime
    val before = rawTime.substring(0, match.range.first).replace(' ', '\u00A0')
    val after = rawTime.substring(match.range.last + 1).replace(' ', '\u00A0')
    return "$before \u2013 $after"
}

private fun AgendaEvent.localDate(zone: ZoneId, today: LocalDate): LocalDate =
    if (isAllDay) today else Instant.ofEpochMilli(beginMillis).atZone(zone).toLocalDate()

private fun formatDayLabel(
    context: android.content.Context,
    date: LocalDate,
    today: LocalDate,
    zone: ZoneId,
): String = when (date) {
    today -> context.getString(R.string.agenda_day_today)
    today.plusDays(1) -> context.getString(R.string.agenda_day_tomorrow)
    else -> {
        val millis = date.atStartOfDay(zone).toInstant().toEpochMilli()
        DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
}
