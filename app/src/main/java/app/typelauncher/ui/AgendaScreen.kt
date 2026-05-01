package app.typelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun AgendaScreen(
    agenda: AgendaUiState,
    innerPadding: PaddingValues,
    onRequestCalendarPermission: () -> Unit,
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
            is AgendaUiState.Events -> AgendaEventsCard(agenda.events, Modifier.weight(1f))
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
private fun AgendaEventsCard(events: List<AgendaEvent>, modifier: Modifier = Modifier) {
    SectionCard(modifier.testTag(AGENDA_EVENTS_TAG)) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(events) { event ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            event.displayTime,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
