package app.typelauncher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// Opacity applied to every [SectionCard]'s container fill within the current
// subtree, so Home can let the wallpaper show through its cards while keeping
// their text and icons fully opaque. Defaults to 1 (fully opaque); Home
// provides the user's "Card opacity" value only while the wallpaper is active,
// so cards outside Home (Settings, dialogs) are unaffected.
internal val LocalCardAlpha = compositionLocalOf { 1f }

@Composable
internal fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// Inner padding (top + bottom and start + end) applied to every `SectionCard`
// content area. Exposed so callers that need to size a card around a known
// content height (e.g. the home dock slot capping the work dock at one icon
// row) can add the chrome without recomputing it from a literal.
internal const val SECTION_CARD_PADDING_DP = 12

@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    colors: CardColors = CardDefaults.cardColors(),
    // Defaults to the uniform 12dp inner padding every card shares. Overridden
    // only by the search card, whose bordered text field already supplies its
    // own frame, so the redundant vertical padding is trimmed there — see
    // `SearchCard` in HomeScreen.kt. Keep the sides at 12dp in any override so
    // the card stays on the same left/right rhythm as its siblings.
    contentPadding: PaddingValues = PaddingValues(SECTION_CARD_PADDING_DP.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    // Fade only the container fill (not the whole card, which would dim text and
    // icons too) so the wallpaper shows through while content stays legible.
    val cardAlpha = LocalCardAlpha.current
    val resolvedColors = if (cardAlpha < 1f) {
        colors.copy(containerColor = colors.containerColor.copy(alpha = colors.containerColor.alpha * cardAlpha))
    } else {
        colors
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = resolvedColors,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}
