package app.typelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Per-app corner-badge picker. Displays:
 *  - a "Default" tile that clears any user override and falls back to the
 *    auto-disambiguator (or to no badge at all),
 *  - the [BUILT_IN_BADGE_OPTIONS] presets (Home / Work / School / Travel)
 *    in a single row,
 *  - every ISO 3166-1 alpha-2 country flag in an alphabetised grid below.
 *
 * Tapping any tile invokes [onPickBadge] with the chosen glyph (or `null`
 * for the Default tile) and dismisses the dialog. The currently-selected
 * option is outlined so the user can tell at a glance which badge is in
 * effect.
 */
@Composable
internal fun BadgePickerDialog(
    currentBadge: String?,
    onPickBadge: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(BADGE_PICKER_DIALOG_TAG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            BadgePickerDialogContent(
                currentBadge = currentBadge,
                onPickBadge = { glyph ->
                    onPickBadge(glyph)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * Body of [BadgePickerDialog]. Factored out so a Robolectric screenshot
 * test can compose the content directly without the surrounding `Dialog`
 * popup window — same pattern as [EditAppDialogContent].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BadgePickerDialogContent(
    currentBadge: String?,
    onPickBadge: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The flag grid leads with the globe ("world / international") tile so
    // a user-chosen 🌐 sits next to the country flags it generalises over,
    // rather than mixed in with the life-context presets (Home / Work /
    // School / Travel) above. Keeps the preset row a small coherent set
    // and gives the globe a discoverable home alongside the flags.
    val flagGridOptions = remember {
        buildList {
            add(WORLD_BADGE_OPTION)
            addAll(countryBadgeOptions())
        }
    }
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.badge_picker_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // FlowRow wraps to a second visual line on narrow screens (or with
        // longer localised labels) so every preset stays visible and
        // tappable inside the dialog. A plain Row would push the rightmost
        // tiles past the dialog edge on common 360–411 dp phones once
        // Default + 5 presets are laid out side-by-side.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BadgeTile(
                label = stringResource(R.string.badge_picker_default),
                glyph = null,
                selected = currentBadge.isNullOrEmpty(),
                testTag = BADGE_PICKER_DEFAULT_TILE_TAG,
                onClick = { onPickBadge(null) },
            )
            BUILT_IN_BADGE_OPTIONS.forEach { option ->
                BadgeTile(
                    label = option.resolveLabel(),
                    glyph = option.glyph,
                    selected = currentBadge == option.glyph,
                    testTag = "$BADGE_PICKER_PRESET_TILE_TAG:${option.key}",
                    onClick = { onPickBadge(option.glyph) },
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 56.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 320.dp)
                .testTag(BADGE_PICKER_FLAG_GRID_TAG),
            contentPadding = PaddingValues(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(flagGridOptions, key = { it.key }) { option ->
                FlagCell(
                    option = option,
                    selected = currentBadge == option.glyph,
                    onClick = { onPickBadge(option.glyph) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(BADGE_PICKER_DIALOG_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.edit_app_dialog_cancel))
            }
        }
    }
}

@Composable
private fun BadgeTile(
    label: String,
    glyph: String?,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val background = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp)
            .testTag(testTag)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph == null) {
                // "Default" tile draws an outlined circle so the absence
                // of a glyph reads as "no badge" rather than "broken icon".
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .border(
                            2.dp,
                            MaterialTheme.colorScheme.onSurfaceVariant,
                            CircleShape,
                        ),
                )
            } else {
                Text(
                    text = glyph,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FlagCell(
    option: BadgeOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val label = option.resolveLabel()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("$BADGE_PICKER_FLAG_TILE_TAG:${option.key}")
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = option.glyph,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Resolves the user-facing label for a [BadgeOption]. Presets carry an
 * `@StringRes`, so we go through `stringResource` to honour the device
 * locale; country tiles already arrived with a pre-localized
 * [BadgeOption.labelText] from `Locale.getDisplayCountry`. Falls through
 * to the empty string for an option that has neither (no current caller
 * constructs that, but it keeps the resolver total).
 */
@Composable
private fun BadgeOption.resolveLabel(): String =
    labelRes?.let { stringResource(it) } ?: labelText.orEmpty()
