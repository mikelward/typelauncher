package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home's own widget set ("Widgets on home screen"), rendered in the app-list
 * slot while the query is empty.
 *
 * Normal Home never scrolls this area: the widgets stack from the top and
 * anything past the slot's bottom is clipped, so the launcher's vertical pulls
 * (recents / notification shade) keep working over it. A long-press anywhere
 * in the area — on a widget or on the empty space below — enters edit mode,
 * where the area scrolls, every widget shows inline Move / Resize / Remove
 * actions, and an Add / Done bar sits under the list. With no widgets yet, a
 * single Add widget button fills the area so the setting never looks broken.
 *
 * The widgets are one non-lazy `Column` in both modes, so entering or leaving
 * edit mode never disposes a hosted widget view (no re-inflation, no
 * RemoteViews re-apply); only the scroll and the per-widget action rows toggle.
 */
@Composable
internal fun HomeWidgets(
    widgetIds: List<Int>,
    isEditing: Boolean,
    isAddingWidget: Boolean,
    isLoadingAvailableWidgets: Boolean,
    availableWidgets: List<WidgetProvider>,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    widgetHeights: Map<Int, Int>,
    widgetSpans: Map<Int, Int>,
    widgetProviderLabels: Map<Int, String>,
    strandedWidgetIds: Set<Int>,
    workProfileWidgetRefreshToken: Int,
    isCurrentPage: Boolean,
    onBoundsChanged: (Rect) -> Unit,
    onStartEditing: () -> Unit,
    onStopEditing: () -> Unit,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRestoreWidget: (Int) -> Unit,
    onResizeWidget: (widgetId: Int, heightDp: Int) -> Unit,
    onMoveWidget: (widgetId: Int, direction: WidgetMoveDirection) -> Unit,
    onResizeWidgetSpan: (widgetId: Int, span: Int) -> Unit,
    modifier: Modifier = Modifier,
    // False for the kiosk display: a hold there is the exit gesture, so the
    // widgets' own long-press (and its haptic) must not fire.
    widgetLongPressEnabled: Boolean = true,
    // Test seams, forwarded to each HostedWidgetCard (see its docs).
    providerInfoOverride: ((Int) -> AppWidgetProviderInfo?)? = null,
    createWidgetView: ((Context, Int) -> AppWidgetHostView)? = null,
) {
    val resolvedProviderInfos = remember { mutableStateMapOf<Int, AppWidgetProviderInfo?>() }
    val scrollState = rememberScrollState()
    // Normal Home is pinned to the top: leaving edit mode drops whatever
    // scroll position editing left behind.
    LaunchedEffect(isEditing) {
        if (!isEditing) scrollState.scrollTo(0)
    }
    Box(
        modifier = modifier
            .testTag(HOME_WIDGETS_TAG)
            .onGloballyPositioned { coords ->
                onBoundsChanged(Rect(coords.positionInRoot(), coords.size.toSize()))
            },
    ) {
        if (widgetIds.isEmpty() && !isEditing) {
            FilledTonalButton(
                onClick = onAddWidget,
                modifier = Modifier
                    .align(Alignment.Center)
                    .testTag(HOME_WIDGETS_EMPTY_ADD_TAG),
            ) {
                Icon(LauncherIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(R.string.widgets_add_button_description),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            return@Box
        }
        // Landscape doubles the columns a row holds (see widgetGridRowColumns).
        val rowColumns = currentWidgetGridRowColumns()
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    // Long-press on the empty space below the widgets enters
                    // edit mode (a long-press on a widget itself arrives through
                    // its host view, see onLongPressOverride).
                    .pointerInput(isEditing) {
                        if (!isEditing) detectStillLongPress(onLongPress = onStartEditing)
                    }
                    // Scrollable only while editing; otherwise the scroll
                    // container just clips the stack at the slot's bottom.
                    .verticalScroll(scrollState, enabled = isEditing),
                verticalArrangement = Arrangement.spacedBy(HOME_CARD_SPACING_DP.dp),
            ) {
                // Grid rows: widgets narrower than a row share it (see
                // widgetGridRows). Each card stays keyed by its own ID, so a
                // reflow only re-hosts widgets that actually change rows.
                widgetGridRows(widgetIds, widgetSpans, rowColumns).forEach { row ->
                    key(row.key) {
                        WidgetGridRowLayout(row = row, modifier = Modifier.fillMaxWidth()) {
                            row.cells.forEach { cell ->
                                val widgetId = cell.widgetId
                                val index = widgetIds.indexOf(widgetId)
                                key(widgetId) {
                                    HostedWidgetCard(
                                        widgetId = widgetId,
                                        appWidgetHost = appWidgetHost,
                                        appWidgetManager = appWidgetManager,
                                        customHeightDp = widgetHeights[widgetId],
                                        canMoveUp = index > 0,
                                        canMoveDown = index < widgetIds.lastIndex,
                                        restoreLabel = widgetProviderLabels[widgetId]
                                            ?.takeIf { widgetId in strandedWidgetIds },
                                        onRemoveWidget = onRemoveWidget,
                                        onRestoreWidget = onRestoreWidget,
                                        onResizeWidget = { heightDp -> onResizeWidget(widgetId, heightDp) },
                                        onMoveWidget = onMoveWidget,
                                        workProfileWidgetRefreshToken = workProfileWidgetRefreshToken,
                                        providerInfoOverride = providerInfoOverride?.invoke(widgetId),
                                        createWidgetView = createWidgetView,
                                        resolvedProviderInfos = resolvedProviderInfos,
                                        isCurrentPage = isCurrentPage,
                                        onLongPressOverride = if (isEditing) ({}) else onStartEditing,
                                        longPressEnabled = widgetLongPressEnabled,
                                        showInlineActions = isEditing,
                                        span = cell.span,
                                        onResizeSpan = { span -> onResizeWidgetSpan(widgetId, span) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (isEditing && isAddingWidget) {
                    WidgetPickerCard(
                        availableWidgets = availableWidgets,
                        isLoading = isLoadingAvailableWidgets,
                        appWidgetManager = appWidgetManager,
                        onDismissWidgetPicker = onDismissWidgetPicker,
                        onSelectWidget = onSelectWidget,
                    )
                }
            }
            if (isEditing) {
                // The edit bar sits on the launcher's opaque card surface, like
                // the widgets' action rows, so it reads over any wallpaper.
                SectionCard(
                    modifier = Modifier.padding(top = HOME_CARD_SPACING_DP.dp),
                    contentPadding = WIDGET_ACTION_BAR_PADDING,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(HOME_CARD_SPACING_DP.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!isAddingWidget) {
                            TextButton(
                                onClick = onAddWidget,
                                modifier = Modifier.testTag(HOME_WIDGETS_ADD_TAG),
                            ) {
                                Text(stringResource(R.string.widgets_add_button_description))
                            }
                        }
                        Button(
                            onClick = onStopEditing,
                            modifier = Modifier.testTag(HOME_WIDGETS_DONE_TAG),
                        ) {
                            Text(stringResource(R.string.widgets_picker_done))
                        }
                    }
                }
            }
        }
    }
}

/**
 * A long-press that never consumes an event and gives up the moment the
 * finger travels past touch slop, lifts, or a child or the carousel consumes
 * the pointer. `detectTapGestures` consumes the down and keeps counting
 * through any unconsumed drift, so a slow pull-down that crossed the widget
 * area could open edit mode instead of the notification shade.
 */
private suspend fun PointerInputScope.detectStillLongPress(onLongPress: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val endedEarly = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                val drift = (change.position - down.position).getDistance()
                if (!change.pressed || change.isConsumed || drift > viewConfiguration.touchSlop) break
            }
            true
        }
        if (endedEarly == null) onLongPress()
    }
}
