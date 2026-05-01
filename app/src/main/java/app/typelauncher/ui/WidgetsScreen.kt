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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    appWidgetManager: AppWidgetManager?,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    SectionCard(Modifier.testTag(WIDGET_PICKER_TAG)) {
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
        if (availableWidgets.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Widgets,
                title = stringResource(R.string.widgets_picker_empty_title),
                body = stringResource(R.string.widgets_picker_empty_body),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Column(
                modifier = Modifier.testTag(WIDGET_PICKER_LIST_TAG),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                availableWidgets.groupBy { provider -> provider.appName }.forEach { (appName, providers) ->
                    WidgetAppSection(
                        appName = appName,
                        providers = providers,
                        appWidgetManager = appWidgetManager,
                        onSelectWidget = onSelectWidget,
                    )
                }
            }
        }
    }
}

@Composable
private fun WidgetAppSection(
    appName: String,
    providers: List<WidgetProvider>,
    appWidgetManager: AppWidgetManager?,
    onSelectWidget: (WidgetProvider) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("$WIDGET_APP_HEADER_TAG:$appName"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = appName,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        providers.forEach { provider ->
            WidgetProviderRow(
                provider = provider,
                appWidgetManager = appWidgetManager,
                onSelectWidget = onSelectWidget,
            )
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
            .clickable { onSelectWidget(provider) }
            .semantics { role = Role.Button }
            .testTag("$WIDGET_PROVIDER_ROW_TAG:${provider.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WidgetPreview(
                provider = provider,
                appWidgetManager = appWidgetManager,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WIDGET_PROVIDER_PREVIEW_HEIGHT_DP.dp)
                    .testTag("$WIDGET_PREVIEW_TAG:${provider.id}"),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WidgetIcon(provider)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        when {
            preview.generated != null -> AndroidView(
                factory = { viewContext ->
                    preview.generated.apply(viewContext, FrameLayout(viewContext)).also { view ->
                        view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
                    setOnLongClickListener {
                        menuExpanded = true
                        true
                    }
                }
            },
            update = { view ->
                view.setOnLongClickListener {
                    menuExpanded = true
                    true
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
    return WidgetPreviewValue(generated = generated, image = generated?.let { null } ?: previewImage)
}

private fun WidgetProvider.icon(): Drawable? = icon ?: appIcon

private const val ADD_WIDGET_CARD_HEIGHT_DP = 112
private const val WIDGET_MIN_HEIGHT_DP = 96
private const val WIDGET_PROVIDER_PREVIEW_HEIGHT_DP = 120
private const val GENERATED_WIDGET_PREVIEW_MIN_API = 36
