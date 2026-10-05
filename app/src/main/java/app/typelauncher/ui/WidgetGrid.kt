package app.typelauncher

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One row of the widget grid: the widgets that share it, left to right, each
 * with the column it starts at and how many of the row's [columns] it spans.
 */
internal data class WidgetGridRow(
    val cells: List<WidgetGridCell>,
    val columns: Int = WIDGET_GRID_COLUMNS,
) {
    /** Stable for as long as the row's first widget leads it. */
    val key: Int get() = cells.first().widgetId
}

internal data class WidgetGridCell(val widgetId: Int, val startColumn: Int, val span: Int)

/**
 * How many columns one grid row holds in a window of [widthDp] × [heightDp]:
 * [WIDGET_GRID_COLUMNS] normally, doubled in a window wider than it is tall,
 * so landscape fits two full-width widgets side by side. A widget's span is
 * still 1..[WIDGET_GRID_COLUMNS] either way, so rotating never rewrites a
 * stored width; a widget just takes half as much of a landscape row.
 */
internal fun widgetGridRowColumns(widthDp: Int, heightDp: Int): Int =
    if (widthDp > heightDp) WIDGET_GRID_COLUMNS * 2 else WIDGET_GRID_COLUMNS

/** [widgetGridRowColumns] for the current window. */
@Composable
internal fun currentWidgetGridRowColumns(): Int {
    val configuration = LocalConfiguration.current
    return widgetGridRowColumns(configuration.screenWidthDp, configuration.screenHeightDp)
}

/**
 * Packs [widgetIds], in order, into grid rows of [rowColumns] columns: each
 * widget takes the next free columns of the current row, or starts a new row
 * when its span doesn't fit in what's left. Order is never changed to fill a
 * gap, so Move up / Move down keep meaning "earlier / later in the list". A
 * widget missing from [spans] spans [WIDGET_GRID_COLUMNS] — a full row in
 * portrait, half a row in landscape.
 */
internal fun widgetGridRows(
    widgetIds: List<Int>,
    spans: Map<Int, Int>,
    rowColumns: Int = WIDGET_GRID_COLUMNS,
): List<WidgetGridRow> {
    val rows = mutableListOf<WidgetGridRow>()
    var current = mutableListOf<WidgetGridCell>()
    var nextColumn = 0
    for (id in widgetIds) {
        val span = (spans[id] ?: WIDGET_GRID_COLUMNS).coerceIn(1, WIDGET_GRID_COLUMNS)
        if (nextColumn + span > rowColumns) {
            rows += WidgetGridRow(current, rowColumns)
            current = mutableListOf()
            nextColumn = 0
        }
        current += WidgetGridCell(id, nextColumn, span)
        nextColumn += span
    }
    if (current.isNotEmpty()) rows += WidgetGridRow(current, rowColumns)
    return rows
}

/**
 * Lays out one [WidgetGridRow]: the width is split into the row's
 * [WidgetGridRow.columns] equal columns separated by [gap], so a cell's edges
 * line up with every other row's column edges. Each child (one per cell, in order) gets exactly
 * its cells' width and its own height; the row is as tall as its tallest
 * child, children top-aligned.
 */
@Composable
internal fun WidgetGridRowLayout(
    row: WidgetGridRow,
    modifier: Modifier = Modifier,
    gap: Dp = HOME_CARD_SPACING_DP.dp,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val columns = row.columns
        val columnWidth = (width - gapPx * (columns - 1)).toFloat() / columns
        val placeables = measurables.mapIndexed { index, measurable ->
            val cell = row.cells[index]
            val cellWidth = (columnWidth * cell.span + gapPx * (cell.span - 1)).roundToInt()
            measurable.measure(Constraints.fixedWidth(cellWidth).copy(maxHeight = constraints.maxHeight))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val x = ((columnWidth + gapPx) * row.cells[index].startColumn).roundToInt()
                placeable.placeRelative(x, 0)
            }
        }
    }
}
