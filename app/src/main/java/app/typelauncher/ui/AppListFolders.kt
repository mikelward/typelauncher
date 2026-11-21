package app.typelauncher

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal fun AppCategory.labelRes(): Int = when (this) {
    AppCategory.Ai -> R.string.app_folder_ai
    AppCategory.Code -> R.string.app_folder_code
    AppCategory.Finance -> R.string.app_folder_finance
    AppCategory.Food -> R.string.app_folder_food
    AppCategory.Games -> R.string.app_folder_games
    AppCategory.Government -> R.string.app_folder_government
    AppCategory.Health -> R.string.app_folder_health
    AppCategory.Home -> R.string.app_folder_home
    AppCategory.Internet -> R.string.app_folder_internet
    AppCategory.Kids -> R.string.app_folder_kids
    AppCategory.Learning -> R.string.app_folder_learning
    AppCategory.Media -> R.string.app_folder_media
    AppCategory.Music -> R.string.app_folder_music
    AppCategory.News -> R.string.app_folder_news
    AppCategory.Pets -> R.string.app_folder_pets
    AppCategory.Phone -> R.string.app_folder_phone
    AppCategory.Betting -> R.string.app_folder_betting
    AppCategory.Design -> R.string.app_folder_design
    AppCategory.Events -> R.string.app_folder_events
    AppCategory.Jobs -> R.string.app_folder_jobs
    AppCategory.Rewards -> R.string.app_folder_rewards
    AppCategory.Security -> R.string.app_folder_security
    AppCategory.Post -> R.string.app_folder_post
    AppCategory.Photos -> R.string.app_folder_photos
    AppCategory.Office -> R.string.app_folder_office
    AppCategory.Reading -> R.string.app_folder_reading
    AppCategory.Shopping -> R.string.app_folder_shopping
    AppCategory.Social -> R.string.app_folder_social
    AppCategory.Sports -> R.string.app_folder_sports
    AppCategory.Tools -> R.string.app_folder_tools
    AppCategory.Transit -> R.string.app_folder_transit
    AppCategory.Car -> R.string.app_folder_car
    AppCategory.Charity -> R.string.app_folder_charity
    AppCategory.Cycling -> R.string.app_folder_cycling
    AppCategory.Hotels -> R.string.app_folder_hotels
    AppCategory.Maps -> R.string.app_folder_maps
    AppCategory.Travel -> R.string.app_folder_travel
    AppCategory.Weather -> R.string.app_folder_weather
    AppCategory.Other -> R.string.app_folder_other
}

internal fun FolderIcon.vector(): ImageVector = when (this) {
    FolderIcon.Folder -> CategoryIcons.Folder
    FolderIcon.Star -> CategoryIcons.Star
    FolderIcon.Paid -> CategoryIcons.Finance
    FolderIcon.SportsEsports -> CategoryIcons.Games
    FolderIcon.Favorite -> CategoryIcons.Health
    FolderIcon.Home -> CategoryIcons.Home
    FolderIcon.School -> CategoryIcons.Learning
    FolderIcon.PlayCircle -> CategoryIcons.Media
    FolderIcon.Newspaper -> CategoryIcons.News
    FolderIcon.Work -> CategoryIcons.Productivity
    FolderIcon.Business -> CategoryIcons.Business
    FolderIcon.ShoppingBag -> CategoryIcons.Shopping
    FolderIcon.Chat -> CategoryIcons.Social
    FolderIcon.Build -> CategoryIcons.Tools
    FolderIcon.Travel -> CategoryIcons.Travel
    FolderIcon.DirectionsBus -> CategoryIcons.DirectionsBus
    FolderIcon.Train -> CategoryIcons.Train
    FolderIcon.DirectionsCar -> CategoryIcons.DirectionsCar
    FolderIcon.PedalBike -> CategoryIcons.PedalBike
    FolderIcon.Hotel -> CategoryIcons.Hotel
    FolderIcon.Map -> CategoryIcons.Map
    FolderIcon.VolunteerActivism -> CategoryIcons.VolunteerActivism
    FolderIcon.Flight -> CategoryIcons.Flight
    FolderIcon.Restaurant -> CategoryIcons.Restaurant
    FolderIcon.Language -> CategoryIcons.Language
    FolderIcon.Translate -> CategoryIcons.Translate
    FolderIcon.SportsSoccer -> CategoryIcons.SportsSoccer
    FolderIcon.FitnessCenter -> CategoryIcons.FitnessCenter
    FolderIcon.Apartment -> CategoryIcons.Apartment
    FolderIcon.Code -> CategoryIcons.Code
    FolderIcon.AutoAwesome -> CategoryIcons.AutoAwesome
    FolderIcon.Palette -> CategoryIcons.Palette
    FolderIcon.FamilyRestroom -> CategoryIcons.FamilyRestroom
    FolderIcon.ChildCare -> CategoryIcons.ChildCare
    FolderIcon.Shield -> CategoryIcons.Shield
    FolderIcon.Lock -> CategoryIcons.Lock
    FolderIcon.Call -> CategoryIcons.Call
    FolderIcon.Casino -> CategoryIcons.Casino
    FolderIcon.ConfirmationNumber -> CategoryIcons.ConfirmationNumber
    FolderIcon.Loyalty -> CategoryIcons.Loyalty
    FolderIcon.MarkunreadMailbox -> CategoryIcons.MarkunreadMailbox
    FolderIcon.Smartphone -> CategoryIcons.Smartphone
    FolderIcon.AccountBalance -> CategoryIcons.AccountBalance
    FolderIcon.Badge -> CategoryIcons.Badge
    FolderIcon.Flag -> CategoryIcons.Flag
    FolderIcon.MenuBook -> CategoryIcons.MenuBook
    FolderIcon.PartlyCloudyDay -> CategoryIcons.PartlyCloudyDay
    FolderIcon.Eco -> CategoryIcons.Eco
    FolderIcon.MusicNote -> CategoryIcons.MusicNote
    FolderIcon.PhotoCamera -> CategoryIcons.PhotoCamera
    FolderIcon.Pets -> CategoryIcons.Pets
    FolderIcon.MoreHoriz -> CategoryIcons.Other
}

