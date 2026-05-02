package app.typelauncher

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    bodyReady: Boolean,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onSetNotificationBarOpen: (Boolean) -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
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
        // `bodyReady` flips one frame after TypeLauncherApp first composes,
        // and stays true for the lifetime of the activity composition: the
        // holdback is a cold-start optimisation, not a per-mount one. See the
        // comment on `homeBodyReady` in TypeLauncherApp for the why.
        if (bodyReady) {
            AppsCard(
                apps = state.filteredApps,
                isLoading = state.isLoadingApps,
                dockLimit = Int.MAX_VALUE,
                isIconOnly = state.isAppListIconOnly,
                iconSizeDp = dockIconSizeDp,
                highlightFirst = state.query.isNotBlank(),
                modifier = Modifier.weight(1f),
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
            )
            // Notification bar sits between the app list and the dock so a single
            // pull-down brings it into view without displacing the dock or the
            // keyboard. The list above shrinks (it has weight 1f) to make room.
            NotificationBarCard(
                notifyingApps = state.notifyingApps,
                isVisible = state.isNotificationBarOpen,
                hasNotificationAccess = state.hasNotificationAccess,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
                onRequestNotificationAccess = onRequestNotificationAccess,
                onDismiss = { onSetNotificationBarOpen(false) },
            )
            if (state.isDockEnabled) {
                DockCard(
                    dockedApps = state.dockedApps,
                    dockIconSizeDp = dockIconSizeDp,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                    onHideApp = onHideApp,
                )
            }
            // Recents lives in its own card below the dock so it can render
            // independently of `isDockEnabled`. The drag-up gesture on the dock
            // and the `Show recents` setting are orthogonal triggers — either
            // is enough to make the card appear.
            RecentsCard(
                recentApps = state.recentApps,
                isVisible = state.isRecentsAlwaysShown || state.isRecentsOpen,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
            )
        } else {
            // Reserve the remaining vertical space so SearchCard stays pinned
            // to the top of the screen during the one-frame holdback.
            Spacer(modifier = Modifier.weight(1f))
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
    onHideApp: (InstalledApp) -> Unit,
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
            ScrollableIconRow(
                rowModifier = Modifier.testTag(DOCK_LIST_TAG),
                startChevronTestTag = DOCK_SCROLL_START_CHEVRON_TAG,
                endChevronTestTag = DOCK_SCROLL_END_CHEVRON_TAG,
                chevronContentDescription = stringResource(R.string.dock_scroll_more_hint),
            ) {
                dockedApps.forEach { app ->
                    DockedAppButton(
                        app = app,
                        dockIconSizeDp = dockIconSizeDp,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onHideApp = onHideApp,
                    )
                }
            }
        }
    }
}

/**
 * Recents card sits below the dock and above the keyboard. Visibility is
 * controlled by either the persistent `Show recents` setting or the transient
 * drag-up gesture on the dock; the two triggers are orthogonal — either alone
 * surfaces the card. When the card is hidden the composable collapses to zero
 * height (no dangling chrome), so swapping to the always-on setting just makes
 * what would otherwise be a transient panel into a permanent home-screen row.
 */
@Composable
private fun RecentsCard(
    recentApps: List<InstalledApp>,
    isVisible: Boolean,
    dockIconSizeDp: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        SectionCard(modifier.testTag(DOCK_RECENTS_CARD_TAG)) {
            RecentsRow(
                recentApps = recentApps,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
            )
        }
    }
}

@Composable
private fun NotificationBarCard(
    notifyingApps: List<InstalledApp>,
    isVisible: Boolean,
    hasNotificationAccess: Boolean,
    dockIconSizeDp: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onRequestNotificationAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = isVisible,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        SectionCard(modifier.testTag(NOTIFICATION_BAR_CARD_TAG)) {
            when {
                !hasNotificationAccess -> NotificationBarPermissionCta(
                    onRequestNotificationAccess = {
                        onDismiss()
                        onRequestNotificationAccess()
                    },
                )
                notifyingApps.isEmpty() -> Text(
                    text = stringResource(R.string.notification_bar_empty_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp)
                        .testTag(NOTIFICATION_BAR_HINT_TAG),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> NotificationBarRow(
                    notifyingApps = notifyingApps,
                    dockIconSizeDp = dockIconSizeDp,
                    onLaunchApp = { app ->
                        onDismiss()
                        onLaunchApp(app)
                    },
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                    onHideApp = onHideApp,
                )
            }
        }
    }
}

