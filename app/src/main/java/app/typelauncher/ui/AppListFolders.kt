package app.typelauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun AppCategory.labelRes(): Int = when (this) {
    AppCategory.Finance -> R.string.app_folder_finance
    AppCategory.Games -> R.string.app_folder_games
    AppCategory.Health -> R.string.app_folder_health
    AppCategory.Media -> R.string.app_folder_media
    AppCategory.News -> R.string.app_folder_news
    AppCategory.Productivity -> R.string.app_folder_productivity
    AppCategory.Shopping -> R.string.app_folder_shopping
    AppCategory.Social -> R.string.app_folder_social
    AppCategory.Tools -> R.string.app_folder_tools
    AppCategory.Travel -> R.string.app_folder_travel
    AppCategory.Other -> R.string.app_folder_other
}

/**
 * Lets the app list's long-press menus offer "Move to folder". Provided only
 * around the app list while folders are on, so the dock's and recents' menus,
 * which sit outside it, don't offer it.
 */
@Stable
internal class AppFolderMenu(
    /** The folder [InstalledApp] is filed in now, ignoring the minimum folder size. */
    val folderOf: (InstalledApp) -> AppCategory?,
    val onMove: (InstalledApp, AppCategory?) -> Unit,
)

internal val LocalAppFolderMenu = staticCompositionLocalOf<AppFolderMenu?> { null }

/** The folder an app is filed in: the user's choice if they made one, else its manifest's. */
internal fun effectiveAppFolder(app: InstalledApp, assignments: Map<String, AppCategory?>): AppCategory? =
    if (assignments.containsKey(app.id)) assignments[app.id] else app.declaredCategory

/** A closed folder as a name-beside row: the 2×2 mini icon, then the folder's name. */
@Composable
internal fun AppListFolderRow(
    folder: AppListFolder,
    // The folder icon's size; the same as an app row's icon so names line up.
    iconSizeDp: Int,
    onOpen: (AppListFolder) -> Unit,
) {
    val label = stringResource(folder.category.labelRes())
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = { onOpen(folder) })
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag("$APP_LIST_FOLDER_TAG:${folder.category.storageKey}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DockFolderMiniIcon(
            folder = ResolvedDockFolder(folder.id, folder.apps),
            // The mini icon adds the dock's item padding around this size.
            dockIconSizeDp = iconSizeDp - DOCK_ITEM_VERTICAL_PADDING_DP,
            appIconTag = APP_LIST_FOLDER_ICON_TAG,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/**
 * A closed folder as a grid tile, in the same footprint as an app tile (the
 * icon plus 4dp all round), with the name below when app names show.
 */
@Composable
internal fun AppListFolderTile(
    folder: AppListFolder,
    iconSizeDp: Int,
    showLabel: Boolean,
    onOpen: (AppListFolder) -> Unit,
) {
    val label = stringResource(folder.category.labelRes())
    Column(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = { onOpen(folder) })
            .semantics { contentDescription = label }
            .testTag("$APP_LIST_FOLDER_TAG:${folder.category.storageKey}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DockFolderMiniIcon(
            folder = ResolvedDockFolder(folder.id, folder.apps),
            dockIconSizeDp = iconSizeDp,
            appIconTag = APP_LIST_FOLDER_ICON_TAG,
        )
        if (showLabel) {
            Text(
                label,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The closed folders as rows of [AppListFolderTile]s, laid out on the app
 * grid's own columns (`GridCells.Adaptive` at the icon size plus 8dp, 8dp
 * apart) so each tile sits over an app tile's column. Rows read left to right,
 * top to bottom, in folder order.
 */
@Composable
internal fun AppListFolderTileBlock(
    folders: List<AppListFolder>,
    iconSizeDp: Int,
    showLabel: Boolean,
    onOpen: (AppListFolder) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val spacing = APP_GRID_SPACING_DP.dp
        val minCell = (iconSizeDp + APP_GRID_SPACING_DP).dp
        // Same column count and cell width GridCells.Adaptive computes.
        val columns = ((maxWidth + spacing) / (minCell + spacing)).toInt().coerceAtLeast(1)
        val cellWidth = (maxWidth - spacing * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            for (row in folders.chunked(columns)) {
                Row(horizontalArrangement = Arrangement.spacedBy(spacing)) {
                    for (folder in row) {
                        Box(modifier = Modifier.width(cellWidth)) {
                            AppListFolderTile(folder, iconSizeDp = iconSizeDp, showLabel = showLabel, onOpen = onOpen)
                        }
                    }
                }
            }
        }
    }
}

// The app grid's gap between cells, and the padding an app tile adds around
// its icon in total (see IconOnlyAppGrid / IconOnlyAppButton).
private const val APP_GRID_SPACING_DP = 8

/** The open folder's title, with the button that closes it. Full width, above its apps. */
@Composable
internal fun AppListFolderHeader(folder: AppListFolder, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(APP_LIST_FOLDER_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(folder.category.labelRes()),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        IconButton(onClick = onClose, modifier = Modifier.testTag(APP_LIST_FOLDER_CLOSE_TAG)) {
            Icon(
                imageVector = LauncherIcons.Clear,
                contentDescription = stringResource(R.string.dock_folder_close_description),
            )
        }
    }
}

/**
 * The one-time offer to group the app list into folders, at the top of the
 * list. Either answer retires it for good; Settings keeps the switch.
 */
@Composable
internal fun AppFoldersOffer(onAccept: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(APP_FOLDERS_OFFER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_folders_offer_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(APP_FOLDERS_OFFER_DISMISS_TAG)) {
            Text(stringResource(R.string.app_folders_offer_dismiss))
        }
        TextButton(onClick = onAccept, modifier = Modifier.testTag(APP_FOLDERS_OFFER_ACCEPT_TAG)) {
            Text(stringResource(R.string.app_folders_offer_accept))
        }
    }
}

/** Picks the folder for one app, or none. Picking closes the dialog. */
@Composable
internal fun AppFolderPickerDialog(
    current: AppCategory?,
    onPick: (AppCategory?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_menu_move_to_folder)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                val options = listOf<AppCategory?>(null) + AppCategory.entries
                for (option in options) {
                    val label = option?.let { stringResource(it.labelRes()) }
                        ?: stringResource(R.string.app_folder_picker_none)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == current,
                                role = Role.RadioButton,
                                onClick = { onPick(option) },
                            )
                            .padding(vertical = 4.dp)
                            .testTag("$APP_FOLDER_PICKER_OPTION_TAG:${option?.storageKey ?: "none"}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.app_folder_picker_cancel))
            }
        },
    )
}