/** What [FolderIcon] shows, in words, for TalkBack in the icon picker. */
internal fun FolderIcon.descriptionRes(): Int = when (this) {
    FolderIcon.Folder -> R.string.folder_icon_folder
    FolderIcon.Star -> R.string.folder_icon_star
    FolderIcon.Paid -> R.string.folder_icon_paid
    FolderIcon.SportsEsports -> R.string.folder_icon_sports_esports
    FolderIcon.Favorite -> R.string.folder_icon_favorite
    FolderIcon.Home -> R.string.folder_icon_home
    FolderIcon.School -> R.string.folder_icon_school
    FolderIcon.PlayCircle -> R.string.folder_icon_play_circle
    FolderIcon.Newspaper -> R.string.folder_icon_newspaper
    FolderIcon.Work -> R.string.folder_icon_work
    FolderIcon.Business -> R.string.folder_icon_business
    FolderIcon.ShoppingBag -> R.string.folder_icon_shopping_bag
    FolderIcon.Chat -> R.string.folder_icon_chat
    FolderIcon.Build -> R.string.folder_icon_build
    FolderIcon.Travel -> R.string.folder_icon_travel
    FolderIcon.DirectionsBus -> R.string.folder_icon_directions_bus
    FolderIcon.Train -> R.string.folder_icon_train
    FolderIcon.DirectionsCar -> R.string.folder_icon_directions_car
    FolderIcon.PedalBike -> R.string.folder_icon_pedal_bike
    FolderIcon.Hotel -> R.string.folder_icon_hotel
    FolderIcon.Map -> R.string.folder_icon_map
    FolderIcon.VolunteerActivism -> R.string.folder_icon_volunteer_activism
    FolderIcon.Flight -> R.string.folder_icon_flight
    FolderIcon.Restaurant -> R.string.folder_icon_restaurant
    FolderIcon.Language -> R.string.folder_icon_language
    FolderIcon.Translate -> R.string.folder_icon_translate
    FolderIcon.SportsSoccer -> R.string.folder_icon_sports_soccer
    FolderIcon.FitnessCenter -> R.string.folder_icon_fitness_center
    FolderIcon.Apartment -> R.string.folder_icon_apartment
    FolderIcon.Code -> R.string.folder_icon_code
    FolderIcon.AutoAwesome -> R.string.folder_icon_auto_awesome
    FolderIcon.Palette -> R.string.folder_icon_palette
    FolderIcon.FamilyRestroom -> R.string.folder_icon_family_restroom
    FolderIcon.ChildCare -> R.string.folder_icon_child_care
    FolderIcon.Shield -> R.string.folder_icon_shield
    FolderIcon.Lock -> R.string.folder_icon_lock
    FolderIcon.Call -> R.string.folder_icon_call
    FolderIcon.Casino -> R.string.folder_icon_casino
    FolderIcon.ConfirmationNumber -> R.string.folder_icon_confirmation_number
    FolderIcon.Loyalty -> R.string.folder_icon_loyalty
    FolderIcon.MarkunreadMailbox -> R.string.folder_icon_markunread_mailbox
    FolderIcon.Smartphone -> R.string.folder_icon_smartphone
    FolderIcon.AccountBalance -> R.string.folder_icon_account_balance
    FolderIcon.Badge -> R.string.folder_icon_badge
    FolderIcon.Flag -> R.string.folder_icon_flag
    FolderIcon.MenuBook -> R.string.folder_icon_menu_book
    FolderIcon.PartlyCloudyDay -> R.string.folder_icon_partly_cloudy_day
    FolderIcon.Eco -> R.string.folder_icon_eco
    FolderIcon.MusicNote -> R.string.folder_icon_music_note
    FolderIcon.PhotoCamera -> R.string.folder_icon_photo_camera
    FolderIcon.Pets -> R.string.folder_icon_pets
    FolderIcon.MoreHoriz -> R.string.folder_icon_more_horiz
}

