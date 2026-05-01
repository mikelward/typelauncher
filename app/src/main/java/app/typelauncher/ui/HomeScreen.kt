package app.typelauncher

import android.view.KeyEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val dockIconSizeDp = dockIconSizeForSlotCount(configuration.screenWidthDp, state.dockIconCount)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(HOME_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchCard(
            query = state.query,
            onQueryChanged = onQueryChanged,
            onClearQuery = onClearQuery,
            onOpenSettings = onOpenSettings,
            onLaunchActiveApp = onLaunchActiveApp,
        )
        AppsCard(
            apps = state.filteredApps,
            dockLimit = Int.MAX_VALUE,
            isIconOnly = state.isAppListIconOnly,
            iconSizeDp = dockIconSizeDp,
            modifier = Modifier.weight(1f),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
        if (state.isDockEnabled) {
            DockCard(
                dockedApps = state.dockedApps,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
            )
        }
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchActiveApp: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    SectionCard {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER && event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                        onLaunchActiveApp()
                        true
                    } else {
                        false
                    }
                }
                .testTag(SEARCH_FIELD_TAG),
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClearQuery) {
                        Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.app_search_clear_button_description))
                    }
                } else {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag(SETTINGS_BUTTON_TAG),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_open_button_description))
                    }
                }
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.app_search_hint)) },
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus(force = false)
                    onLaunchActiveApp()
                },
            ),
        )
    }
}

@Composable
private fun DockCard(
    dockedApps: List<InstalledApp>,
    dockIconSizeDp: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    SectionCard(modifier.testTag(DOCK_CARD_TAG)) {
        if (dockedApps.isEmpty()) {
            Text(
                text = stringResource(R.string.dock_apps_hint),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
                    .testTag(DOCK_HINT_TAG),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag(DOCK_LIST_TAG),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dockedApps.forEach { app ->
                    DockedAppButton(
                        app = app,
                        dockIconSizeDp = dockIconSizeDp,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    dockLimit: Int,
    isIconOnly: Boolean,
    iconSizeDp: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    SectionCard(modifier.testTag(APPS_CARD_TAG)) {
        if (apps.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.home_empty_title),
                body = stringResource(R.string.home_empty_body),
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            if (isIconOnly) {
                IconOnlyAppGrid(
                    apps = apps,
                    dockLimit = dockLimit,
                    iconSizeDp = iconSizeDp,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(APPS_LIST_TAG),
                ) {
                    items(apps, key = { app -> app.id }) { app ->
                        AppRow(
                            app = app,
                            isActive = app == apps.first(),
                            dockLimit = dockLimit,
                            onLaunchApp = onLaunchApp,
                            onOpenAppInfo = onOpenAppInfo,
                            onToggleDock = onToggleDock,
                            onResetRank = onResetRank,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun IconOnlyAppGrid(
    apps: List<InstalledApp>,
    dockLimit: Int,
    iconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(iconSizeDp.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = iconSizeDp.dp)
            .testTag(APPS_LIST_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(apps, key = { app -> app.id }) { app ->
            IconOnlyAppButton(
                app = app,
                isActive = app == apps.first(),
                dockLimit = dockLimit,
                iconSizeDp = iconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
            )
        }
    }
}

@Composable
private fun IconOnlyAppButton(
    app: InstalledApp,
    isActive: Boolean,
    dockLimit: Int,
    iconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Box {
        Column(
            modifier = Modifier
                .background(containerColor, MaterialTheme.shapes.medium)
                .semantics {
                    contentDescription = app.name
                    selected = isActive
                }
                .padding(4.dp)
                .testTag("$APP_ICON_ONLY_BUTTON_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = iconSizeDp.dp, testTag = APP_ICON_ONLY_ICON_TAG)
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onLaunchApp(app) },
                    onLongClick = { menuExpanded = true },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = app.name
                    selected = isActive
                },
        )
        AppActionsMenu(
            expanded = menuExpanded,
            app = app,
            dockLimit = dockLimit,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    isActive: Boolean,
    dockLimit: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    val rowColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val textColor = if (isActive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onBackground
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor, MaterialTheme.shapes.medium)
                .semantics { selected = isActive }
                .combinedClickable(
                    onClick = { onLaunchApp(app) },
                    onLongClick = { menuExpanded = true },
                )
                .padding(horizontal = 4.dp, vertical = 8.dp)
                .testTag("$APP_ROW_TAG:${app.name}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(app = app, size = 40.dp)
            Text(
                app.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = textColor,
            )
        }
        AppActionsMenu(
            expanded = menuExpanded,
            app = app,
            dockLimit = dockLimit,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun AppActionsMenu(
    expanded: Boolean,
    app: InstalledApp,
    dockLimit: Int,
    onDismiss: () -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onOpenAppInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (app.isDocked) R.string.app_menu_undock else R.string.app_menu_dock)) },
            modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onToggleDock(app, dockLimit)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_reset_rank)) },
            modifier = Modifier.testTag("$RESET_RANK_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onResetRank(app)
            },
        )
    }
}

@Composable
private fun DockedAppButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.name }
                .padding(4.dp)
                .testTag("$DOCK_APP_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = dockIconSizeDp.dp, testTag = DOCK_APP_ICON_TAG)
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onLaunchApp(app) },
                    onLongClick = { menuExpanded = true },
                )
                .semantics {
                    role = Role.Button
                    contentDescription = app.name
                },
        )
        AppActionsMenu(
            expanded = menuExpanded,
            app = app,
            dockLimit = Int.MAX_VALUE,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
internal fun SettingsScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    onCloseSettings: () -> Unit,
    onRequestDefaultLauncher: () -> Unit,
    onDockEnabledChanged: (Boolean) -> Unit,
    onAppListIconOnlyChanged: (Boolean) -> Unit,
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val slotCountRange = dockSlotCountRange(configuration.screenWidthDp)
    val dockIconCount = state.dockIconCount.coerceIn(slotCountRange)
    val dockIconSizeDp = dockIconSizeForSlotCount(configuration.screenWidthDp, dockIconCount)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .testTag(SETTINGS_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Button(
                onClick = onCloseSettings,
                modifier = Modifier.testTag(SETTINGS_DONE_BUTTON_TAG),
            ) {
                Text(stringResource(R.string.settings_done_button))
            }
        }
        Button(
            onClick = onRequestDefaultLauncher,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DEFAULT_LAUNCHER_BUTTON_TAG),
        ) {
            Text(stringResource(R.string.settings_default_launcher_button))
        }
        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_dock_enabled_title), style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    checked = state.isDockEnabled,
                    onCheckedChange = onDockEnabledChanged,
                    modifier = Modifier.testTag(DOCK_ENABLED_SWITCH_TAG),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_app_list_icon_only_title), style = MaterialTheme.typography.titleMedium)
                }
                Switch(
                    checked = state.isAppListIconOnly,
                    onCheckedChange = onAppListIconOnlyChanged,
                    modifier = Modifier.testTag(APP_LIST_ICON_ONLY_SWITCH_TAG),
                )
            }
            Text(
                text = stringResource(R.string.settings_dock_icon_count_label, dockIconCount),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = dockIconCount.toFloat(),
                onValueChange = { value -> onDockVisibleIconCountChanged(value.roundToInt()) },
                valueRange = slotCountRange.first.toFloat()..slotCountRange.last.toFloat(),
                steps = (slotCountRange.last - slotCountRange.first - 1).coerceAtLeast(0),
                modifier = Modifier.testTag(DOCK_ICON_COUNT_SLIDER_TAG),
            )
            Text(
                text = stringResource(R.string.settings_dock_icon_size_value, dockIconSizeDp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.settings_dock_preview_label),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        SettingsPreview(
            state = state,
            dockIconSizeDp = dockIconSizeDp,
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
    }
}