@Composable
private fun NotificationBarPermissionCta(onRequestNotificationAccess: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.notification_bar_permission_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onRequestNotificationAccess,
            modifier = Modifier.testTag(NOTIFICATION_BAR_PERMISSION_BUTTON_TAG),
        ) {
            Text(stringResource(R.string.notification_bar_permission_button))
        }
    }
}

@Composable
private fun NotificationBarRow(
    notifyingApps: List<InstalledApp>,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    val description = stringResource(R.string.notification_bar_description)
    ScrollableIconRow(
        rowModifier = Modifier
            .semantics { contentDescription = description }
            .testTag(NOTIFICATION_BAR_LIST_TAG),
        startChevronTestTag = NOTIFICATION_BAR_SCROLL_START_CHEVRON_TAG,
        endChevronTestTag = NOTIFICATION_BAR_SCROLL_END_CHEVRON_TAG,
        chevronContentDescription = description,
        // Newest notification sits at the end of the row; auto-scroll to the
        // end whenever the list contents change so the freshest entry stays
        // visible without the user having to swipe.
        pinToEndKey = notifyingApps.map { it.id },
    ) {
        notifyingApps.forEach { app ->
            NotifyingAppButton(
                app = app,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
            )
        }
    }
}

@Composable
private fun NotifyingAppButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val badgeDescription = stringResource(R.string.notification_bar_badge_description)
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.name }
                .padding(4.dp)
                .testTag("$NOTIFICATION_BAR_APP_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                AppIcon(app = app, size = dockIconSizeDp.dp, testTag = NOTIFICATION_BAR_APP_ICON_TAG)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(NOTIFICATION_BADGE_SIZE_DP.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        )
                        .semantics { contentDescription = badgeDescription }
                        .testTag("$NOTIFICATION_BAR_BADGE_TAG:${app.name}"),
                )
            }
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
            onHideApp = onHideApp,
        )
    }
}

@Composable
private fun RecentsRow(
    recentApps: List<InstalledApp>,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    if (recentApps.isEmpty()) {
        Text(
            text = stringResource(R.string.dock_recents_empty_hint),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag(DOCK_RECENTS_HINT_TAG),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val description = stringResource(R.string.dock_recents_description)
    ScrollableIconRow(
        rowModifier = Modifier
            .semantics { contentDescription = description }
            .testTag(DOCK_RECENTS_LIST_TAG),
        startChevronTestTag = DOCK_RECENTS_SCROLL_START_CHEVRON_TAG,
        endChevronTestTag = DOCK_RECENTS_SCROLL_END_CHEVRON_TAG,
        chevronContentDescription = stringResource(R.string.dock_recents_scroll_more_hint),
        // Keep the freshest recent app (rightmost) visible after every launch.
        pinToEndKey = recentApps.map { it.id },
    ) {
        recentApps.forEach { app ->
            RecentAppButton(
                app = app,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
            )
        }
    }
}

@Composable
private fun RecentAppButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.name }
                .padding(4.dp)
                .testTag("$DOCK_RECENTS_APP_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = dockIconSizeDp.dp, testTag = DOCK_RECENTS_APP_ICON_TAG)
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
            onHideApp = onHideApp,
        )
    }
}

/**
 * Wraps a horizontally scrollable row of icons (the dock or the recents row) and
 * overlays start/end chevrons on whichever edge has more content scrolled past.
 * The chevron uses an auto-mirrored icon and start/end alignment so it points
 * the right direction under RTL.
 *
 * When [pinToEndKey] is non-null, the row scrolls to its end whenever the key
 * (or the row's own measured `maxValue`) changes. The recents row uses this so
 * the most-recently-launched app — which sits at the right edge — stays
 * visible when the recents list overflows the row width; the dock leaves it
 * null and stays anchored at the start.
 */