/** A folder's name: a built-in folder's in the user's language, else the name the user gave it. */
@Composable
internal fun folderLabel(folder: AppFolderDef): String =
    folder.category?.let { stringResource(it.labelRes()) } ?: folder.name

/**
 * A folder's icon: its category's glyph, large and monochrome, with no tile
 * behind it (maintainer's choice of style, 2026-11-19), in the box an app icon
 * takes, so the folder's name lines up with the apps' names.
 */
@Composable
internal fun CategoryIconTile(icon: FolderIcon, sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .testTag(APP_LIST_FOLDER_ICON_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon.vector(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((sizeDp * CATEGORY_GLYPH_FRACTION).dp),
        )
    }
}

// Nearly the whole box: a Material Symbol already keeps its own margin.
private const val CATEGORY_GLYPH_FRACTION = 0.95f

/**
 * Lets the app list's and dock's long-press menus offer "Move to folder", and
 * the list's folders offer to be edited or deleted. Provided only around the
 * app list and the dock while folders are on, so recents' menus don't offer it.
 */
@Stable
internal class AppFolderMenu(
    /** The folders in use, in list order. */
    val folders: List<AppFolderDef>,
    /** The key of the folder [InstalledApp] is filed in now, ignoring the minimum folder size. */
    val folderOf: (InstalledApp) -> String?,
    val onMove: (InstalledApp, String?) -> Unit,
    /** Makes a folder and moves the app into it. */
    val onCreate: (InstalledApp, String, FolderIcon) -> Unit,
    val onEdit: (AppFolderDef, String, FolderIcon) -> Unit,
    val onDelete: (AppFolderDef) -> Unit,
    /** Files the first folder's apps into the second, then deletes the first. */
    val onMerge: (AppFolderDef, AppFolderDef) -> Unit,
)

internal val LocalAppFolderMenu = staticCompositionLocalOf<AppFolderMenu?> { null }

/** A closed folder as a name-beside row: its category icon, then the folder's name. */
@Composable
internal fun AppListFolderRow(
    folder: AppListFolder,
    // The folder icon's size; the same as an app row's icon so names line up.
    iconSizeDp: Int,
    onOpen: (AppListFolder) -> Unit,
) {
    val label = folderLabel(folder.folder)
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folderClickable(folder, onOpen = onOpen, onLongPress = { menuOpen = true })
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .testTag("$APP_LIST_FOLDER_TAG:${folder.folder.key}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CategoryIconTile(folder.folder.icon, sizeDp = iconSizeDp)
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        AppListFolderActions(folder.folder, expanded = menuOpen, onDismiss = { menuOpen = false })
    }
}

/**
 * A tap opens the folder; a long press opens its menu while there's one to
 * show (see [AppListFolderActions]).
 */
@Composable
private fun Modifier.folderClickable(
    folder: AppListFolder,
    onOpen: (AppListFolder) -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val hasMenu = LocalAppFolderMenu.current != null && !folder.folder.isOther
    return combinedClickable(
        role = Role.Button,
        onClick = { onOpen(folder) },
        onLongClick = if (hasMenu) onLongPress else null,
    )
}

/**
 * A folder's long-press menu: "Edit folder" for one the user made, then
 * "Merge into…" and "Delete folder" for any but Other, each through its
 * dialog.
 */
