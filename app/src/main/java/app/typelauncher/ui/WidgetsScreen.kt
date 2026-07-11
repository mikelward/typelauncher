package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WidgetsScreen(
    widgetIds: List<Int>,
    availableWidgets: List<WidgetProvider>,
    isAddingWidget: Boolean,
    isLoadingAvailableWidgets: Boolean = false,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    innerPadding: PaddingValues,
    widgetHeights: Map<Int, Int> = emptyMap(),
    isCurrentPage: Boolean = true,
    onAddWidget: (isCurrentPageScrollable: Boolean) -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onResizeWidget: (widgetId: Int, heightDp: Int) -> Unit = { _, _ -> },
    onMoveWidget: (widgetId: Int, direction: WidgetMoveDirection) -> Unit = { _, _ -> },
    workProfileWidgetRefreshToken: Int = 0,
) {
    val listState = rememberLazyListState()
    // Snap back to the top as soon as this page stops being the current page.
    // The widget page's LazyListState is remembered for the lifetime of its
    // carousel slot — the page subtree is keyed by destination, so it survives
    // navigating away and back — which means a page left scrolled down would
    // otherwise stay scrolled down on return, hiding the first widget above the
    // fold. The reset fires on leave rather than on (re-)entry because the
    // carousel only flips `currentPage` (and thus isCurrentPage) once the settle
    // animation has finished: resetting on entry would let the stale offset
    // slide into view and then snap to the top, the exact jump this avoids. By
    // the time isCurrentPage flips false the page has already translated
    // off-screen, so the snap is invisible and the page is at the top before it
    // is ever translated back on. Keyed on isCurrentPage alone so adding or
    // removing a widget while the page is open doesn't move the scroll position.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            listState.scrollToItem(0)
        }
    }
    val widgetScreenTag = if (isCurrentPage) WIDGETS_SCREEN_TAG else "$WIDGETS_SCREEN_TAG:offscreen"
    val addWidgetCardTag = if (isCurrentPage) ADD_WIDGET_CARD_TAG else "$ADD_WIDGET_CARD_TAG:offscreen"
    // Screen-scoped so a card whose LazyColumn slot was recycled re-enters
    // composition with its provider info already resolved (no placeholder
    // flash). See the parameter's doc on HostedWidgetCard for why this is
    // plain state rather than rememberSaveable.
    val resolvedProviderInfos = remember { mutableStateMapOf<Int, AppWidgetProviderInfo?>() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .testTag(widgetScreenTag),
        verticalArrangement = Arrangement.spacedBy(HOME_CARD_SPACING_DP.dp),
    ) {
        itemsIndexed(widgetIds, key = { _, widgetId -> widgetId }) { index, widgetId ->
            HostedWidgetCard(
                widgetId = widgetId,
                appWidgetHost = appWidgetHost,
                appWidgetManager = appWidgetManager,
                customHeightDp = widgetHeights[widgetId],
                canMoveUp = index > 0,
                canMoveDown = index < widgetIds.lastIndex,
                onRemoveWidget = onRemoveWidget,
                onResizeWidget = { heightDp -> onResizeWidget(widgetId, heightDp) },
                onMoveWidget = onMoveWidget,
                workProfileWidgetRefreshToken = workProfileWidgetRefreshToken,
                resolvedProviderInfos = resolvedProviderInfos,
                isCurrentPage = isCurrentPage,
            )
        }
        if (isAddingWidget) {
            item {
                WidgetPickerCard(
                    availableWidgets = availableWidgets,
                    isLoading = isLoadingAvailableWidgets,
                    appWidgetManager = appWidgetManager,
                    onDismissWidgetPicker = onDismissWidgetPicker,
                    onSelectWidget = onSelectWidget,
                )
            }
        }
        item {
            AddWidgetCard(
                testTag = addWidgetCardTag,
                onAddWidget = {
                    onAddWidget(listState.canScrollBackward || listState.canScrollForward)
                },
            )
        }
    }
}