@Composable
private fun ScrollableIconRow(
    startChevronTestTag: String,
    endChevronTestTag: String,
    chevronContentDescription: String,
    rowModifier: Modifier = Modifier,
    pinToEndKey: Any? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    if (pinToEndKey != null) {
        LaunchedEffect(pinToEndKey, scrollState.maxValue) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }
    // Track overflow from the layout pass directly rather than from
    // `scrollState.maxValue`, which defaults to Int.MAX_VALUE before the
    // first layout pass and would otherwise make the end chevron flash on
    // every initial composition.
    var contentOverflowsViewport by remember { mutableStateOf(false) }
    val showEndChevron by remember(scrollState) {
        derivedStateOf { contentOverflowsViewport && scrollState.value < scrollState.maxValue }
    }
    val showStartChevron by remember(scrollState) {
        derivedStateOf { contentOverflowsViewport && scrollState.value > 0 }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Stretch the row to at least the viewport width so the centered
        // arrangement has space to distribute when the icons fit on one
        // screen, but use the raw px from `BoxWithConstraints.constraints`
        // (not `maxWidth.dp`) — the Dp round-trip can land 1 px above the
        // viewport on non-integer densities and trip a spurious overflow
        // chevron when the row content actually fits.
        val viewportPx = constraints.maxWidth
        Row(
            modifier = rowModifier
                .horizontalScroll(scrollState)
                .layout { measurable, childConstraints ->
                    val placeable = measurable.measure(
                        childConstraints.copy(minWidth = viewportPx),
                    )
                    contentOverflowsViewport = placeable.width > viewportPx
                    layout(placeable.width, placeable.height) {
                        placeable.place(0, 0)
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            content = content,
        )
        if (showStartChevron) {
            OverflowScrollChevron(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = chevronContentDescription,
                alignment = Alignment.CenterStart,
                testTag = startChevronTestTag,
            )
        }
        if (showEndChevron) {
            OverflowScrollChevron(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = chevronContentDescription,
                alignment = Alignment.CenterEnd,
                testTag = endChevronTestTag,
            )
        }
    }
}

@Composable
private fun BoxScope.OverflowScrollChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    alignment: Alignment,
    testTag: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .align(alignment)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = CircleShape,
            )
            .padding(2.dp)
            .testTag(testTag),
    )
}

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    isLoading: Boolean = false,
    dockLimit: Int,
    isIconOnly: Boolean,
    iconSizeDp: Int,
    highlightFirst: Boolean,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    SectionCard(modifier.testTag(APPS_CARD_TAG)) {
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp)
                    .testTag(APPS_LOADING_TAG),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (apps.isEmpty()) {
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
                    highlightFirst = highlightFirst,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                    onHideApp = onHideApp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(APPS_LIST_TAG),
                ) {
                    itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
                        AppRow(
                            app = app,
                            isActive = highlightFirst && index == 0,
                            dockLimit = dockLimit,
                            onLaunchApp = onLaunchApp,
                            onOpenAppInfo = onOpenAppInfo,
                            onToggleDock = onToggleDock,
                            onResetRank = onResetRank,
                            onHideApp = onHideApp,
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
    highlightFirst: Boolean,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
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
        itemsIndexed(apps, key = { _, app -> app.id }) { index, app ->
            IconOnlyAppButton(
                app = app,
                isActive = highlightFirst && index == 0,
                dockLimit = dockLimit,
                iconSizeDp = iconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
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
    onHideApp: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val highlightColor = selectionHighlightColor()
    val containerColor = if (isActive) highlightColor else Color.Transparent
    Box {
        Column(
            modifier = Modifier
                .background(containerColor, RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = app.name
                    selected = isActive
                }
                .padding(4.dp)
                .testTag("$APP_ICON_ONLY_BUTTON_TAG:${app.name}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(
                app = app,
                size = iconSizeDp.dp,
                testTag = APP_ICON_ONLY_ICON_TAG,
                backgroundColor = if (isActive) highlightColor else MaterialTheme.colorScheme.surfaceVariant,
            )
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
            onHideApp = onHideApp,
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
    onHideApp: (InstalledApp) -> Unit,
) {
    val highlightColor = selectionHighlightColor()
    val highlightOnColor = selectionHighlightOnColor()
    val rowColor = if (isActive) highlightColor else Color.Transparent
    val textColor = if (isActive) highlightOnColor else MaterialTheme.colorScheme.onBackground
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowColor, RoundedCornerShape(8.dp))
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
            AppIcon(
                app = app,
                size = 40.dp,
                backgroundColor = if (isActive) highlightColor else MaterialTheme.colorScheme.surfaceVariant,
            )
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
            onHideApp = onHideApp,
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
    onHideApp: (InstalledApp) -> Unit,
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
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_hide)) },
            modifier = Modifier.testTag("$HIDE_APP_ACTION_TAG:${app.name}"),
            onClick = {
                onDismiss()
                onHideApp(app)
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
    onHideApp: (InstalledApp) -> Unit,
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
            onHideApp = onHideApp,
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
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onRecentsAlwaysShownChanged: (Boolean) -> Unit = {},
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onUnhideApp: (InstalledApp) -> Unit,
    onOpenLauncherAppInfo: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val slotCountRange = dockSlotCountRange(configuration.screenWidthDp)
    val dockIconCount = state.dockIconCount.coerceIn(slotCountRange)
    val dockIconSizeDp = dockIconSizeForSlotCount(configuration.screenWidthDp, dockIconCount)
    var hiddenAppsDialogVisible by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .verticalScroll(rememberScrollState())
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
                modifier = Modifier
                    .weight(1f)
                    .testTag(SETTINGS_TITLE_TAG),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            SettingsOverflowMenu(onOpenLauncherAppInfo = onOpenLauncherAppInfo)
            Button(
                onClick = onCloseSettings,
                modifier = Modifier.testTag(SETTINGS_DONE_BUTTON_TAG),
            ) {
                Text(stringResource(R.string.settings_done_button))
            }
        }
        Button(
            onClick = onRequestDefaultLauncher,
            enabled = !state.isDefaultLauncher,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(DEFAULT_LAUNCHER_BUTTON_TAG),
        ) {
            Text(
                stringResource(
                    if (state.isDefaultLauncher) R.string.settings_already_default_launcher_button
                    else R.string.settings_default_launcher_button,
                ),
            )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_app_list_sort_alphabetical_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = state.appListSortOrder == AppListSortOrder.Alphabetical,
                    onCheckedChange = { isAlphabetical ->
                        onAppListSortOrderChanged(
                            if (isAlphabetical) AppListSortOrder.Alphabetical else AppListSortOrder.Usage,
                        )
                    },
                    modifier = Modifier.testTag(APP_LIST_SORT_ALPHABETICAL_SWITCH_TAG),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_show_recents_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = state.isRecentsAlwaysShown,
                    onCheckedChange = onRecentsAlwaysShownChanged,
                    modifier = Modifier.testTag(SHOW_RECENTS_SWITCH_TAG),
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
        Button(
            onClick = { hiddenAppsDialogVisible = true },
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SETTINGS_MANAGE_HIDDEN_APPS_BUTTON_TAG),
        ) {
            Text(stringResource(R.string.settings_manage_hidden_apps_button))
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
            onHideApp = onHideApp,
        )
    }
    if (hiddenAppsDialogVisible) {
        HiddenAppsDialog(
            hiddenApps = state.hiddenApps,
            onUnhideApp = onUnhideApp,
            onDismiss = { hiddenAppsDialogVisible = false },
        )
    }
}

