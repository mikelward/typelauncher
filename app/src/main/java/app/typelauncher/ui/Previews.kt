package app.typelauncher

import android.content.Intent
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
            onRenameApp = { _, _ -> },
            onSetAppIconOverride = {},
            onClearAppIconOverride = {},
            onHideApp = {},
            onUnhideApp = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
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
            onRenameApp = { _, _ -> },
            onSetAppIconOverride = {},
            onClearAppIconOverride = {},
            onHideApp = {},
            onUnhideApp = {},
            onOpenSettings = {},
            onCloseSettings = {},
            onRequestDefaultLauncher = {},
            onDockEnabledChanged = {},
            onAppListIconOnlyChanged = {},
            onDockVisibleIconCountChanged = {},
            onAppListSortOrderChanged = {},
            onShowWidgets = {},
            onShowHome = {},
            appWidgetHost = null,
            appWidgetManager = null,
            onAddWidget = {},
            onDismissWidgetPicker = {},
            onSelectWidget = {},
            onRemoveWidget = {},
        )
    }
}

private val previewApps = listOf(
    InstalledApp(
        name = "Calendar",
        packageName = "app.preview.calendar",
        launchIntent = Intent(),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    ),
    InstalledApp(
        name = "Work Calendar",
        packageName = "app.preview.workcalendar",
        launchIntent = Intent(),
        user = Process.myUserHandle(),
        isWorkApp = true,
        launchWithLauncherApps = false,
    ),
)
