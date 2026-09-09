package app.typelauncher

internal sealed interface AgendaUiState {
    data object PermissionRequired : AgendaUiState
    data object Empty : AgendaUiState
    data class Events(val events: List<AgendaEvent>) : AgendaUiState
}