@Composable
private fun SettingsOverflowMenu(onOpenLauncherAppInfo: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var aboutVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(SETTINGS_OVERFLOW_BUTTON_TAG),
        ) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.settings_overflow_button_description),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(SETTINGS_OVERFLOW_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_report_bug_action)) },
                modifier = Modifier.testTag(SETTINGS_REPORT_BUG_ACTION_TAG),
                onClick = {
                    expanded = false
                    val activity = context.findActivity() ?: return@DropdownMenuItem
                    scope.launch { BugReport.share(activity) }
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_info_action)) },
                modifier = Modifier.testTag(SETTINGS_APP_INFO_ACTION_TAG),
                onClick = {
                    expanded = false
                    onOpenLauncherAppInfo()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_about_action)) },
                modifier = Modifier.testTag(SETTINGS_ABOUT_ACTION_TAG),
                onClick = {
                    expanded = false
                    aboutVisible = true
                },
            )
        }
    }
    if (aboutVisible) {
        AboutDialog(onDismiss = { aboutVisible = false })
    }
}

@Composable
private fun HiddenAppsDialog(
    hiddenApps: List<InstalledApp>,
    onUnhideApp: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SETTINGS_HIDDEN_APPS_DIALOG_TAG),
        title = { Text(stringResource(R.string.settings_hidden_apps_dialog_title)) },
        text = {
            if (hiddenApps.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_hidden_apps_dialog_empty),
                    modifier = Modifier.testTag(SETTINGS_HIDDEN_APPS_EMPTY_TAG),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .testTag(SETTINGS_HIDDEN_APPS_LIST_TAG),
                ) {
                    itemsIndexed(hiddenApps, key = { _, app -> app.id }) { _, app ->
                        HiddenAppRow(app = app, onUnhideApp = onUnhideApp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SETTINGS_HIDDEN_APPS_DIALOG_DISMISS_TAG),
            ) {
                Text(stringResource(R.string.settings_hidden_apps_dialog_dismiss))
            }
        },
    )
}

