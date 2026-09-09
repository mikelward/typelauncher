package app.typelauncher

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

// Height of the search field's outlined box. Material's own `OutlinedTextField`
// floors at 56dp, so the field is built from `BasicTextField` + the outlined
// decoration box to reach this denser height. 48dp is the accessibility minimum
// for a tappable target — don't go below it.
private const val SEARCH_FIELD_HEIGHT_DP = 48

// DecorationBox / Container / contentPadding on OutlinedTextFieldDefaults are
// experimental in some Material3 releases; opt in explicitly so the dense field
// compiles across versions. Harmless if they graduate to stable.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LauncherFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = SEARCH_FIELD_HEIGHT_DP.dp),
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = keyboardActions,
        interactionSource = interactionSource,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = value,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = interactionSource,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(LauncherIcons.Search, contentDescription = null) },
                trailingIcon = trailingIcon,
                // Trim the vertical content padding (default 16dp) to 12dp so the
                // 24dp icon/text row plus padding lands at the 48dp target height;
                // the horizontal padding keeps Material's default.
                contentPadding = OutlinedTextFieldDefaults.contentPadding(top = 12.dp, bottom = 12.dp),
                container = {
                    OutlinedTextFieldDefaults.Container(
                        enabled = true,
                        isError = false,
                        interactionSource = interactionSource,
                    )
                },
            )
        },
    )
}

/**
 * Inline autocomplete suggestion drawn right-aligned inside the search field: the
 * top match's full title, faint, with the typed letters bolded. Rendered as a
 * non-interactive overlay sibling of the text field so taps still fall through to
 * the field and its focus / IME handling is left untouched.
 *
 * Right-aligned with an end ellipsis and a width cap so the typed text on the
 * left keeps priority: matched letters anchor at the title's start in the common
 * case, and end-ellipsis truncates the tail, so the highlighted run stays visible
 * when the title is too long to fit.
 */
@Composable
internal fun SearchSuggestionOverlay(
    suggestion: InlineSearchSuggestion,
    modifier: Modifier = Modifier,
) {
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val boldColor = MaterialTheme.colorScheme.onSurface
    val annotated = remember(suggestion, baseColor, boldColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor)) { append(suggestion.name) }
            // Overlay a bold span on each contiguous run of matched letters; the
            // later, narrower span wins over the base color/weight within its range.
            for (range in suggestion.boldIndices.toContiguousRanges(suggestion.name.length)) {
                addStyle(
                    SpanStyle(color = boldColor, fontWeight = FontWeight.Bold),
                    range.first,
                    range.last + 1,
                )
            }
        }
    }
    Text(
        text = annotated,
        // Cap to the right portion so a short typed query on the left never
        // collides with the suggestion; end padding clears the trailing icon.
        // TODO: This is a static width cap, not a measurement of the typed text.
        //  When the user types a long *partial* query (one wider than the left
        //  ~40% of the field, before it exactly equals the full title — the
        //  exact-match case is already suppressed in `searchInlineSuggestion`),
        //  this faint overlay still paints across the right 60% while the
        //  editable text and caret render underneath, making the query hard to
        //  read/edit in the overlap. Measure the typed text and only draw the
        //  suggestion in the leftover space (or hide it once the query no longer
        //  fits), rather than relying on the fixed fraction.
        modifier = modifier
            .fillMaxWidth(SEARCH_SUGGESTION_WIDTH_FRACTION)
            .padding(end = 48.dp),
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
    )
}

// Fraction of the search field width the inline suggestion may occupy on the
// right; the left remainder is reserved for the user's typed text.
private const val SEARCH_SUGGESTION_WIDTH_FRACTION = 0.6f

// Collapse a sorted list of matched indices into contiguous [first..last] ranges
// so the annotated string carries one bold span per run instead of one per char.
// Indices are clamped to the title length defensively.
private fun List<Int>.toContiguousRanges(length: Int): List<IntRange> {
    if (isEmpty()) return emptyList()
    val sorted = filter { it in 0 until length }.sorted()
    if (sorted.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    var start = sorted.first()
    var prev = start
    for (index in 1 until sorted.size) {
        val current = sorted[index]
        if (current == prev + 1) {
            prev = current
        } else {
            ranges.add(start..prev)
            start = current
            prev = current
        }
    }
    ranges.add(start..prev)
    return ranges
}

@Composable
internal fun FilterClearButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(LauncherIcons.Clear, contentDescription = contentDescription)
    }
}
