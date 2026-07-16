package app.typelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The compact quick-actions card for a searched-for contact. A low-level
 * [Dialog] + [Surface] (rather than `AlertDialog`) for the same reason
 * `EditAppDialog` uses one — a hand-rolled body avoids the material dialog's
 * scrollable wrapper — and so the body can be screenshot-tested via
 * [ContactActionsSheetContent] without the popup window.
 */
@Composable
internal fun ContactActionsSheet(
    actions: ContactActions,
    onAction: (ContactAction) -> Unit,
    onOpenContactCard: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CONTACT_ACTIONS_SHEET_TAG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            ContactActionsSheetContent(
                actions = actions,
                onAction = onAction,
                onOpenContactCard = onOpenContactCard,
            )
        }
    }
}

/**
 * The card body: a contact header, then either the channel list (step one —
 * Phone, Message, the contact's installed-app actions, Email, and "Open
 * contact") or, once a channel with more than one action is chosen, that
 * channel's actions with the default number on top (step two). Tapping a
 * single-action channel acts immediately, skipping step two. Factored out of
 * [ContactActionsSheet] so a Robolectric screenshot test can compose it inside
 * an activity-hosted tree rather than the `Dialog` popup window.
 */
@Composable
internal fun ContactActionsSheetContent(
    actions: ContactActions,
    onAction: (ContactAction) -> Unit,
    onOpenContactCard: () -> Unit,
) {
    // Saveable so a configuration change (rotation, theme toggle) while the
    // second step is open keeps the user on that channel rather than snapping
    // back to the channel list. Keyed on the contact so reusing the sheet for a
    // different contact resets to step one.
    var selectedChannelId by rememberSaveable(actions.contact.contactId) {
        mutableStateOf<String?>(null)
    }
    val selectedChannel = actions.channels.firstOrNull { it.id == selectedChannelId }

    // Scrollable: a contact with many channels or numbers — or a large
    // font/display size — can make the card taller than the window, and without
    // scrolling the lower rows (including "Open contact") would clip out of
    // reach. The Dialog bounds the height to the window, so the column scrolls
    // within it.
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        ContactActionsHeader(
            contact = actions.contact,
            channel = selectedChannel,
            onBack = { selectedChannelId = null },
        )
        if (selectedChannel == null) {
            actions.channels.forEach { channel ->
                ContactChannelRow(
                    channel = channel,
                    onClick = {
                        val single = channel.actions.singleOrNull()
                        if (single != null) onAction(single) else selectedChannelId = channel.id
                    },
                )
            }
            ContactSheetRow(
                icon = Icons.Filled.Person,
                iconPackageName = null,
                title = stringResource(R.string.contact_actions_open_contact),
                subtitle = null,
                trailingChevron = false,
                modifier = Modifier.testTag(CONTACT_ACTIONS_OPEN_CONTACT_TAG),
                onClick = onOpenContactCard,
            )
        } else {
            selectedChannel.actions.forEach { action ->
                ContactSheetRow(
                    icon = selectedChannel.glyph.toIcon(),
                    iconPackageName = selectedChannel.iconPackageName,
                    title = action.label,
                    subtitle = action.detail,
                    trailingChevron = false,
                    modifier = Modifier.testTag("$CONTACT_ACTIONS_ACTION_TAG:${action.label}"),
                    onClick = { onAction(action) },
                )
            }
        }
    }
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
private fun ContactChannelRow(channel: ContactChannel, onClick: () -> Unit) {
    ContactSheetRow(
        icon = channel.glyph.toIcon(),
        iconPackageName = channel.iconPackageName,
        title = channel.label,
        subtitle = channel.actions.singleOrNull()?.detail,
        trailingChevron = channel.actions.size > 1,
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
@Composable
private fun ContactSheetRow(
    icon: ImageVector,
    iconPackageName: String?,
    title: String,
    subtitle: String?,
    trailingChevron: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppOrGlyphIcon(icon = icon, iconPackageName = iconPackageName)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppOrGlyphIcon(icon: ImageVector, iconPackageName: String?) {
    if (iconPackageName == null) {
        // Built-in channel (Phone / Message / Email): the glyph is the real
        // icon, drawn synchronously on its tinted plate.
        GlyphPlate(icon)
        return
    }
    // App channel: load the app's launcher icon off the main thread — the
    // PackageManager lookup crosses IPC/disk and decodes a bitmap, which would
    // jank the sheet's first frame if done inline during composition. Until it
    // lands, show a subtle empty plate (no glyph, so nothing misleading flashes
    // before the real icon swaps in). A package uninstalled between resolve and
    // render simply leaves the empty plate rather than throwing.
    val context = LocalContext.current
    var appIcon by remember(iconPackageName) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(iconPackageName) {
        appIcon = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(iconPackageName).toBitmap().asImageBitmap() }
                .getOrNull()
        }
    }
    val loaded = appIcon
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
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