@Composable
private fun SettingsPreview(
    state: LauncherUiState,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
) {
    val previewHeight = (dockIconSizeDp + SETTINGS_PREVIEW_CARD_CHROME_DP).dp
    val appListHeight = if (state.isDockEnabled) previewHeight else previewHeight * 2 + SETTINGS_PREVIEW_SPACING_DP.dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_PREVIEW_SPACING_DP.dp),
    ) {
        AppsCard(
            apps = state.filteredApps,
            dockLimit = Int.MAX_VALUE,
            isIconOnly = state.isAppListIconOnly,
            iconSizeDp = dockIconSizeDp,
            modifier = Modifier.height(appListHeight),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
        )
        if (state.isDockEnabled) {
            DockCard(
                dockedApps = state.dockedApps,
                dockIconSizeDp = dockIconSizeDp,
                modifier = Modifier.height(previewHeight),
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
            )
        }
    }
}

@Composable
private fun AppIcon(app: InstalledApp, size: androidx.compose.ui.unit.Dp, testTag: String = APP_ICON_TAG) {
    val bitmap = remember(app.id, app.icon) {
        app.icon?.toBitmap(width = 96, height = 96)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(size)
            .testTag("$testTag:${app.name}"),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
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
                    Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(8.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (app.isWorkApp) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .testTag("$WORK_APP_BADGE_TAG:${app.name}"),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) {
                Icon(
                    Icons.Filled.Badge,
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private const val MIN_DOCKED_APPS = 1
private const val SETTINGS_PREVIEW_CARD_CHROME_DP = 40
private const val SETTINGS_PREVIEW_SPACING_DP = 16
