package app.typelauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

internal const val MIN_DOCK_APP_ICON_SIZE_DP = 40
internal const val DEFAULT_DOCK_APP_ICON_SIZE_DP = 56
internal const val MAX_DOCK_APP_ICON_SIZE_DP = 80

internal data class LauncherUiState(
    val screen: LauncherScreen = LauncherScreen.Home,
    val query: String = "",
    val filteredApps: List<InstalledApp> = emptyList(),
    val dockedApps: List<InstalledApp> = emptyList(),
    val widgetIds: List<Int> = emptyList(),
    val availableWidgets: List<WidgetProvider> = emptyList(),
    val isAddingWidget: Boolean = false,
    val agenda: AgendaUiState = AgendaUiState.PermissionRequired,
    val isSettingsOpen: Boolean = false,
    val isDockEnabled: Boolean = true,
    val dockIconSizeDp: Int = DEFAULT_DOCK_APP_ICON_SIZE_DP,
)

internal data class WidgetProvider(
    val appName: String,
    val label: String,
    val componentName: ComponentName,
    val profile: UserHandle,
    val icon: Drawable?,
    val appIcon: Drawable?,
    val minWidth: Int,
    val minHeight: Int,
    val targetCellWidth: Int,
    val targetCellHeight: Int,
    val previewImage: Drawable?,
) {
    val id: String = "${profile.hashCode()}:${componentName.flattenToShortString()}"
}
