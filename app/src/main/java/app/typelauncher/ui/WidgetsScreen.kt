package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap

@Composable
internal fun WidgetsScreen(
    widgetIds: List<Int>,
    availableWidgets: List<WidgetProvider>,
    isAddingWidget: Boolean,
    isLoadingAvailableWidgets: Boolean = false,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    innerPadding: PaddingValues,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(WIDGETS_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            AddWidgetCard(onAddWidget = onAddWidget)
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
        items(widgetIds, key = { widgetId -> widgetId }) { widgetId ->
            HostedWidgetCard(
                widgetId = widgetId,
                appWidgetHost = appWidgetHost,
                appWidgetManager = appWidgetManager,
                onRemoveWidget = onRemoveWidget,
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
        var expandedAppName by remember { mutableStateOf<String?>(null) }
        var expandedProviderId by remember { mutableStateOf<String?>(null) }
        var filterQuery by remember { mutableStateOf("") }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.widgets_picker_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.widgets_picker_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onDismissWidgetPicker) {
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
                    expandedAppName = null
                    expandedProviderId = null
                },
            )
            // TODO: also filter individual widget labels within an app group, not just the app group names.
            val filteredGroups = availableWidgets
                .groupBy { provider -> provider.appName }
                .filterKeys { appName -> appName.contains(filterQuery.trim(), ignoreCase = true) }
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
                    filteredGroups.forEach { (appName, providers) ->
                        val isAppExpanded = expandedAppName == appName
                        WidgetAppSection(
                            appName = appName,
                            providers = providers,
                            isExpanded = isAppExpanded,
                            expandedProviderId = if (isAppExpanded) expandedProviderId else null,
                            appWidgetManager = appWidgetManager,
                            onToggleExpanded = {
                                expandedAppName = if (isAppExpanded) null else appName
                                expandedProviderId = null
                            },
                            onToggleProvider = { provider ->
                                expandedProviderId = if (expandedProviderId == provider.id) null else provider.id
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
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(WIDGET_PICKER_FILTER_TAG),
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChanged("") },
                    modifier = Modifier.testTag(WIDGET_PICKER_FILTER_CLEAR_TAG),
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.widgets_picker_filter_clear_description),
                    )
                }
            }
        },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.widgets_picker_filter_hint)) },
        textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search,
        ),
    )
}

@Composable
private fun WidgetAppSection(
    appName: String,
    providers: List<WidgetProvider>,
    isExpanded: Boolean,
    expandedProviderId: String?,
    appWidgetManager: AppWidgetManager?,
    onToggleExpanded: () -> Unit,
    onToggleProvider: (WidgetProvider) -> Unit,
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
                .testTag("$WIDGET_APP_ROW_TAG:$appName"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetAppIcon(providers.firstOrNull()?.appIcon)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = appName,
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
                    isExpanded = expandedProviderId == provider.id,
                    appWidgetManager = appWidgetManager,
                    onToggleExpanded = { onToggleProvider(provider) },
                    onSelectWidget = onSelectWidget,
                )
            }
        }
    }
}

@Composable
private fun WidgetProviderRow(
    provider: WidgetProvider,
    isExpanded: Boolean,
    appWidgetManager: AppWidgetManager?,
    onToggleExpanded: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .semantics { role = Role.Button }
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
                }
                Text(
                    text = if (isExpanded) {
                        stringResource(R.string.widgets_picker_hide_preview)
                    } else {
                        stringResource(R.string.widgets_picker_preview_provider)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (isExpanded) {
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
}

@Composable
private fun WidgetPreview(
    provider: WidgetProvider,
    appWidgetManager: AppWidgetManager?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val preview = remember(provider, appWidgetManager) {
        provider.preview(appWidgetManager, context)
    }
    var generatedInflationFailed by remember(preview.generated) { mutableStateOf(false) }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        when {
            preview.generated != null && !generatedInflationFailed -> AndroidView(
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
            preview.image != null -> Image(
                bitmap = preview.image.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WIDGET_PROVIDER_PREVIEW_HEIGHT_DP.dp),
                contentScale = ContentScale.Fit,
            )
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
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (appIcon != null) {
            Image(
                bitmap = appIcon.toBitmap().asImageBitmap(),
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
private fun WidgetIcon(provider: WidgetProvider) {
    val icon = remember(provider) { provider.icon() }
    Box(
        modifier = Modifier.size(36.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon.toBitmap().asImageBitmap(),
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
private fun AddWidgetCard(onAddWidget: () -> Unit) {
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
            .testTag(ADD_WIDGET_CARD_TAG),
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
private fun HostedWidgetCard(
    widgetId: Int,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onRemoveWidget: (Int) -> Unit,
) {
    val providerInfo = remember(widgetId, appWidgetManager) {
        appWidgetManager?.getAppWidgetInfo(widgetId)
    }
    var menuExpanded by remember { mutableStateOf(false) }
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
                onDismiss = { menuExpanded = false },
                onRemoveWidget = onRemoveWidget,
            )
        }
        return
    }

    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(widgetCardHeight(providerInfo.minHeight, density))
            .testTag("$WIDGET_CARD_TAG:$widgetId"),
    ) {
        AndroidView(
            factory = { context ->
                appWidgetHost.createView(context, widgetId, providerInfo).apply {
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
                if (view is LauncherAppWidgetHostView) {
                    view.setOnWidgetLongPressListener { menuExpanded = true }
                } else {
                    view.setOnLongClickListener {
                        menuExpanded = true
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        WidgetActionsMenu(
            expanded = menuExpanded,
            widgetId = widgetId,
            onDismiss = { menuExpanded = false },
            onRemoveWidget = onRemoveWidget,
        )
    }
}

@Composable
private fun WidgetActionsMenu(
    expanded: Boolean,
    widgetId: Int,
    onDismiss: () -> Unit,
    onRemoveWidget: (Int) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.widget_menu_remove)) },
            modifier = Modifier.testTag("$REMOVE_WIDGET_ACTION_TAG:$widgetId"),
            onClick = {
                onDismiss()
                onRemoveWidget(widgetId)
            },
        )
    }
}

internal fun widgetCardHeight(minHeightPx: Int, density: Density): Dp =
    with(density) { minHeightPx.toDp() }.coerceAtLeast(WIDGET_MIN_HEIGHT_DP.dp)

private data class WidgetPreviewValue(
    val generated: RemoteViews?,
    val image: Drawable?,
)

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

private fun widgetProviderCountLabel(providerCount: Int): String =
    if (providerCount == 1) {
        "1 widget"
    } else {
        "$providerCount widgets"
    }

private const val ADD_WIDGET_CARD_HEIGHT_DP = 112
private const val WIDGET_MIN_HEIGHT_DP = 96
private const val WIDGET_PROVIDER_PREVIEW_HEIGHT_DP = 120
private const val GENERATED_WIDGET_PREVIEW_MIN_API = 35
