package app.typelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The in-list contact quick-actions surface: rendered in the app list's slot in
 * place of the app results while a contact is open, so acting on the person
 * found stays in the same keyboard-driven list the launcher uses everywhere. A
 * contact header, then the current step's rows — the channel list (Phone,
 * Message, the contact's installed-app actions, Email) plus "Open contact" at
 * step one, or a drilled-into channel's actions with the default number on top
 * at step two. The rows are already filtered by the live [query] and the step is
 * driven by the ViewModel's [selectedChannelId], so typing filters the rows and
 * Enter fires the top one exactly like the app list. The first row carries the
 * active-row highlight the app list gives its top match — unconditionally, not
 * only while typing, because Enter fires the top row here even on a blank query
 * (see `launchActiveApp`), unlike the blank-query app list where Enter opens
 * settings instead. A step-two phone row keeps the long-press Default /
 * Undefault menu.
 */
@Composable
internal fun ContactActionsCard(
    actions: ContactActions,
    selectedChannelId: String?,
    query: String,
    onRowSelected: (ContactActionRow) -> Unit,
    onSetNumberDefault: (dataId: Long, makeDefault: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedChannel = selectedChannelId?.let { id -> actions.channels.firstOrNull { it.id == id } }
    val rows = actions.visibleRows(
        selectedChannelId = selectedChannelId,
        query = query,
        openContactLabel = stringResource(R.string.contact_actions_open_contact),
    )
    // Scrollable: a contact with many channels or numbers — or a large
    // font/display size — can make the list taller than the slot, and without
    // scrolling the lower rows (including "Open contact") would clip out of reach.
    val scrollState = rememberScrollState()
    // Snap back to the top whenever the filter or the step changes, so a card the
    // user scrolled down isn't left offset away from the first row — which Enter
    // now fires — mirroring the app list's query scroll-reset.
    LaunchedEffect(selectedChannelId, query) { scrollState.scrollTo(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .testTag(CONTACT_ACTIONS_SHEET_TAG)
            .padding(vertical = 8.dp),
    ) {
        ContactActionsHeader(
            contact = actions.contact,
            channel = selectedChannel,
            onBack = onBack,
        )
        rows.forEachIndexed { index, row ->
            // The first row is what Enter fires (`launchActiveApp` →
            // `currentContactRows(state).firstOrNull()`), so it takes the
            // active-row highlight.
            val isActive = index == 0
            // Key by identity (channel id / Data row / the open-contact marker) so
            // a row's transient state — a step-two number's open Default menu —
            // follows its content when the query re-filters or a default write
            // re-sorts the list, rather than staying positional and acting on
            // whatever row slid into the slot.
            key(contactRowKey(row)) {
                when (row) {
                    is ContactActionRow.Channel -> ContactChannelRow(
                        channel = row.channel,
                        isActive = isActive,
                        onClick = { onRowSelected(row) },
                    )
                    is ContactActionRow.Action -> ContactActionItemRow(
                        glyph = row.channel.glyph,
                        iconPackageName = row.channel.iconPackageName,
                        action = row.action,
                        isActive = isActive,
                        onClick = { onRowSelected(row) },
                        onSetNumberDefault = onSetNumberDefault,
                    )
                    is ContactActionRow.OpenContact -> ContactSheetRow(
                        glyph = null,
                        iconPackageName = null,
                        title = row.label,
                        subtitle = null,
                        trailingChevron = false,
                        isActive = isActive,
                        modifier = Modifier.testTag(CONTACT_ACTIONS_OPEN_CONTACT_TAG),
                        onClick = { onRowSelected(row) },
                    )
                }
            }
        }
    }
}

/** Stable identity for a [ContactActionRow], so Compose keeps per-row state with its content across re-filters. */
private fun contactRowKey(row: ContactActionRow): Any = when (row) {
    is ContactActionRow.Channel -> "channel:${row.channel.id}"
    is ContactActionRow.Action -> "action:${row.action.dataId ?: row.action.label}"
    is ContactActionRow.OpenContact -> "open-contact"
}

@Composable
private fun ContactActionsHeader(
    contact: ContactResult,
    channel: ContactChannel?,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (channel != null) {
            // Second step: a back affordance replaces the avatar so the header
            // reads as "inside <channel>" and returns to the channel list.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .testTag(CONTACT_ACTIONS_BACK_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.contact_actions_choose_number),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            ContactAvatar(contact)
        }
        Column {
            Text(
                text = channel?.label ?: contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (channel != null) {
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A channel row (step one): app-or-glyph icon, label, and a chevron when the channel expands. */
@Composable
private fun ContactChannelRow(channel: ContactChannel, isActive: Boolean, onClick: () -> Unit) {
    ContactSheetRow(
        glyph = channel.glyph,
        iconPackageName = channel.iconPackageName,
        title = channel.label,
        subtitle = channel.actions.singleOrNull()?.detail,
        trailingChevron = channel.actions.size > 1,
        isActive = isActive,
        modifier = Modifier.testTag("$CONTACT_ACTIONS_CHANNEL_TAG:${channel.id}"),
        onClick = onClick,
    )
}

/**
 * One tappable row of the card: a 40dp leading visual (an installed app's icon
 * when [iconPackageName] is set, else the built-in [icon] glyph), a title with
 * optional [subtitle], and an optional trailing chevron. Geometry mirrors the
 * search result rows (40dp visual, 12dp gap, 8dp-rounded highlight) so the card
 * reads as part of the same launcher.
 */
/**
 * A step-two action row (a number, or an app's action). When the action is a
 * contact phone row ([ContactAction.dataId] set) it gets a long-press menu to
 * set or clear it as the default to call/text, mirroring the app-row long-press
 * affordance; other actions are a plain tappable row.
 */
@Composable
private fun ContactActionItemRow(
    glyph: ContactChannelGlyph?,
    iconPackageName: String?,
    action: ContactAction,
    isActive: Boolean,
    onClick: () -> Unit,
    onSetNumberDefault: (dataId: Long, makeDefault: Boolean) -> Unit,
) {
    val dataId = action.dataId
    if (dataId == null) {
        ContactSheetRow(
            glyph = glyph,
            iconPackageName = iconPackageName,
            title = action.label,
            subtitle = action.detail,
            trailingChevron = false,
            isActive = isActive,
            modifier = Modifier.testTag("$CONTACT_ACTIONS_ACTION_TAG:${action.label}"),
            onClick = onClick,
        )
        return
    }
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        ContactSheetRow(
            glyph = glyph,
            iconPackageName = iconPackageName,
            title = action.label,
            subtitle = action.detail,
            trailingChevron = false,
            isActive = isActive,
            modifier = Modifier.testTag("$CONTACT_ACTIONS_ACTION_TAG:${action.label}"),
            onClick = onClick,
            onLongClick = { menuExpanded = true },
        )
        LauncherDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            // Non-focusable like the launcher's other row menus, so it never
            // grabs focus off the sheet / search field behind it.
            properties = PopupProperties(focusable = false),
        ) {
            DropdownMenuItem(
                text = {
                    LauncherMenuItemText(
                        stringResource(
                            if (action.isDefault) {
                                R.string.contact_action_clear_default
                            } else {
                                R.string.contact_action_make_default
                            },
                        ),
                    )
                },
                onClick = {
                    menuExpanded = false
                    onSetNumberDefault(dataId, !action.isDefault)
                },
                modifier = Modifier.testTag("$CONTACT_ACTIONS_DEFAULT_ACTION_TAG:${action.label}"),
            )
        }
    }
}

@Composable
private fun ContactSheetRow(
    glyph: ContactChannelGlyph?,
    iconPackageName: String?,
    title: String,
    subtitle: String?,
    trailingChevron: Boolean,
    isActive: Boolean,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val highlightColor = selectionHighlightColor()
    val highlightOnColor = selectionHighlightOnColor()
    val rowColor = if (isActive) highlightColor else Color.Transparent
    val titleColor = if (isActive) highlightOnColor else MaterialTheme.colorScheme.onSurface
    val secondaryColor = if (isActive) highlightOnColor else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(8.dp))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { selected = isActive },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppOrGlyphIcon(iconPackageName = iconPackageName, glyph = glyph)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = secondaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = secondaryColor,
            )
        }
    }
}

