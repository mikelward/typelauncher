package app.typelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap

internal const val ADD_WEB_LINK_DIALOG_FIELD_TAG = "add_web_link_dialog_field"
internal const val ADD_WEB_LINK_DIALOG_ADD_TAG = "add_web_link_dialog_add"
internal const val ADD_WEB_LINK_DIALOG_CANCEL_TAG = "add_web_link_dialog_cancel"

private val AddWebLinkIconSize = 48.dp

/**
 * Asks before a shared page joins the app list, with its name editable: a
 * page title is often longer than a launcher label should be. Dismissing the
 * dialog any way other than Add adds nothing.
 */
@Composable
internal fun AddWebLinkDialog(
    name: String,
    onNameChange: (String) -> Unit,
    url: String,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            AddWebLinkDialogContent(name = name, onNameChange = onNameChange, url = url, onAdd = onAdd, onCancel = onCancel)
        }
    }
}

/**
 * The dialog body, split from [AddWebLinkDialog] so a screenshot test can
 * render it without the popup window (see `EditAppDialogContent`). Spacing
 * matches the pin-shortcut dialog's.
 */
@Composable
internal fun AddWebLinkDialogContent(
    name: String,
    onNameChange: (String) -> Unit,
    url: String,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    val canAdd = name.isNotBlank()
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pin_shortcut_dialog_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The tile the link will get, following the name as it is typed.
            // A 48 dp bitmap: cheap enough to draw per keystroke.
            val iconPx = with(LocalDensity.current) { AddWebLinkIconSize.roundToPx() }
            val icon = remember(name, iconPx) { letterTileIcon(name).toBitmap(iconPx, iconPx).asImageBitmap() }
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(AddWebLinkIconSize)
                    .clip(MaterialTheme.shapes.medium),
            )
            TextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                label = { Text(stringResource(R.string.edit_app_dialog_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag(ADD_WEB_LINK_DIALOG_FIELD_TAG),
            )
        }
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(ADD_WEB_LINK_DIALOG_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.pin_shortcut_dialog_cancel))
            }
            TextButton(
                onClick = onAdd,
                enabled = canAdd,
                modifier = Modifier.testTag(ADD_WEB_LINK_DIALOG_ADD_TAG),
            ) {
                Text(stringResource(R.string.pin_shortcut_dialog_add))
            }
        }
    }
}
