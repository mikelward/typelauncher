package app.typelauncher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
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
    AppCategory.Home -> R.string.app_folder_home
    AppCategory.Media -> R.string.app_folder_media
    AppCategory.News -> R.string.app_folder_news
    AppCategory.Productivity -> R.string.app_folder_productivity
    AppCategory.Shopping -> R.string.app_folder_shopping
    AppCategory.Social -> R.string.app_folder_social
    AppCategory.Tools -> R.string.app_folder_tools
    AppCategory.Travel -> R.string.app_folder_travel
    AppCategory.Other -> R.string.app_folder_other
}

internal fun AppCategory.icon(): ImageVector = when (this) {
    AppCategory.Finance -> CategoryIcons.Finance
    AppCategory.Games -> CategoryIcons.Games
    AppCategory.Health -> CategoryIcons.Health
    AppCategory.Home -> CategoryIcons.Home
    AppCategory.Media -> CategoryIcons.Media
    AppCategory.News -> CategoryIcons.News
    AppCategory.Productivity -> CategoryIcons.Productivity
    AppCategory.Shopping -> CategoryIcons.Shopping
    AppCategory.Social -> CategoryIcons.Social
    AppCategory.Tools -> CategoryIcons.Tools
    AppCategory.Travel -> CategoryIcons.Travel
    AppCategory.Other -> CategoryIcons.Other
}

/**
 * A folder's icon: its category's glyph, large and monochrome, with no tile
 * behind it (maintainer's choice of style, 2026-11-19), in the box an app icon
 * takes, so the folder's name lines up with the apps' names.
 */
@Composable
internal fun CategoryIconTile(category: AppCategory, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .testTag(APP_LIST_FOLDER_ICON_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = category.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((sizeDp * CATEGORY_GLYPH_FRACTION).dp),
        )
    }
}

// Nearly the whole box: a Material Symbol already keeps its own margin.
private const val CATEGORY_GLYPH_FRACTION = 0.95f

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

/**
 * The folder an app is filed in: the user's choice if they made one, else the
 * AI's (passed only while AI sorting is on), else its manifest's, else Other.
 */
internal fun effectiveAppFolder(
    app: InstalledApp,
    assignments: Map<String, AppCategory?>,
    aiAssignments: Map<String, AppCategory> = emptyMap(),
): AppCategory? = if (assignments.containsKey(app.id)) {
    assignments[app.id]
} else {
    aiAssignments[app.id] ?: app.declaredCategory ?: AppCategory.Other
}

/** A closed folder as a name-beside row: its category icon, then the folder's name. */
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
        CategoryIconTile(folder.category, sizeDp = iconSizeDp)
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
        // The same 4 dp around the icon an app tile has, so the two line up.
        Box(modifier = Modifier.padding(4.dp)) {
            CategoryIconTile(folder.category, sizeDp = iconSizeDp)
        }
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
    AppListOfferRow(
        title = stringResource(R.string.app_folders_offer_title),
        acceptLabel = stringResource(R.string.app_folders_offer_accept),
        tag = APP_FOLDERS_OFFER_TAG,
        acceptTag = APP_FOLDERS_OFFER_ACCEPT_TAG,
        dismissTag = APP_FOLDERS_OFFER_DISMISS_TAG,
        onAccept = onAccept,
        onDismiss = onDismiss,
    )
}

/**
 * The one-time offer to show apps as a grid, names below, in the same place
 * and style as [AppFoldersOffer]. Either answer retires it for good; the
 * "App list/grid style" setting keeps the choice.
 */
@Composable
internal fun GridLayoutOffer(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AppListOfferRow(
        title = stringResource(R.string.grid_offer_title),
        acceptLabel = stringResource(R.string.grid_offer_accept),
        tag = GRID_OFFER_TAG,
        acceptTag = GRID_OFFER_ACCEPT_TAG,
        dismissTag = GRID_OFFER_DISMISS_TAG,
        onAccept = onAccept,
        onDismiss = onDismiss,
    )
}

/** A one-line question at the top of the app list, with "Not now" and an accept button. */
@Composable
private fun AppListOfferRow(
    title: String,
    acceptLabel: String,
    tag: String,
    acceptTag: String,
    dismissTag: String,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        TextButton(onClick = onDismiss, modifier = Modifier.testTag(dismissTag)) {
            Text(stringResource(R.string.app_folders_offer_dismiss))
        }
        TextButton(onClick = onAccept, modifier = Modifier.testTag(acceptTag)) {
            Text(acceptLabel)
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

/**
 * What AI sorting is doing, in a few words, or null when there's nothing worth
 * saying (it's off, or there was nothing to sort).
 */
@Composable
internal fun aiSortStatusText(progress: AiSortProgress): String? = when (progress.phase) {
    AiSortPhase.Idle -> null
    AiSortPhase.Unavailable -> stringResource(R.string.ai_sort_status_unavailable)
    AiSortPhase.Downloading -> stringResource(R.string.ai_sort_status_downloading)
    AiSortPhase.Sorting -> stringResource(R.string.ai_sort_status_sorting, progress.sorted, progress.total)
    AiSortPhase.Done -> if (progress.total == 0) {
        null
    } else {
        pluralStringResource(R.plurals.ai_sort_status_done, progress.total, progress.total)
    }
    AiSortPhase.Paused -> stringResource(R.string.ai_sort_status_paused)
}

/**
 * Settings' "Sort with on-device AI" switch, with what it's doing below it,
 * and "Re-sort all apps" while it's on.
 */
@Composable
internal fun AiSortSettingsRows(
    isEnabled: Boolean,
    progress: AiSortProgress,
    onEnabledChanged: (Boolean) -> Unit,
    onResort: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_ai_sort_title),
                style = MaterialTheme.typography.titleMedium,
            )
            val status = if (isEnabled) aiSortStatusText(progress) else null
            if (status != null) {
                Text(
                    status,
                    modifier = Modifier.testTag(AI_SORT_STATUS_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onEnabledChanged,
            modifier = Modifier.testTag(AI_SORT_SWITCH_TAG),
        )
    }
    if (isEnabled && progress.phase != AiSortPhase.Unavailable) {
        TextButton(onClick = onResort, modifier = Modifier.testTag(AI_SORT_RESORT_TAG)) {
            Text(stringResource(R.string.settings_ai_sort_resort))
        }
    }
}

/**
 * Below the open Other folder's header: sorting progress while the AI is
 * moving apps out of it, or else the one-time offer to turn AI sorting on.
 * Nothing at all when neither applies.
 */
@Composable
internal fun OtherFolderAiNote(
    progress: AiSortProgress,
    showOffer: Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        progress.phase == AiSortPhase.Sorting || progress.phase == AiSortPhase.Downloading -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .testTag(AI_SORT_PROGRESS_TAG),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (progress.phase == AiSortPhase.Sorting && progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { progress.sorted.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                aiSortStatusText(progress)?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        showOffer -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp)
                .testTag(AI_SORT_OFFER_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.ai_sort_offer_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(AI_SORT_OFFER_DISMISS_TAG)) {
                Text(stringResource(R.string.app_folders_offer_dismiss))
            }
            TextButton(onClick = onAccept, modifier = Modifier.testTag(AI_SORT_OFFER_ACCEPT_TAG)) {
                Text(stringResource(R.string.app_folders_offer_accept))
            }
        }
    }
}