@Composable
private fun AppOrGlyphIcon(iconPackageName: String?, glyph: ContactChannelGlyph?) {
    // The built-in channels (Phone / Message / Email) carry a glyph; app channels
    // don't. The glyph is what shows whenever no real app icon is available — the
    // fallback plate below resolves to Person only for the "Open contact" row,
    // which has neither an app icon nor a channel glyph.
    val glyphIcon = glyph?.toIcon()
    if (iconPackageName == null) {
        GlyphPlate(glyphIcon ?: Icons.Filled.Person)
        return
    }
    // A channel with an app icon (a real integration, or a built-in channel's
    // default handler): load the launcher icon off the main thread — the
    // PackageManager lookup crosses IPC/disk and decodes a bitmap, which would
    // jank the sheet's first frame if done inline during composition. Until it
    // lands (or if it fails — an uninstall racing the resolve), fall back to the
    // channel's glyph so a built-in row like Call never shows a blank plate; an
    // app channel has no glyph, so it keeps the subtle empty plate rather than
    // flashing a misleading icon before its real one swaps in.
    val context = LocalContext.current
    var appIcon by remember(iconPackageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(iconPackageName) {
        appIcon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(iconPackageName).toBitmap().asImageBitmap() }
                .getOrNull()
        }
    }
    val loaded = appIcon
    when {
        loaded != null -> Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        glyphIcon != null -> GlyphPlate(glyphIcon)
        else -> Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        )
    }
}

/** A built-in channel's vector glyph centered on the tinted circular plate. */
@Composable
private fun GlyphPlate(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun ContactAvatar(contact: ContactResult) {
    val photo = rememberContactPhotoResolution(contact, 40.dp).bitmap
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            val initial = remember(contact.displayName) {
                val trimmed = contact.displayName.trim()
                String(Character.toChars(trimmed.codePointAt(0))).uppercase()
            }
            Text(
                text = initial,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/** The built-in glyph for a channel, and the neutral fallback for app channels whose icon fails to load. */
private fun ContactChannelGlyph?.toIcon(): ImageVector = when (this) {
    ContactChannelGlyph.Call -> Icons.Filled.Call
    ContactChannelGlyph.Message -> Icons.AutoMirrored.Filled.Message
    ContactChannelGlyph.Email -> Icons.Filled.Email
    null -> Icons.Filled.Person
}
