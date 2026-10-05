package app.typelauncher

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal const val PIN_SHORTCUT_DIALOG_ADD_TAG = "pin_shortcut_dialog_add"
internal const val PIN_SHORTCUT_DIALOG_CANCEL_TAG = "pin_shortcut_dialog_cancel"

/**
 * Asks before a pin request joins the app list. Any app on screen may ask to
 * pin a shortcut, and the shortcut's label and icon are whatever it says, so
 * the user — not the requesting app — confirms what is added. Dismissing the
 * dialog any way other than Add declines the request.
 */
@Composable
internal fun PinShortcutConfirmDialog(
    label: String,
    icon: ImageBitmap?,
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
            PinShortcutConfirmContent(label = label, icon = icon, onAdd = onAdd, onCancel = onCancel)
        }
    }
}

/**
 * The dialog body, split from [PinShortcutConfirmDialog] — the same split as
 * `EditAppDialog` / `EditAppDialogContent` — so a screenshot test can render
 * it without the popup window. Spacing matches the Edit dialog's.
 */
@Composable
internal fun PinShortcutConfirmContent(
    label: String,
    icon: ImageBitmap?,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
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
            // 48 dp, like the Edit dialog's icon preview. Until the icon has
            // loaded (it is read off the main thread) the slot holds a plain
            // placeholder so the row doesn't shift when it lands.
            val iconModifier = Modifier
                .size(48.dp)
                .clip(MaterialTheme.shapes.medium)
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = iconModifier)
            } else {
                Box(iconModifier.background(MaterialTheme.colorScheme.surfaceVariant))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier.testTag(PIN_SHORTCUT_DIALOG_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.pin_shortcut_dialog_cancel))
            }
            TextButton(
                onClick = onAdd,
                modifier = Modifier.testTag(PIN_SHORTCUT_DIALOG_ADD_TAG),
            ) {
                Text(stringResource(R.string.pin_shortcut_dialog_add))
            }
        }
    }
}
