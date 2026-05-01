package app.typelauncher

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    SectionCard(modifier.testTag(AGENDA_EVENTS_TAG)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(events) { event ->
                AgendaEventRow(event = event, onOpenAgendaEvent = onOpenAgendaEvent)
            }
        }
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
            text = event.displayTime,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
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