@Composable
private fun HiddenAppRow(
    app: InstalledApp,
    onUnhideApp: (InstalledApp) -> Unit,
) {
    val unhideDescription = stringResource(R.string.settings_hidden_apps_unhide_description, app.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("$SETTINGS_HIDDEN_APPS_ROW_TAG:${app.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app = app, size = 32.dp)
        Text(
            text = app.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = { onUnhideApp(app) },
            modifier = Modifier.testTag("$SETTINGS_HIDDEN_APPS_UNHIDE_TAG:${app.name}"),
        ) {
            Icon(
                imageVector = Icons.Filled.Clear,
                contentDescription = unhideDescription,
            )
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SETTINGS_ABOUT_DIALOG_TAG),
        title = { Text(stringResource(R.string.settings_about_dialog_title)) },
        text = {
            Text(
                stringResource(
                    R.string.settings_about_version_value,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE,
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(SETTINGS_ABOUT_DIALOG_DISMISS_TAG),
            ) {
                Text(stringResource(R.string.settings_about_dialog_dismiss))
            }
        },
    )
}

@Composable
private fun SettingsPreview(
    state: LauncherUiState,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    val previewHeight = (dockIconSizeDp + SETTINGS_PREVIEW_CARD_CHROME_DP).dp
    // Total preview footprint is fixed at SETTINGS_PREVIEW_BAR_COUNT bars so the
    // user can see the size impact of enabling each bar: every additional bottom
    // card (dock, recents) eats one bar of vertical space out of the apps card.
    val totalPreviewHeight =
        previewHeight * SETTINGS_PREVIEW_BAR_COUNT +
            SETTINGS_PREVIEW_SPACING_DP.dp * (SETTINGS_PREVIEW_BAR_COUNT - 1)
    val bottomCardCount =
        (if (state.isDockEnabled) 1 else 0) + (if (state.isRecentsAlwaysShown) 1 else 0)
    val appListHeight =
        totalPreviewHeight - (previewHeight + SETTINGS_PREVIEW_SPACING_DP.dp) * bottomCardCount
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_PREVIEW_SPACING_DP.dp),
    ) {
        AppsCard(
            apps = state.filteredApps,
            dockLimit = Int.MAX_VALUE,
            isIconOnly = state.isAppListIconOnly,
            iconSizeDp = dockIconSizeDp,
            highlightFirst = state.query.isNotBlank(),
            modifier = Modifier.height(appListHeight),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
            onHideApp = onHideApp,
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
                onHideApp = onHideApp,
            )
        }
        // Mirror Home: recents lives in its own card below the dock so the
        // preview reflects the orthogonal `Show recents` setting even when the
        // dock is disabled.
        RecentsCard(
            recentApps = state.recentApps,
            isVisible = state.isRecentsAlwaysShown,
            dockIconSizeDp = dockIconSizeDp,
            modifier = Modifier.height(previewHeight),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
            onHideApp = onHideApp,
        )
    }
}

@Composable
private fun AppIcon(
    app: InstalledApp,
    size: androidx.compose.ui.unit.Dp,
    testTag: String = APP_ICON_TAG,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val bitmap = rememberAppIconBitmap(app, size)
    Box(
        modifier = Modifier
            .size(size)
            .testTag("$testTag:${app.name}"),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.medium,
            color = backgroundColor,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
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

@Composable
private fun selectionHighlightColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF274C7A) else Color(0xFFCFE2FF)

@Composable
private fun selectionHighlightOnColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFE6EEFA) else Color(0xFF0B2A5B)

private const val MIN_DOCKED_APPS = 1
private const val SETTINGS_PREVIEW_CARD_CHROME_DP = 40
private const val SETTINGS_PREVIEW_BAR_COUNT = 3
private const val SETTINGS_PREVIEW_SPACING_DP = 16

// Notification badge dot — sized to read as "presence" rather than a count or
// number badge, matching Android's standard notification dot. Sits in the
// top-right corner of the icon with a thin surface-coloured ring so it stays
// legible against busy app icons.
private const val NOTIFICATION_BADGE_SIZE_DP = 12
