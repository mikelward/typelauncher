package app.typelauncher

import android.content.ComponentName
import android.graphics.drawable.Drawable
import android.os.UserHandle

internal const val MIN_DOCK_APP_ICON_SIZE_DP = 40
internal const val DEFAULT_DOCK_APP_ICON_SIZE_DP = 56
internal const val MAX_DOCK_APP_ICON_SIZE_DP = 80
internal const val MIN_DOCK_ICON_COUNT = 1
internal const val DEFAULT_DOCK_ICON_COUNT = 4
internal const val MAX_DOCK_ICON_COUNT = 8
internal const val DEFAULT_DOCK_SCREEN_WIDTH_DP = 411
private const val DOCK_HORIZONTAL_PADDING_DP = 64
private const val DOCK_ITEM_HORIZONTAL_PADDING_DP = 8
private const val DOCK_ITEM_SPACING_DP = 8

internal enum class AppListSortOrder {
    Usage,
    Alphabetical,
}

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
    val isAppListIconOnly: Boolean = false,
    val dockIconCount: Int = DEFAULT_DOCK_ICON_COUNT,
    val appListSortOrder: AppListSortOrder = AppListSortOrder.Usage,
    val isLoadingApps: Boolean = false,
)

internal fun dockSlotCountForIconSize(screenWidthDp: Int, iconSizeDp: Int): Int {
    val availableWidthDp = (screenWidthDp - DOCK_HORIZONTAL_PADDING_DP).coerceAtLeast(0)
    return ((availableWidthDp + DOCK_ITEM_SPACING_DP) /
        (iconSizeDp + DOCK_ITEM_HORIZONTAL_PADDING_DP + DOCK_ITEM_SPACING_DP)).coerceAtLeast(1)
}

internal fun dockIconSizeForSlotCount(screenWidthDp: Int, slotCount: Int): Int {
    val availableWidthDp = (screenWidthDp - DOCK_HORIZONTAL_PADDING_DP).coerceAtLeast(0)
    val clampedSlotCount = slotCount.coerceAtLeast(1)
    val itemChromeWidthDp = DOCK_ITEM_SPACING_DP * (clampedSlotCount - 1) +
        DOCK_ITEM_HORIZONTAL_PADDING_DP * clampedSlotCount
    return ((availableWidthDp - itemChromeWidthDp) / clampedSlotCount)
        .coerceIn(MIN_DOCK_APP_ICON_SIZE_DP, MAX_DOCK_APP_ICON_SIZE_DP)
}

internal fun dockSlotCountRange(screenWidthDp: Int): IntRange {
    val minSlotCount = dockSlotCountForIconSize(screenWidthDp, MAX_DOCK_APP_ICON_SIZE_DP)
    val maxSlotCount = dockSlotCountForIconSize(screenWidthDp, MIN_DOCK_APP_ICON_SIZE_DP)
    return minSlotCount..maxSlotCount
}

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