@Composable
private fun WidgetPickerCard(
    availableWidgets: List<WidgetProvider>,
    isLoading: Boolean,
    appWidgetManager: AppWidgetManager?,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    SectionCard(Modifier.testTag(WIDGET_PICKER_TAG)) {
        var expandedGroupKey by remember { mutableStateOf<WidgetGroupKey?>(null) }
        var filterQuery by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.widgets_picker_title), style = MaterialTheme.typography.titleMedium)
            Button(
                onClick = onDismissWidgetPicker,
                modifier = Modifier.defaultMinSize(minWidth = 72.dp),
            ) {
                Text(stringResource(R.string.widgets_picker_done))
            }
        }
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .testTag(WIDGET_PICKER_LOADING_TAG),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.widgets_picker_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (availableWidgets.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Widgets,
                title = stringResource(R.string.widgets_picker_empty_title),
                body = stringResource(R.string.widgets_picker_empty_body),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            WidgetPickerFilterField(
                query = filterQuery,
                onQueryChanged = { value ->
                    filterQuery = value
                    expandedGroupKey = null
                },
            )
            val trimmedQuery = filterQuery.trim()
            // Group key includes the work-profile flag so the personal and work
            // copies of the same package render as distinct sections and bind
            // against the correct profile.
            val filteredGroups = availableWidgets
                .groupBy { provider -> WidgetGroupKey(provider.appName, provider.isWorkProvider) }
                .let { groups ->
                    if (trimmedQuery.isEmpty()) {
                        groups
                    } else {
                        groups.entries
                            .mapNotNull { entry ->
                                // A group surfaces when its app name matches — in
                                // which case every widget in the group is kept, as
                                // if the app itself were the hit — or, failing
                                // that, when any individual widget label matches,
                                // in which case only the matching widgets are kept
                                // so an "agenda" search finds the Calendar app's
                                // Agenda widget without the app name mentioning it.
                                val appTier = entry.key.appName.launcherMatchTier(trimmedQuery)
                                val labelMatches = entry.value.mapNotNull { provider ->
                                    provider.label.launcherMatchTier(trimmedQuery)
                                        ?.let { tier -> provider to tier }
                                }
                                val bestLabelTier = labelMatches
                                    .minByOrNull { (_, tier) -> tier.ordinal }
                                    ?.second
                                // Rank the group by its single best hit across the
                                // app name and every widget label, so a strong
                                // widget-label match (e.g. a prefix) still outranks
                                // a weaker app-name match (e.g. an anchored hit) —
                                // not just whichever kind of hit was checked first.
                                val bestTier = listOfNotNull(appTier, bestLabelTier)
                                    .minByOrNull { it.ordinal }
                                    ?: return@mapNotNull null
                                val providers = if (appTier != null) {
                                    entry.value
                                } else {
                                    labelMatches.map { (provider, _) -> provider }
                                }
                                Triple(entry.key, providers, bestTier)
                            }
                            .sortedBy { (_, _, tier) -> tier.ordinal }
                            .associate { (key, providers, _) -> key to providers }
                    }
                }
            if (filteredGroups.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.widgets_picker_no_matches_title),
                    body = stringResource(R.string.widgets_picker_no_matches_body),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(
                    modifier = Modifier.testTag(WIDGET_PICKER_LIST_TAG),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    filteredGroups.forEach { (groupKey, providers) ->
                        val isAppExpanded = expandedGroupKey == groupKey
                        WidgetAppSection(
                            groupKey = groupKey,
                            providers = providers,
                            isExpanded = isAppExpanded,
                            appWidgetManager = appWidgetManager,
                            onToggleExpanded = {
                                expandedGroupKey = if (isAppExpanded) null else groupKey
                            },
                            onSelectWidget = onSelectWidget,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetPickerFilterField(
    query: String,
    onQueryChanged: (String) -> Unit,
) {
    LauncherFilterField(
        value = query,
        onValueChange = onQueryChanged,
        placeholder = stringResource(R.string.widgets_picker_filter_hint),
        modifier = Modifier.testTag(WIDGET_PICKER_FILTER_TAG),
        trailingIcon = {
            if (query.isNotEmpty()) {
                FilterClearButton(
                    onClick = { onQueryChanged("") },
                    contentDescription = stringResource(R.string.widgets_picker_filter_clear_description),
                    modifier = Modifier.testTag(WIDGET_PICKER_FILTER_CLEAR_TAG),
                )
            }
        },
    )
}

@Composable
private fun WidgetAppSection(
    groupKey: WidgetGroupKey,
    providers: List<WidgetProvider>,
    isExpanded: Boolean,
    appWidgetManager: AppWidgetManager?,
    onToggleExpanded: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .semantics { role = Role.Button }
                .testTag("$WIDGET_APP_ROW_TAG:${groupKey.tagSuffix}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetAppIcon(
                    appIcon = providers.firstOrNull()?.appIcon,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = groupKey.appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = widgetProviderCountLabel(providers.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) {
                        stringResource(R.string.widgets_picker_collapse_app)
                    } else {
                        stringResource(R.string.widgets_picker_expand_app)
                    },
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isExpanded) {
            providers.forEach { provider ->
                WidgetProviderRow(
                    provider = provider,
                    appWidgetManager = appWidgetManager,
                    onSelectWidget = onSelectWidget,
                )
            }
        }
    }
}

@Composable
private fun WidgetProviderRow(
    provider: WidgetProvider,
    appWidgetManager: AppWidgetManager?,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("$WIDGET_PROVIDER_ROW_TAG:${provider.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetIcon(provider)
                val density = LocalDensity.current
                val paddedToFloor = with(density) { provider.minHeight.toDp() } < WIDGET_MIN_HEIGHT_DP.dp
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(provider.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.widgets_picker_size_label,
                            provider.targetCellWidth,
                            provider.targetCellHeight,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (paddedToFloor) {
                        Text(
                            stringResource(R.string.widgets_picker_padded_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            WidgetPreview(
                provider = provider,
                appWidgetManager = appWidgetManager,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WIDGET_PROVIDER_PREVIEW_HEIGHT_DP.dp)
                    .testTag("$WIDGET_PREVIEW_TAG:${provider.id}"),
            )
            Button(onClick = { onSelectWidget(provider) }) {
                Text(stringResource(R.string.widgets_add_button_description))
            }
        }
    }
}

@Composable
private fun WidgetPreview(
    provider: WidgetProvider,
    appWidgetManager: AppWidgetManager?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // `getWidgetPreview` is a synchronous Binder round-trip into
    // AppWidgetService (it returns a RemoteViews), and this composable first
    // composes for every visible provider row on the picker's opening frame —
    // running it inside `remember` stalled the main thread once per row
    // during composition. Load it off the main thread and swap in; until it
    // lands, the fallback glyph below renders (the same thing a provider with
    // no preview shows), so the swap only ever adds detail.
    val loadedPreview by produceState<WidgetPreviewValue?>(null, provider, appWidgetManager) {
        // Reset before the IO hop: produceState keeps its previous value
        // across key changes (only the producer restarts), and these rows are
        // emitted positionally, so filtering the picker can hand this
        // composition a different provider — without the reset the old
        // provider's preview would linger next to the new provider's label
        // until the new load lands.
        value = null
        value = withContext(Dispatchers.IO) { provider.preview(appWidgetManager, context) }
    }
    val preview = loadedPreview
    var generatedInflationFailed by remember(preview?.generated) { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        when {
            preview?.generated != null && !generatedInflationFailed -> AndroidView(
                factory = { viewContext ->
                    try {
                        preview.generated.apply(viewContext, FrameLayout(viewContext)).also { view ->
                            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    } catch (_: RuntimeException) {
                        generatedInflationFailed = true
                        FrameLayout(viewContext)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            preview?.image != null -> {
                val previewImage = preview.image
                val previewBitmap = remember(previewImage) {
                    previewImage.toBitmap().asImageBitmap()
                }
                Image(
                    bitmap = previewBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(WIDGET_PROVIDER_PREVIEW_HEIGHT_DP.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            else -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Widgets,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WidgetAppIcon(appIcon: Drawable?) {
    val bitmap = remember(appIcon) { appIcon?.toBitmap()?.asImageBitmap() }
    Box(modifier = Modifier.size(36.dp)) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    Icons.Filled.Widgets,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun WidgetIcon(provider: WidgetProvider) {
    val bitmap = remember(provider) { provider.icon()?.toBitmap()?.asImageBitmap() }
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                Icons.Filled.Widgets,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AddWidgetCard(testTag: String, onAddWidget: () -> Unit) {
    val addWidgetDescription = stringResource(R.string.widgets_add_button_description)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(ADD_WIDGET_CARD_HEIGHT_DP.dp)
            .clickable(onClick = onAddWidget)
            .semantics {
                role = Role.Button
                contentDescription = addWidgetDescription
            }
            .testTag(testTag),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun HostedWidgetCard(
    widgetId: Int,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    customHeightDp: Int?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRemoveWidget: (Int) -> Unit,
    onResizeWidget: (Int) -> Unit,
    onMoveWidget: (widgetId: Int, direction: WidgetMoveDirection) -> Unit,
    // Bumped when a work profile becomes available again; see the token's doc in
    // LauncherUiState. Only work-profile cards fold it into the host-view key so
    // the stale "Couldn't add widget" placeholder is replaced by a re-fetch.
    workProfileWidgetRefreshToken: Int = 0,
    // Test seams (default null = production behavior). The hosted-view path
    // needs a real AppWidgetHost binding, which doesn't resolve under
    // Robolectric, so a test supplies the provider info directly and creates a
    // stand-in host view instead of going through AppWidgetHost.createView.
    providerInfoOverride: AppWidgetProviderInfo? = null,
    createWidgetView: ((Context, Int) -> AppWidgetHostView)? = null,
    // Starts the card in resize mode. Test seam: the production entry point
    // is the long-press menu's Resize item, which Robolectric can't drive
    // through the host view's View-level long-press detection.
    initiallyResizing: Boolean = false,
    // Starts the card with its actions menu open. Test seam: the production
    // entry point is the host view's View-level long-press detection, which
    // Robolectric can't drive directly.
    initiallyMenuExpanded: Boolean = false,
    // Whether this card's page is the carousel's current page. False once the
    // page has settled off-screen (see WidgetsScreen's `isCurrentPage` doc) —
    // used to close the actions menu so a `DropdownMenu` popup, which does
    // not track its anchor's carousel `graphicsLayer` translation, can never
    // freeze at a stale screen position and reappear over whichever page
    // later settles under it.
    isCurrentPage: Boolean = true,
    // Test seam: replaces the initial provider-info lookup so a test can hold
    // the card in its resolving state deterministically. Default null = the
    // real AppWidgetManager lookup on Dispatchers.IO.
    resolveProviderInfo: (suspend (Int) -> AppWidgetProviderInfo?)? = null,
    // Cache of resolved provider infos keyed by widget id: key absent = not
    // yet resolved, present-with-null = resolved unavailable. WidgetsScreen
    // hoists one map per page so a recycled LazyColumn slot re-enters
    // composition already resolved instead of flashing the placeholder.
    // Deliberately plain composition state, never rememberSaveable: the infos
    // are heavyweight parcelables (they carry ActivityInfo/ApplicationInfo),
    // and writing one per widget into the saved-state Bundle could overflow
    // the state-save binder transaction on a widget-heavy setup. After an
    // activity recreation the cards just re-resolve asynchronously. The
    // default gives standalone (test/preview) callers a private cache.
    resolvedProviderInfos: SnapshotStateMap<Int, AppWidgetProviderInfo?> = remember { mutableStateMapOf() },
) {
    // Resolved asynchronously: `AppWidgetManager.getAppWidgetInfo` is a Binder
    // IPC, and this card first composes mid carousel drag (widget pages are
    // warmed the moment the gesture is claimed, precisely to spread the heavy
    // work out), so resolving it inline in composition would block the main
    // thread on exactly the frames the swipe is animating — and again every
    // time a LazyColumn slot re-enters composition on a long widget list.
    // While the lookup is in flight the card renders a same-size placeholder
    // (below) rather than the "unavailable" text, so a normal load never
    // flashes an error state.
    val initialResolvePending = providerInfoOverride == null &&
        appWidgetManager != null &&
        widgetId !in resolvedProviderInfos
    if (initialResolvePending) {
        LaunchedEffect(widgetId, appWidgetManager) {
            val resolver = resolveProviderInfo
            resolvedProviderInfos[widgetId] = if (resolver != null) {
                resolver(widgetId)
            } else {
                withContext(Dispatchers.IO) { appWidgetManager?.getAppWidgetInfo(widgetId) }
            }
        }
    }
    val resolvedProviderInfo = resolvedProviderInfos[widgetId]
    val providerInfo = providerInfoOverride ?: resolvedProviderInfo
    // A provider can be unresolvable when the card first composes (its app was
    // uninstalled, or its work profile is locked) and become resolvable later
    // (reinstall, profile unlock) without the widget set changing — which means
    // nothing re-runs the `remember` above and the "unavailable" card stays
    // stuck until a full recomposition. While unavailable, re-query on each
    // resume; the effect leaves composition (and stops querying) as soon as the
    // provider resolves, so it's bounded to unavailable cards only. The
    // re-query is `AppWidgetManager.getAppWidgetInfo`, a Binder IPC, so it runs
    // on a background dispatcher and publishes back to Compose rather than
    // blocking the resume / first-frame path on the main thread.
    if (providerInfoOverride == null && resolvedProviderInfo == null && !initialResolvePending && appWidgetManager != null) {
        val lifecycleOwner = LocalLifecycleOwner.current
        val scope = rememberCoroutineScope()
        DisposableEffect(lifecycleOwner, widgetId, appWidgetManager) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    scope.launch {
                        val info = withContext(Dispatchers.IO) { appWidgetManager.getAppWidgetInfo(widgetId) }
                        // Only publish a real resolution; staying null is a
                        // no-op (no recomposition, observer stays armed).
                        if (info != null) resolvedProviderInfos[widgetId] = info
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
    }
    if (initialResolvePending && providerInfo == null) {
        // The provider lookup is still in flight (a frame or two). Reserve the
        // widget's persisted height — or a modest default when it was never
        // resized — so the resolved card swaps in without the list jumping,
        // and keep the card's test tag so the slot is addressable throughout.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((customHeightDp ?: WIDGET_RESOLVING_PLACEHOLDER_HEIGHT_DP).dp)
                .testTag("$WIDGET_CARD_TAG:$widgetId"),
        )
        return
    }
    var menuExpanded by remember { mutableStateOf(initiallyMenuExpanded) }
    // Close the menu the moment this card's page stops being current. A
    // `DropdownMenu` popup positions itself once from the anchor's
    // `LayoutCoordinates` and does not re-track a later `graphicsLayer`-only
    // translation (the carousel's page-slide is exactly that: a draw-phase
    // transform with no new layout pass), so a menu left open while paging
    // away stays frozen at its original screen position — then reappears,
    // looking like a spurious long-press menu, once another page settles
    // under that stale spot. Keyed on isCurrentPage alone, matching the
    // scroll-position reset above, so opening the menu while the page stays
    // current is unaffected.
    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) menuExpanded = false
    }
    if (appWidgetHost == null || providerInfo == null) {
        Box {
            SectionCard(
                Modifier
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true },
                    )
                    .testTag("$WIDGET_CARD_TAG:$widgetId"),
            ) {
                Text(
                    text = stringResource(R.string.widgets_unavailable),
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WidgetActionsMenu(
                expanded = menuExpanded,
                widgetId = widgetId,
                showResize = false,
                canMoveUp = canMoveUp,
                canMoveDown = canMoveDown,
                onDismiss = { menuExpanded = false },
                onRemoveWidget = onRemoveWidget,
                onStartResize = {},
                onMoveWidget = onMoveWidget,
            )
        }
        return
    }

    // A work-profile widget renders the platform's "Couldn't add widget"
    // placeholder while its profile is unavailable, and that placeholder isn't
    // refreshed until the host view re-attaches. Fold the refresh token into the
    // host-view key for work-profile widgets only, so turning the profile back on
    // (which bumps the token) recreates the view and re-fetches RemoteViews.
    // Personal widgets keep a constant key component, so the common case never
    // recreates a host view on a profile toggle.
    val hostViewRefreshKey = remember(providerInfo, workProfileWidgetRefreshToken) {
        // AppWidgetProviderInfo.getProfile() derives the profile from the
        // provider's applicationInfo rather than a plain field, so guard against
        // a malformed info whose applicationInfo is missing. Defaulting an
        // unknown profile to "personal" only forgoes the (rare) refresh nudge —
        // it never recreates a host view spuriously.
        val isWorkProfileWidget = runCatching {
            val profile = providerInfo.profile
            profile != null && profile != Process.myUserHandle()
        }.getOrDefault(false)
        if (isWorkProfileWidget) workProfileWidgetRefreshToken else 0
    }
    val density = LocalDensity.current
    val defaultHeightDp = widgetCardHeight(providerInfo.minHeight, providerInfo.targetCellHeightCompat, density)
    var isResizing by remember { mutableStateOf(initiallyResizing) }
    var resizeHeightDp by remember(customHeightDp, defaultHeightDp) {
        mutableFloatStateOf((customHeightDp?.toFloat() ?: defaultHeightDp.value))
    }
    val effectiveHeightDp = if (isResizing) resizeHeightDp.dp else (customHeightDp?.dp ?: defaultHeightDp)
    var measuredSize by remember(widgetId) { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(effectiveHeightDp)
            .onSizeChanged { measuredSize = it }
            .testTag("$WIDGET_CARD_TAG:$widgetId"),
    ) {
        // Widget rendering jank during a Home -> Widgets swipe was traced to
        // three causes; the remaining open item is hardware-layer promotion.
        // (1) First-time AppWidgetHostView inflation + initial RemoteViews
        // apply on first reveal — addressed by SwipeNavigationBox's
        // `widgetsWarmed` gate (composes widget pages only after the
        // carousel claims a horizontal gesture toward Widgets) plus
        // LauncherAppWidgetHost.deferRemoteViewsApply, which parks the
        // UI-thread RemoteViews.apply() while the carousel is in motion
        // and flushes on settle. The provider's background data fetch
        // still runs during the drag (host stays listening), so by the
        // time the deferral lifts the latest RemoteViews are ready and
        // paint in one pass after settle. (2) Provider self-invalidations
        // (clock ticks, calendar refreshes, weather updates) landing during
        // the swipe — same defer-apply pipe handles them: queued in the
        // host, flushed on settle. Velocity tracker bias from any single
        // stalled drag frame is additionally mitigated by feeding
        // change.historical samples into the tracker. (3) Open: RemoteViews
        // layouts don't promote to a hardware layer, so the graphicsLayer
        // translation can't cheaply re-translate a cached RenderNode.
        // Worth trying: wrap each hosted widget in
        // `Modifier.graphicsLayer { compositingStrategy =
        // CompositingStrategy.Offscreen }` so the carousel translation
        // re-blits a cached RenderNode at the cost of width × height × 4 B
        // of extra GPU memory per widget.
        // Key the host view on widgetId so two widgets from the same provider
        // never share one AppWidgetHostView instance. Without the key, when a
        // LazyColumn slot is recycled (scroll, reorder) the AndroidView's
        // factory — the only place the host view is bound to an appWidgetId —
        // is not re-run, so a recycled view keeps the previous widget's binding
        // and both cards end up showing the same widget. The key forces the old
        // AndroidView to be disposed and a fresh one created for the new id.
        key(widgetId, hostViewRefreshKey) {
            AndroidView(
                factory = { context ->
                    val hostView = createWidgetView?.invoke(context, widgetId)
                        ?: appWidgetHost.createView(context, widgetId, providerInfo)
                    hostView.apply {
                        setAppWidget(widgetId, providerInfo)
                        if (this is LauncherAppWidgetHostView) {
                            setOnWidgetLongPressListener { menuExpanded = true }
                        } else {
                            setOnLongClickListener {
                                menuExpanded = true
                                true
                            }
                        }
                    }
                },
                update = { view ->
                    // Push the host view's measured size to the provider via
                    // AppWidgetManager options. Adaptive widgets — Google Clock's
                    // world clocks, calendar agendas, and other layouts that scale
                    // their content to the available space — read these options
                    // from getAppWidgetOptions() to decide what to render. Without
                    // them the options bundle stays empty and providers fall back
                    // to their zero/empty layout, which appears as a blank widget
                    // card even though the host view occupies the full height.
                    //
                    // Withheld while a resize drag is active: every drag frame
                    // changes the box height by >= 1 dp, so pushing per-frame
                    // defeated the applyAppWidgetSizeIfChanged cache and paid
                    // one binder IPC + provider onAppWidgetOptionsChanged wake
                    // + one persisted-cache prefs write per frame on the
                    // gesture's hot path. The final size lands when the drag
                    // ends — isResizing flips false, which re-runs this update
                    // block with the settled measurement.
                    if (!isResizing) {
                        widgetSizeHintDp(measuredSize, density)?.let { (widthDp, heightDp) ->
                            // Use the public min/max overload — the SizeF list
                            // variant is @hide in AOSP and not part of the SDK.
                            // Routes through `applyAppWidgetSizeIfChanged` so a
                            // layout pass that produces the same dimensions as
                            // last time (the typical case — same device, same
                            // persisted widget height) skips the binder IPC and
                            // avoids waking the provider via
                            // `onAppWidgetOptionsChanged`. Cache is persisted, so
                            // the first layout pass after a cold start short-
                            // circuits too whenever the size is unchanged.
                            val launcherHost = appWidgetHost as? LauncherAppWidgetHost
                            if (launcherHost != null) {
                                launcherHost.applyAppWidgetSizeIfChanged(view, widgetId, widthDp, heightDp)
                            } else {
                                view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
                            }
                        }
                    }
                    if (view is LauncherAppWidgetHostView) {
                        view.setOnWidgetLongPressListener { if (!isResizing) menuExpanded = true }
                    } else {
                        view.setOnLongClickListener {
                            if (!isResizing) menuExpanded = true
                            true
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        WidgetActionsMenu(
            expanded = menuExpanded,
            widgetId = widgetId,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onDismiss = { menuExpanded = false },
            onRemoveWidget = onRemoveWidget,
            onStartResize = {
                resizeHeightDp = effectiveHeightDp.value
                isResizing = true
            },
            onMoveWidget = onMoveWidget,
        )
        if (isResizing) {
            WidgetResizeHandle(
                onDrag = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp().value }
                    resizeHeightDp = (resizeHeightDp + deltaDp).coerceAtLeast(WIDGET_MIN_HEIGHT_DP.toFloat())
                },
                onDragEnd = {
                    onResizeWidget(resizeHeightDp.roundToInt())
                    isResizing = false
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun WidgetResizeHandle(
    onDrag: (deltaPx: Float) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resizeHandleDescription = stringResource(R.string.widget_resize_handle_description)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(WIDGET_RESIZE_HANDLE_HEIGHT_DP.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta -> onDrag(delta) },
                onDragStopped = { onDragEnd() },
            )
            .semantics { contentDescription = resizeHandleDescription }
            .testTag(WIDGET_RESIZE_HANDLE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WidgetActionsMenu(
    expanded: Boolean,
    widgetId: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    showResize: Boolean = true,
    onDismiss: () -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onStartResize: () -> Unit,
    onMoveWidget: (widgetId: Int, direction: WidgetMoveDirection) -> Unit,
) {
    LauncherDropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showResize) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.widget_menu_resize)) },
                modifier = Modifier.testTag("$RESIZE_WIDGET_ACTION_TAG:$widgetId"),
                onClick = {
                    onDismiss()
                    onStartResize()
                },
            )
        }
        if (canMoveUp) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.widget_menu_move_up)) },
                modifier = Modifier.testTag("$MOVE_UP_WIDGET_ACTION_TAG:$widgetId"),
                onClick = {
                    onDismiss()
                    onMoveWidget(widgetId, WidgetMoveDirection.UP)
                },
            )
        }
        if (canMoveDown) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.widget_menu_move_down)) },
                modifier = Modifier.testTag("$MOVE_DOWN_WIDGET_ACTION_TAG:$widgetId"),
                onClick = {
                    onDismiss()
                    onMoveWidget(widgetId, WidgetMoveDirection.DOWN)
                },
            )
        }
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.widget_menu_remove)) },
            modifier = Modifier.testTag("$REMOVE_WIDGET_ACTION_TAG:$widgetId"),
            onClick = {
                onDismiss()
                onRemoveWidget(widgetId)
            },
        )
    }
}

internal fun widgetCardHeight(minHeightPx: Int, targetCellHeight: Int, density: Density): Dp {
    val fromMinHeight = with(density) { minHeightPx.toDp() }
    val fromCells = (targetCellHeight * WIDGET_CELL_HEIGHT_DP).dp
    return maxOf(fromMinHeight, fromCells, WIDGET_MIN_HEIGHT_DP.dp)
}

internal data class WidgetSizeHintDp(val widthDp: Int, val heightDp: Int)

/**
 * Converts a hosted widget's measured pixel size into the dp values reported
 * to the provider via `AppWidgetHostView.updateAppWidgetSize`. Returns `null`
 * for `IntSize.Zero`, so the caller skips the framework call before the host
 * view has been laid out.
 */
internal fun widgetSizeHintDp(sizePx: IntSize, density: Density): WidgetSizeHintDp? {
    if (sizePx == IntSize.Zero) return null
    val widthDp = with(density) { sizePx.width.toDp().value.toInt() }
    val heightDp = with(density) { sizePx.height.toDp().value.toInt() }
    return WidgetSizeHintDp(widthDp, heightDp)
}

private val AppWidgetProviderInfo.targetCellHeightCompat: Int
    get() = targetCellHeight.takeIf { it > 0 } ?: 1

private data class WidgetPreviewValue(
    val generated: RemoteViews?,
    val image: Drawable?,
)

internal data class WidgetGroupKey(
    val appName: String,
    val isWorkProvider: Boolean,
) {
    /**
     * Suffix used in compose test tags. Plain `appName` for personal-profile
     * groups (so existing tests continue matching `widget_app_row:Calendar`)
     * and `appName|work` for work-profile groups so the same display name in a
     * different profile is targetable.
     */
    val tagSuffix: String
        get() = if (isWorkProvider) "$appName|work" else appName
}

private fun WidgetProvider.preview(appWidgetManager: AppWidgetManager?, context: Context): WidgetPreviewValue {
    val generated = if (Build.VERSION.SDK_INT >= GENERATED_WIDGET_PREVIEW_MIN_API) {
        try {
            appWidgetManager?.getWidgetPreview(
                componentName,
                profile,
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
            )
        } catch (_: RuntimeException) {
            null
        }
    } else {
        null
    }
    return WidgetPreviewValue(generated = generated, image = previewImage)
}

private fun WidgetProvider.icon(): Drawable? = icon ?: appIcon

@Composable
private fun widgetProviderCountLabel(providerCount: Int): String =
    pluralStringResource(R.plurals.widgets_picker_widget_count, providerCount, providerCount)

private const val ADD_WIDGET_CARD_HEIGHT_DP = 112

// Height reserved while a hosted widget's provider info is resolving and no
// persisted height is available. Matches the add-widget card so the brief
// placeholder reads as a card-sized surface rather than a sliver.
private const val WIDGET_RESOLVING_PLACEHOLDER_HEIGHT_DP = 112
private const val WIDGET_MIN_HEIGHT_DP = 64
private const val WIDGET_CELL_HEIGHT_DP = 64
private const val WIDGET_RESIZE_HANDLE_HEIGHT_DP = 32
private const val WIDGET_PROVIDER_PREVIEW_HEIGHT_DP = 120
private const val GENERATED_WIDGET_PREVIEW_MIN_API = 35
