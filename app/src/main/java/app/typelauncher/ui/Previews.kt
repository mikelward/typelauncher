package app.typelauncher

import android.content.Intent
import android.content.res.Configuration
import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Home empty")
@Composable
private fun HomeEmptyPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(filteredApps = emptyList()),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowAgenda = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Home running", fontScale = 1.3f)
@Composable
private fun HomeRunningLargeFontPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(
                filteredApps = previewApps,
                dockedApps = previewApps.take(2).map { it.copy(isDocked = true) },
            ),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowAgenda = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda permission", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun AgendaPermissionDarkPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(screen = LauncherScreen.Agenda, agenda = AgendaUiState.PermissionRequired),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowAgenda = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda empty RTL", locale = "ar")
@Composable
private fun AgendaEmptyRtlPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(screen = LauncherScreen.Agenda, agenda = AgendaUiState.Empty),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowAgenda = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRequestCalendarPermission = {},
        )
    }
}

@Preview(name = "Agenda events")
@Composable
private fun AgendaEventsPreview() {
    TypeLauncherTheme(dynamicColor = false) {
        TypeLauncherApp(
            state = LauncherUiState(
                screen = LauncherScreen.Agenda,
                agenda = AgendaUiState.Events(
                    listOf(
                        AgendaEvent("Planning", 0L, 1L, false, "10:00 AM"),
                        AgendaEvent("Launch day", 0L, 1L, true, "All day"),
                    ),
                ),
            ),
            onQueryChanged = {},
            onClearQuery = {},
            onLaunchActiveApp = {},
            onLaunchApp = {},
            onOpenAppInfo = {},
            onToggleDock = { _, _ -> },
            onResetRank = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowAgenda = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
            onRequestCalendarPermission = {},
        )
    }
}

private val previewApps = listOf(
    InstalledApp(
        name = "Calendar",
        packageName = "app.preview.calendar",
        launchIntent = Intent(),
        icon = null,
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    ),
    InstalledApp(
        name = "Work Calendar",
        packageName = "app.preview.workcalendar",
        launchIntent = Intent(),
        icon = null,
        user = Process.myUserHandle(),
        isWorkApp = true,
        launchWithLauncherApps = false,
    ),
)