@Composable
internal fun AppListFolderActions(folder: AppFolderDef, expanded: Boolean, onDismiss: () -> Unit) {
    val menu = LocalAppFolderMenu.current ?: return
    var editing by rememberSaveable(folder.key) { mutableStateOf(false) }
    var deleting by rememberSaveable(folder.key) { mutableStateOf(false) }
    var merging by rememberSaveable(folder.key) { mutableStateOf(false) }
    LauncherDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (folder.category == null) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.app_folder_menu_edit)) },
                modifier = Modifier.testTag("$APP_FOLDER_EDIT_ACTION_TAG:${folder.key}"),
                onClick = {
                    onDismiss()
                    editing = true
                },
            )
        }
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_folder_menu_merge)) },
            modifier = Modifier.testTag("$APP_FOLDER_MERGE_ACTION_TAG:${folder.key}"),
            onClick = {
                onDismiss()
                merging = true
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_folder_menu_delete)) },
            modifier = Modifier.testTag("$APP_FOLDER_DELETE_ACTION_TAG:${folder.key}"),
            onClick = {
                onDismiss()
                deleting = true
            },
        )
    }
    if (editing) {
        AppFolderEditDialog(
            initial = folder,
            onSave = { name, icon ->
                menu.onEdit(folder, name, icon)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
    if (merging) {
        MergeAppFolderDialog(
            name = folderLabel(folder),
            // Not into itself, nor into Other: that would pin its apps there,
            // out of the AI's reach, when deleting already sorts them again.
            targets = menu.folders.filter { it.key != folder.key && !it.isOther },
            onPick = { target ->
                menu.onMerge(folder, target)
                merging = false
            },
            onDismiss = { merging = false },
        )
    }
    if (deleting) {
        DeleteAppFolderDialog(
            name = folderLabel(folder),
            onConfirm = {
                menu.onDelete(folder)
                deleting = false
            },
            onDismiss = { deleting = false },
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
    val label = folderLabel(folder.folder)
    var menuOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .folderClickable(folder, onOpen = onOpen, onLongPress = { menuOpen = true })
            .semantics { contentDescription = label }
            .testTag("$APP_LIST_FOLDER_TAG:${folder.folder.key}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The same 4 dp around the icon an app tile has, so the two line up.
        Box(modifier = Modifier.padding(4.dp)) {
            CategoryIconTile(folder.folder.icon, sizeDp = iconSizeDp)
            AppListFolderActions(folder.folder, expanded = menuOpen, onDismiss = { menuOpen = false })
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

/**
 * The open folder's title, full width above its apps. The close (✕) item is
 * at the other end of the list, as in dock folders; see
 * [AppListFolderCloseRow].
 */
@Composable
internal fun AppListFolderHeader(folder: AppListFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp)
            .testTag(APP_LIST_FOLDER_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            folderLabel(folder.folder),
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The ✕ on its circle, as a dock folder's close tile draws it, at [sizeDp]. */
@Composable
private fun FolderCloseGlyph(sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LauncherIcons.Clear,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size((sizeDp * CLOSE_GLYPH_FRACTION).dp),
        )
    }
}

// The dock folder close tile's proportion.
private const val CLOSE_GLYPH_FRACTION = 0.6f

/** The open folder's close item in a name-beside list: an app row's shape, ✕ in the icon's place. */
@Composable
internal fun AppListFolderCloseRow(onClose: () -> Unit) {
    val description = stringResource(R.string.dock_folder_close_description)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = description }
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .testTag(APP_LIST_FOLDER_CLOSE_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderCloseGlyph(sizeDp = APP_LIST_ROW_ICON_SIZE_DP)
        Text(
            stringResource(R.string.app_folder_close),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

/** The open folder's close item in a grid: an app tile's footprint, ✕ in the icon's place. */
@Composable
internal fun AppListFolderCloseTile(iconSizeDp: Int, showLabel: Boolean, onClose: () -> Unit) {
    val description = stringResource(R.string.dock_folder_close_description)
    Column(
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClose)
            .semantics { contentDescription = description }
            .padding(4.dp)
            .testTag(APP_LIST_FOLDER_CLOSE_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FolderCloseGlyph(sizeDp = iconSizeDp)
        if (showLabel) {
            Text(
                stringResource(R.string.app_folder_close),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )
        }
    }
}

// An app row's icon size (APP_ROW_ICON_SIZE_DP in HomeScreen.kt).
private const val APP_LIST_ROW_ICON_SIZE_DP = 40

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

/**
 * Picks the folder for one app, or none, or makes a new one for it. Picking
 * closes the dialog; "New folder" opens [AppFolderEditDialog] in its place.
 */
@Composable
internal fun AppFolderPickerDialog(
    folders: List<AppFolderDef>,
    current: String?,
    onPick: (String?) -> Unit,
    onCreate: (String, FolderIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        AppFolderEditDialog(initial = null, onSave = onCreate, onDismiss = onDismiss)
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_menu_move_to_folder)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                FolderPickerOption(
                    label = stringResource(R.string.app_folder_picker_none),
                    selected = current == null,
                    tag = "$APP_FOLDER_PICKER_OPTION_TAG:none",
                    onClick = { onPick(null) },
                )
                for (folder in folders) {
                    FolderPickerOption(
                        label = folderLabel(folder),
                        selected = folder.key == current,
                        tag = "$APP_FOLDER_PICKER_OPTION_TAG:${folder.key}",
                        onClick = { onPick(folder.key) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { creating = true }, modifier = Modifier.testTag(APP_FOLDER_PICKER_NEW_TAG)) {
                Text(stringResource(R.string.app_folder_picker_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.app_folder_picker_cancel))
            }
        },
    )
}

@Composable
private fun FolderPickerOption(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Names a new folder and picks its icon, or edits one the user made
 * ([initial]). A `Dialog` around [AppFolderEditDialogContent] rather than an
 * `AlertDialog`, for the reason [EditAppDialog] gives.
 */
@Composable
internal fun AppFolderEditDialog(
    initial: AppFolderDef?,
    onSave: (String, FolderIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            AppFolderEditDialogContent(initial = initial, onSave = onSave, onDismiss = onDismiss)
        }
    }
}

/** [AppFolderEditDialog]'s body, apart so a test can compose it without the dialog window. */
@Composable
internal fun AppFolderEditDialogContent(
    initial: AppFolderDef?,
    onSave: (String, FolderIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(initial?.key) { mutableStateOf(initial?.name.orEmpty()) }
    var icon by rememberSaveable(initial?.key) { mutableStateOf(initial?.icon ?: FolderIcon.Folder) }
    val canSave = name.isNotBlank()
    Column(
        modifier = Modifier
            .padding(24.dp)
            .testTag(APP_FOLDER_EDIT_DIALOG_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(if (initial == null) R.string.app_folder_new_title else R.string.app_folder_edit_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        TextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text(stringResource(R.string.app_folder_name_label)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(name, icon) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(APP_FOLDER_NAME_FIELD_TAG),
        )
        // Scrolls on its own, below the name, and shrinks to what's left once
        // the buttons have their room, so a short screen (landscape, or the
        // keyboard up) keeps the name and buttons in view. Opens on the
        // folder's own icon.
        val initialIcon = remember { FolderIcon.entries.indexOf(icon) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(48.dp),
            state = rememberLazyGridState(initialFirstVisibleItemIndex = initialIcon),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 208.dp)
                .selectableGroup()
                .testTag(APP_FOLDER_ICON_GRID_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(FolderIcon.entries, key = { it.storageKey }) { option ->
                val selected = option == icon
                val description = stringResource(option.descriptionRes())
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            CircleShape,
                        )
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { icon = option })
                        .semantics { contentDescription = description }
                        .testTag("$APP_FOLDER_ICON_OPTION_TAG:${option.storageKey}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = option.vector(),
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_folder_picker_cancel)) }
            TextButton(
                onClick = { onSave(name, icon) },
                enabled = canSave,
                modifier = Modifier.testTag(APP_FOLDER_SAVE_TAG),
            ) {
                Text(stringResource(R.string.app_folder_save))
            }
        }
    }
}

/** Picks the folder to merge [name] into. Picking merges and closes the dialog. */
@Composable
internal fun MergeAppFolderDialog(
    name: String,
    targets: List<AppFolderDef>,
    onPick: (AppFolderDef) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_folder_merge_title, name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for (target in targets) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button, onClick = { onPick(target) })
                            .padding(vertical = 8.dp)
                            .testTag("$APP_FOLDER_MERGE_OPTION_TAG:${target.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = target.icon.vector(),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Text(folderLabel(target), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_folder_picker_cancel)) }
        },
    )
}

/**
 * Settings' "Edit folders": every folder in use, each opening the menu a
 * long-pressed folder has (edit, merge, delete; Other has none), and "New
 * folder". Needs [LocalAppFolderMenu] for those actions.
 */
@Composable
internal fun EditAppFoldersDialog(
    folders: List<AppFolderDef>,
    onCreate: (String, FolderIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        AppFolderEditDialog(
            initial = null,
            onSave = { name, icon ->
                onCreate(name, icon)
                creating = false
            },
            onDismiss = { creating = false },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_folders_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .testTag(APP_FOLDERS_EDIT_DIALOG_TAG),
            ) {
                for (folder in folders) {
                    var menuOpen by remember(folder.key) { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (folder.isOther) {
                                        Modifier
                                    } else {
                                        Modifier.clickable(role = Role.Button, onClick = { menuOpen = true })
                                    },
                                )
                                .padding(vertical = 8.dp)
                                .testTag("$APP_FOLDERS_EDIT_ROW_TAG:${folder.key}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = folder.icon.vector(),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                            Text(folderLabel(folder), style = MaterialTheme.typography.bodyLarge)
                        }
                        if (!folder.isOther) {
                            AppListFolderActions(folder, expanded = menuOpen, onDismiss = { menuOpen = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_folders_dialog_done)) }
        },
        dismissButton = {
            TextButton(onClick = { creating = true }, modifier = Modifier.testTag(APP_FOLDER_PICKER_NEW_TAG)) {
                Text(stringResource(R.string.app_folder_picker_new))
            }
        },
    )
}

/** Asks before deleting a folder; its apps stay installed and are sorted again. */
@Composable
internal fun DeleteAppFolderDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_folder_delete_title, name)) },
        text = { Text(stringResource(R.string.app_folder_delete_text)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(APP_FOLDER_DELETE_CONFIRM_TAG)) {
                Text(stringResource(R.string.app_folder_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_folder_picker_cancel)) }
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
 * Below an open folder's header: sorting progress while the AI is sorting,
 * or else the offer to turn AI sorting on, in every folder until answered.
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

/** What "Sort using Google Play" can do, from the folder note and Settings. */
@Immutable
internal class PlaySortActions(
    // The ticked apps to look up, and the unticked ones, which are never looked up on their own.
    val onStart: (chosen: List<String>, unticked: List<String>) -> Unit = { _, _ -> },
    val onDismissOffer: () -> Unit = {},
    val onAcknowledge: () -> Unit = {},
    val onAutoChanged: (Boolean) -> Unit = {},
    val onResort: () -> Unit = {},
    // "Check guessed folders": Not now, Check, and closing its sheet.
    val onDismissGuessCheck: () -> Unit = {},
    val onStartGuessCheck: () -> Unit = {},
    val onEndGuessCheck: () -> Unit = {},
)

/**
 * Atop the list once on-device sorting is done (maintainer, 2026-11-21): the
 * offer to check the folders of the apps placed only by a guess. **Check**
 * opens [PlaySortReviewSheet] for them; either answer retires the offer for
 * these apps.
 */
@Composable
internal fun GuessCheckNote(count: Int, onCheck: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp)
            .testTag(GUESS_CHECK_OFFER_TAG),
    ) {
        Text(
            pluralStringResource(R.plurals.guess_check_offer_title, count, count),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.guess_check_offer_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(GUESS_CHECK_DISMISS_TAG)) {
                Text(stringResource(R.string.app_folders_offer_dismiss))
            }
            TextButton(onClick = onCheck, modifier = Modifier.testTag(GUESS_CHECK_ACCEPT_TAG)) {
                Text(stringResource(R.string.guess_check_accept))
            }
        }
    }
}

/**
 * At the top of Other: the offer to sort the apps our rules don't place using
 * Google Play, its progress, or how it went. The offer opens
 * [PlaySortSheet] to choose the apps; nothing is looked up before that.
 */
@Composable
internal fun PlaySortNote(state: PlaySortState, actions: PlaySortActions, onReview: (() -> Unit)? = null) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    when {
        state.phase == PlaySortPhase.Sorting || state.phase == PlaySortPhase.Paused -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .testTag(PLAY_SORT_PROGRESS_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                if (state.phase == PlaySortPhase.Paused) {
                    stringResource(R.string.play_sort_paused)
                } else {
                    stringResource(R.string.play_sort_progress, state.done, state.total)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        state.phase == PlaySortPhase.Done -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp)
                .testTag(PLAY_SORT_RESULT_TAG),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pluralStringResource(R.plurals.play_sort_done, state.sorted, state.sorted),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (state.unsorted > 0) {
                    Text(
                        pluralStringResource(R.plurals.play_sort_done_unsorted, state.unsorted, state.unsorted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.reviewed.isNotEmpty() && onReview != null) {
                TextButton(onClick = onReview, modifier = Modifier.testTag(PLAY_SORT_REVIEW_TAG)) {
                    Text(stringResource(R.string.play_sort_review))
                }
            }
            TextButton(onClick = actions.onAcknowledge, modifier = Modifier.testTag(PLAY_SORT_DONE_TAG)) {
                Text(stringResource(R.string.play_sort_done_button))
            }
        }
        state.showOffer -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, top = 4.dp)
                .testTag(PLAY_SORT_OFFER_TAG),
        ) {
            Text(
                pluralStringResource(R.plurals.play_sort_offer_title, state.offered.size, state.offered.size),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                stringResource(R.string.play_sort_offer_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = actions.onDismissOffer, modifier = Modifier.testTag(PLAY_SORT_OFFER_DISMISS_TAG)) {
                    Text(stringResource(R.string.app_folders_offer_dismiss))
                }
                TextButton(onClick = { choosing = true }, modifier = Modifier.testTag(PLAY_SORT_OFFER_ACCEPT_TAG)) {
                    Text(stringResource(R.string.play_sort_offer_accept))
                }
            }
        }
    }
    if (choosing) {
        PlaySortSheet(
            candidates = state.candidates,
            onSort = { chosen, unticked ->
                choosing = false
                actions.onStart(chosen, unticked)
            },
            onDismiss = { choosing = false },
        )
    }
}

/**
 * The apps "Sort using Google Play" would look up, each ticked, so any can be
 * left out. Apps Google Play didn't install are listed, greyed out and never
 * looked up, so the list says plainly what stays on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaySortSheet(
    candidates: List<PlaySortCandidate>,
    onSort: (chosen: List<String>, unticked: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Apps the user kept out before start unticked.
    var unticked by rememberSaveable {
        mutableStateOf(candidates.filter { it.declined }.map { it.app.packageName }.toSet())
    }
    val chosen = candidates.filter { it.fromPlay && it.app.packageName !in unticked }.map { it.app.packageName }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .testTag(PLAY_SORT_SHEET_TAG),
        ) {
            Text(stringResource(R.string.play_sort_sheet_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.play_sort_sheet_body),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(candidates, key = { it.app.packageName }) { candidate ->
                    val packageName = candidate.app.packageName
                    val ticked = candidate.fromPlay && packageName !in unticked
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // Every row reads as a checkbox; one Google Play
                            // didn't install is a disabled, unticked one.
                            .toggleable(value = ticked, enabled = candidate.fromPlay, role = Role.Checkbox) { now ->
                                unticked = if (now) unticked - packageName else unticked + packageName
                            }
                            .padding(vertical = 4.dp)
                            .testTag("$PLAY_SORT_ROW_TAG:$packageName"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = ticked, onCheckedChange = null, enabled = candidate.fromPlay)
                        Box(modifier = Modifier.alpha(if (candidate.fromPlay) 1f else DISABLED_ALPHA)) {
                            AppIcon(candidate.app, size = 32.dp)
                        }
                        Text(
                            candidate.app.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (candidate.fromPlay) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!candidate.fromPlay) {
                            Text(
                                stringResource(R.string.play_sort_not_from_play),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.app_folder_picker_cancel)) }
                Button(
                    onClick = {
                        val left = candidates.filter { it.fromPlay && it.app.packageName in unticked }
                        onSort(chosen, left.map { it.app.packageName })
                    },
                    enabled = chosen.isNotEmpty(),
                    modifier = Modifier.testTag(PLAY_SORT_SHEET_CONFIRM_TAG),
                ) {
                    Text(pluralStringResource(R.plurals.play_sort_sheet_confirm, chosen.size, chosen.size))
                }
            }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

/** Settings rows for "Sort using Google Play", shown once it has been used. */
@Composable
internal fun PlaySortSettingsRows(state: PlaySortState, actions: PlaySortActions) {
    if (!state.hasUsed) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_play_sort_auto_title),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        Switch(
            checked = state.isAutoEnabled,
            onCheckedChange = actions.onAutoChanged,
            modifier = Modifier.testTag(PLAY_SORT_AUTO_SWITCH_TAG),
        )
    }
    TextButton(onClick = actions.onResort, modifier = Modifier.testTag(PLAY_SORT_RESORT_TAG)) {
        Text(stringResource(R.string.settings_play_sort_resort))
    }
}


