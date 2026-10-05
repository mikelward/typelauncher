package app.typelauncher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * The Settings preview's stand-in for Home's widget area: the same grid
 * (rows, columns, relative heights) drawn as outlined boxes, without hosting
 * a single widget — so opening Settings costs no widget binds or RemoteViews
 * work, and the sketch stays legible in a slot only a bar or two tall.
 *
 * Heights are the widgets' resized heights where the user set one, else
 * [HOME_WIDGET_SKETCH_DEFAULT_HEIGHT_DP] (a widget's own default height needs a
 * provider lookup Settings deliberately skips). The whole stack is scaled
 * down uniformly in height to fit the slot, never up; widths keep their real
 * column fractions. With no widgets, it shows Home's Add widget button.
 */
@Composable
internal fun HomeWidgetsSketch(
    widgetIds: List<Int>,
    widgetHeights: Map<Int, Int>,
    widgetSpans: Map<Int, Int>,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .testTag(SETTINGS_PREVIEW_HOME_WIDGETS_TAG),
    ) {
        if (widgetIds.isEmpty()) {
            // Inert: the preview's overlay takes every touch.
            FilledTonalButton(onClick = {}, modifier = Modifier.align(Alignment.Center)) {
                Icon(LauncherIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.widgets_add_button_description),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            return@Box
        }
        val rows = remember(widgetIds, widgetSpans) { widgetGridRows(widgetIds, widgetSpans) }
        val rowHeightsDp = rows.map { row ->
            row.cells.maxOf { widgetHeights[it.widgetId] ?: HOME_WIDGET_SKETCH_DEFAULT_HEIGHT_DP }
        }
        val stackHeightDp = rowHeightsDp.sum() + HOME_CARD_SPACING_DP * (rows.size - 1)
        BoxWithConstraints {
            val density = LocalDensity.current
            // Scale in pixels and round every height and gap *down* to a whole
            // pixel: rounding each to the nearest pixel independently could
            // add up to a few pixels past the slot and clip the last row.
            val scale = (constraints.maxHeight / (stackHeightDp * density.density)).coerceAtMost(1f)
            val scaledDp = { dp: Int -> with(density) { floor(dp * density.density * scale).toInt().toDp() } }
            val gap = scaledDp(HOME_CARD_SPACING_DP)
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                rows.forEach { row ->
                    // Only heights scale: the column gutter stays Home's, so
                    // widths match Home's grid (WidgetGridRowLayout's default).
                    WidgetGridRowLayout(row = row, modifier = Modifier.fillMaxWidth()) {
                        row.cells.forEach { cell ->
                            val heightDp = widgetHeights[cell.widgetId] ?: HOME_WIDGET_SKETCH_DEFAULT_HEIGHT_DP
                            Box(
                                modifier = Modifier
                                    .height(scaledDp(heightDp))
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = HOME_WIDGET_SKETCH_FILL_ALPHA),
                                        MaterialTheme.shapes.small,
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                                    .testTag("$SETTINGS_PREVIEW_HOME_WIDGET_SKETCH_TAG:${cell.widgetId}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

// Matches the height a hosted widget card reserves before its provider resolves.
private const val HOME_WIDGET_SKETCH_DEFAULT_HEIGHT_DP = 112
private const val HOME_WIDGET_SKETCH_FILL_ALPHA = 0.6f