/**
 * What Review needs from the app list: the folders, where each app is filed
 * now, the folder its Google Play category suggests, and how to move it.
 */
@Immutable
internal class PlaySortReview(
    val folders: List<AppFolderDef>,
    val folderOf: (InstalledApp) -> String?,
    val playFolderOf: (InstalledApp) -> String?,
    val onMove: (InstalledApp, String?) -> Unit,
    val onCreate: (InstalledApp, String, FolderIcon) -> Unit,
)

/**
 * The apps the last Google Play run looked up, each with its folder in a
 * dropdown (maintainer, 2026-11-21): the ones it placed, then the ones it
 * couldn't, in Other. Choosing a folder files the app there for good, as
 * "Move to folder" does. The caller hosts it above the note that opens it:
 * a move can empty Other, which closes along with its note (Codex on PR #888).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaySortReviewSheet(
    apps: List<InstalledApp>,
    review: PlaySortReview,
    onDismiss: () -> Unit,
    @StringRes title: Int = R.string.play_sort_review_title,
    // Headed groups, in order; by default those Google Play placed, then those still in Other.
    groups: List<Pair<Int, List<InstalledApp>>>? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val other = AppCategory.Other.storageKey
    val headed = groups ?: apps.partition { review.folderOf(it) == other }.let { (unsorted, sorted) ->
        listOf(R.string.play_sort_review_sorted to sorted, R.string.play_sort_review_unsorted to unsorted)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .testTag(PLAY_SORT_REVIEW_SHEET_TAG),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.play_sort_review_body),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                for ((heading, group) in headed) {
                    if (group.isEmpty()) continue
                    item(key = "heading:$heading") {
                        Text(
                            stringResource(heading),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(group, key = { it.id }) { app -> PlaySortReviewRow(app, review) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) {
                Text(stringResource(R.string.play_sort_done_button))
            }
        }
    }
}

@Composable
private fun PlaySortReviewRow(app: InstalledApp, review: PlaySortReview) {
    val current = review.folderOf(app)
    val byKey = review.folders.associateBy { it.key }
    var menuOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("$PLAY_SORT_REVIEW_ROW_TAG:${app.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app, size = 32.dp)
        Text(
            app.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                contentPadding = PaddingValues(start = 12.dp, end = 4.dp),
                modifier = Modifier.testTag("$PLAY_SORT_REVIEW_FOLDER_TAG:${app.packageName}"),
            ) {
                val folder = current?.let(byKey::get)
                if (folder != null) {
                    Icon(folder.icon.vector(), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    folder?.let { folderLabel(it) } ?: stringResource(R.string.app_folder_picker_none),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(LauncherIcons.ArrowDropDown, contentDescription = null)
            }
            LauncherDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                // Likely folders first: Google Play's, then where it is now.
                val likely = listOfNotNull(review.playFolderOf(app), current).distinct().mapNotNull(byKey::get)
                for (folder in likely) {
                    DropdownMenuItem(
                        text = { LauncherMenuItemText(folderLabel(folder)) },
                        leadingIcon = { Icon(folder.icon.vector(), contentDescription = null) },
                        modifier = Modifier.testTag("$PLAY_SORT_REVIEW_OPTION_TAG:${folder.key}"),
                        onClick = {
                            menuOpen = false
                            review.onMove(app, folder.key)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { LauncherMenuItemText(stringResource(R.string.play_sort_review_all_folders)) },
                    modifier = Modifier.testTag("$PLAY_SORT_REVIEW_OPTION_TAG:all"),
                    onClick = {
                        menuOpen = false
                        picking = true
                    },
                )
                DropdownMenuItem(
                    text = { LauncherMenuItemText(stringResource(R.string.app_folder_picker_none)) },
                    modifier = Modifier.testTag("$PLAY_SORT_REVIEW_OPTION_TAG:none"),
                    onClick = {
                        menuOpen = false
                        review.onMove(app, null)
                    },
                )
            }
        }
    }
    if (picking) {
        AppFolderPickerDialog(
            folders = review.folders,
            current = current,
            onPick = { key ->
                picking = false
                review.onMove(app, key)
            },
            onCreate = { name, icon ->
                picking = false
                review.onCreate(app, name, icon)
            },
            onDismiss = { picking = false },
        )
    }
}
