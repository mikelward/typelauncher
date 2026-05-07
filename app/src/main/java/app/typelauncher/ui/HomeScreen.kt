package app.typelauncher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    bodyReady: Boolean,
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
    keyboardShowRequests: SharedFlow<Unit> = MutableSharedFlow(),
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onReorderDock: (Int, Int) -> Unit = { _, _ -> },
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit,
    onDismissNotifications: (InstalledApp) -> Unit,
    onOpenNotificationSettings: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onSetNotificationBarOpen: (Boolean) -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    onHomeBoundsChanged: (Rect?) -> Unit = {},
    onAppsCardBoundsChanged: (Rect?) -> Unit = {},
    onAppListBoundsChanged: (Rect?) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    val dockIconSizeDp = dockIconSizeForSlotCount(configuration.screenWidthDp, state.dockIconCount)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(innerPadding)
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp)
            .onGloballyPositioned { coords ->
                onHomeBoundsChanged(Rect(coords.positionInRoot(), coords.size.toSize()))
            }
            .testTag(HOME_SCREEN_TAG),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SearchCard(
            query = state.query,
            autoShowKeyboard = state.isKeyboardAutoShown && state.screen == LauncherScreen.Home,
            showPlayUpdateBadge = state.playUpdate.showBadge,
            placeholderSuffix = searchPlaceholderSuffix,
            keyboardShowRequests = keyboardShowRequests,
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
            val showNotificationBar = state.notificationPullDownBehavior.showsLauncherNotificationBar &&
                state.isNotificationBarOpen
            val showNotificationBarAbove = state.notificationPullDownBehavior == NotificationPullDownBehavior.BarAbove
            if (showNotificationBarAbove) {
                NotificationBarCard(
                    notifyingApps = state.notifyingApps,
                    isVisible = showNotificationBar,
                    hasNotificationAccess = state.hasNotificationAccess,
                    dockIconSizeDp = dockIconSizeDp,
                    onLaunchApp = onLaunchApp,
                    onDismissNotifications = onDismissNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onRequestNotificationAccess = onRequestNotificationAccess,
                    onDismiss = { onSetNotificationBarOpen(false) },
                )
            }
            AppsCard(
                apps = state.filteredApps,
                isLoading = state.isLoadingApps,
                dockLimit = Int.MAX_VALUE,
                isIconOnly = state.isAppListIconOnly,
                iconSizeDp = dockIconSizeDp,
                highlightFirst = state.query.isNotBlank(),
                reverseLayout = state.appListSortOrder.isReversed,
                scrollResetKey = state.query,
                modifier = Modifier.weight(1f),
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
                onAppsCardBoundsChanged = onAppsCardBoundsChanged,
                onAppListBoundsChanged = onAppListBoundsChanged,
            )
            if (!showNotificationBarAbove) {
                NotificationBarCard(
                    notifyingApps = state.notifyingApps,
                    isVisible = showNotificationBar,
                    hasNotificationAccess = state.hasNotificationAccess,
                    dockIconSizeDp = dockIconSizeDp,
                    onLaunchApp = onLaunchApp,
                    onDismissNotifications = onDismissNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onRequestNotificationAccess = onRequestNotificationAccess,
                    onDismiss = { onSetNotificationBarOpen(false) },
                )
            }
            if (state.isDockEnabled) {
                DockCard(
                    dockedApps = state.dockedApps,
                    dockIconSizeDp = dockIconSizeDp,
                    dockIconCount = state.dockIconCount,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onReorderDock = onReorderDock,
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
                onDismissRecent = onDismissRecent,
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
    autoShowKeyboard: Boolean,
    showPlayUpdateBadge: Boolean,
    placeholderSuffix: String,
    keyboardShowRequests: SharedFlow<Unit>,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onOpenSettings: () -> Unit,
    onLaunchActiveApp: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    // The auto-focus / show pair is the launcher's "type immediately on Home"
    // behavior. Gating both on the user setting is what actually keeps the IME
    // down. MainActivity also applies stateAlwaysHidden when the setting is off
    // so a retained TextField focus cannot re-show the IME on launcher resume.
    LaunchedEffect(autoShowKeyboard) {
        if (autoShowKeyboard) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // Pull-up second-stage trigger: when the carousel decides the user wants
    // the IME back (recents already open, gesture continues), it emits on this
    // flow. Focus has to be re-grabbed too because the back gesture that
    // dismissed the keyboard typically also dropped focus from the TextField.
    LaunchedEffect(keyboardShowRequests) {
        keyboardShowRequests.collect {
            focusRequester.requestFocus()
            keyboard?.show()
        }
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
                        Box {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.settings_open_button_description),
                            )
                            if (showPlayUpdateBadge) {
                                val badgeDescription = stringResource(R.string.play_update_badge_description)
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 2.dp, y = (-2).dp)
                                        .size(PLAY_UPDATE_BADGE_SIZE_DP.dp)
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .semantics { contentDescription = badgeDescription }
                                        .testTag(PLAY_UPDATE_BADGE_TAG),
                                )
                            }
                        }
                    }
                }
            },
            singleLine = true,
            placeholder = {
                Text(stringResource(R.string.app_search_hint, placeholderSuffix))
            },
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
    dockIconCount: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onReorderDock: (Int, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    // Drag-to-reorder state is hoisted here so neighbouring icons can read
    // each other's slot centres and trigger swaps when the dragged icon's
    // centre crosses an adjacent slot. `slotCenters` is keyed by app id, so
    // it survives reorders that change positional indices.
    var draggedAppId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val slotCenters = remember { mutableStateMapOf<String, Float>() }
    val latestDockedApps by rememberUpdatedState(dockedApps)
    val latestOnReorderDock by rememberUpdatedState(onReorderDock)

    SectionCard(modifier.testTag(DOCK_CARD_TAG)) {
        ScrollableIconRow(
            rowModifier = Modifier.testTag(DOCK_LIST_TAG),
            startChevronTestTag = DOCK_SCROLL_START_CHEVRON_TAG,
            endChevronTestTag = DOCK_SCROLL_END_CHEVRON_TAG,
            chevronContentDescription = stringResource(R.string.dock_scroll_more_hint),
        ) {
            // `key(app.id)` keeps each DockedAppButton's Compose identity tied
            // to the app, not the slot — so a reorder mid-drag moves the same
            // composable (and its in-flight pointerInput coroutine) to the new
            // slot instead of restarting the gesture on the slot's new tenant.
            dockedApps.forEach { app ->
                key(app.id) {
                    DockedAppButton(
                        app = app,
                        dockIconSizeDp = dockIconSizeDp,
                        isDragged = draggedAppId == app.id,
                        dragOffsetX = if (draggedAppId == app.id) dragOffsetX else 0f,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onHideApp = onHideApp,
                        onReportSlotCenter = { center -> slotCenters[app.id] = center },
                        onDragStart = {
                            draggedAppId = app.id
                            dragOffsetX = 0f
                        },
                        onDrag = { dx ->
                            handleDockDrag(
                                dx = dx,
                                draggedAppId = draggedAppId,
                                currentDockedApps = latestDockedApps,
                                slotCenters = slotCenters,
                                onReorder = latestOnReorderDock,
                                currentOffset = dragOffsetX,
                                setOffset = { dragOffsetX = it },
                            )
                        },
                        onDragEnd = {
                            draggedAppId = null
                            dragOffsetX = 0f
                        },
                    )
                }
            }
            if (dockedApps.size < dockIconCount) {
                DockAddButton(dockIconSizeDp = dockIconSizeDp)
            }
        }
    }
}

/**
 * Walks the dock list and fires [onReorder] for every adjacent slot whose
 * centre the dragged icon's centre crossed in this single drag step. The
 * dragged-app's offset is rebased on each crossing so the icon stays
 * visually under the finger as the persisted order updates underneath.
 *
 * `currentDockedApps` is captured at the start of the call and intentionally
 * not re-read across iterations: each reorder only shifts the dragged app,
 * so the unaffected neighbours keep their old indices and we can keep
 * walking with the same list.
 */
private fun handleDockDrag(
    dx: Float,
    draggedAppId: String?,
    currentDockedApps: List<InstalledApp>,
    slotCenters: Map<String, Float>,
    onReorder: (Int, Int) -> Unit,
    currentOffset: Float,
    setOffset: (Float) -> Unit,
) {
    if (draggedAppId == null) return
    var workingIndex = currentDockedApps.indexOfFirst { it.id == draggedAppId }
    if (workingIndex < 0) return
    var newOffset = currentOffset + dx
    while (true) {
        val ownCenter = slotCenters[draggedAppId] ?: break
        val draggedCenter = ownCenter + newOffset
        var swapped = false
        if (newOffset > 0f && workingIndex < currentDockedApps.lastIndex) {
            val neighbour = currentDockedApps[workingIndex + 1]
            val neighbourCenter = slotCenters[neighbour.id]
            if (neighbourCenter != null && draggedCenter > neighbourCenter) {
                onReorder(workingIndex, workingIndex + 1)
                newOffset += (ownCenter - neighbourCenter)
                workingIndex += 1
                swapped = true
            }
        } else if (newOffset < 0f && workingIndex > 0) {
            val neighbour = currentDockedApps[workingIndex - 1]
            val neighbourCenter = slotCenters[neighbour.id]
            if (neighbourCenter != null && draggedCenter < neighbourCenter) {
                onReorder(workingIndex, workingIndex - 1)
                newOffset += (ownCenter - neighbourCenter)
                workingIndex -= 1
                swapped = true
            }
        }
        if (!swapped) break
    }
    setOffset(newOffset)
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
    onDismissRecent: (InstalledApp) -> Unit,
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
                onDismissRecent = onDismissRecent,
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
    onDismissNotifications: (InstalledApp) -> Unit,
    onOpenNotificationSettings: (InstalledApp) -> Unit,
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
                    onDismissNotifications = onDismissNotifications,
                    onOpenNotificationSettings = onOpenNotificationSettings,
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
    onDismissNotifications: (InstalledApp) -> Unit,
    onOpenNotificationSettings: (InstalledApp) -> Unit,
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
                onDismissNotifications = onDismissNotifications,
                onOpenNotificationSettings = onOpenNotificationSettings,
            )
        }
    }
}

@Composable
private fun NotifyingAppButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    onLaunchApp: (InstalledApp) -> Unit,
    onDismissNotifications: (InstalledApp) -> Unit,
    onOpenNotificationSettings: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val badgeDescription = stringResource(R.string.notification_bar_badge_description)
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.displayName }
                .padding(4.dp)
                .testTag("$NOTIFICATION_BAR_APP_TAG:${app.displayName}"),
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
                        .testTag("$NOTIFICATION_BAR_BADGE_TAG:${app.displayName}"),
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
                    contentDescription = app.displayName
                },
        )
        NotifyingAppActionsMenu(
            expanded = menuExpanded,
            app = app,
            onDismissMenu = { menuExpanded = false },
            onDismissNotifications = onDismissNotifications,
            onOpenNotificationSettings = onOpenNotificationSettings,
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
    onDismissRecent: (InstalledApp) -> Unit,
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
                onDismissRecent = onDismissRecent,
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
    onDismissRecent: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.displayName }
                .padding(4.dp)
                .testTag("$DOCK_RECENTS_APP_TAG:${app.displayName}"),
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
                    contentDescription = app.displayName
                },
        )
        RecentAppActionsMenu(
            expanded = menuExpanded,
            app = app,
            onDismissMenu = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onDismissRecent = onDismissRecent,
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
    var hasMeasuredContent by remember { mutableStateOf(false) }
    var overflowSlopPx by remember { mutableStateOf(0) }
    if (pinToEndKey != null) {
        LaunchedEffect(pinToEndKey, scrollState.maxValue, hasMeasuredContent, overflowSlopPx) {
            if (hasMeasuredContent) {
                val target = if (scrollState.maxValue > overflowSlopPx) scrollState.maxValue else 0
                scrollState.scrollTo(target)
            }
        }
    }
    val showEndChevron by remember(scrollState) {
        derivedStateOf {
            hasMeasuredContent &&
                scrollState.maxValue > overflowSlopPx &&
                scrollState.value < scrollState.maxValue - overflowSlopPx
        }
    }
    val showStartChevron by remember(scrollState) {
        derivedStateOf {
            hasMeasuredContent &&
                scrollState.maxValue > overflowSlopPx &&
                scrollState.value > overflowSlopPx
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Stretch the row to at least the viewport width so the centered
        // arrangement has space to distribute when the icons fit on one
        // screen, but use the raw px from `BoxWithConstraints.constraints`
        // (not `maxWidth.dp`) — the Dp round-trip can land 1 px above the
        // viewport on non-integer densities and trip a spurious overflow
        // chevron when the row content actually fits.
        val viewportPx = constraints.maxWidth
        val scope = rememberCoroutineScope()
        val pageBack: () -> Unit = {
            scope.launch { scrollState.scrollOneHorizontalPage(backward = true, viewportPx = viewportPx) }
        }
        val pageForward: () -> Unit = {
            scope.launch { scrollState.scrollOneHorizontalPage(backward = false, viewportPx = viewportPx) }
        }
        Row(
            modifier = rowModifier
                .pointerInput(showStartChevron, showEndChevron, viewportPx) {
                    detectTapGestures { offset ->
                        when {
                            showStartChevron && offset.x <= HorizontalScrollChevronTapTargetSize.toPx() -> pageBack()
                            showEndChevron && offset.x >= size.width - HorizontalScrollChevronTapTargetSize.toPx() -> pageForward()
                        }
                    }
                }
                .horizontalScroll(scrollState)
                .layout { measurable, childConstraints ->
                    val placeable = measurable.measure(
                        childConstraints.copy(minWidth = viewportPx),
                    )
                    // Allow a 1.dp slop before declaring overflow: each child
                    // does its own dp→px rounding for padding/spacing, and on
                    // non-integer densities those errors can compound into a
                    // 1–2 px row width above the viewport even when the icons
                    // visibly fit. Without this, `pinToEndKey` rows (recents,
                    // notifications) auto-scroll to that 1 px maxValue, lift
                    // `scrollState.value` above 0, and show the start chevron
                    // for content the user has no way to actually scroll.
                    overflowSlopPx = 1.dp.roundToPx()
                    hasMeasuredContent = true
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
                xEdgeOffset = -HorizontalScrollChevronEdgeOffset,
                testTag = startChevronTestTag,
                tapTargetWidth = HorizontalScrollChevronEdgeOffset,
                tapTargetHeight = HorizontalScrollChevronTapTargetSize,
                iconRequiredSize = HorizontalScrollChevronTapTargetSize,
                onClick = pageBack,
            )
        }
        if (showEndChevron) {
            OverflowScrollChevron(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = chevronContentDescription,
                alignment = Alignment.CenterEnd,
                xEdgeOffset = HorizontalScrollChevronEdgeOffset,
                testTag = endChevronTestTag,
                tapTargetWidth = HorizontalScrollChevronEdgeOffset,
                tapTargetHeight = HorizontalScrollChevronTapTargetSize,
                iconRequiredSize = HorizontalScrollChevronTapTargetSize,
                onClick = pageForward,
            )
        }
    }
}

private suspend fun ScrollState.scrollOneHorizontalPage(backward: Boolean, viewportPx: Int) {
    val delta = if (backward) -viewportPx else viewportPx
    scrollTo((value + delta).coerceIn(0, maxValue))
}

/**
 * Wraps a vertically scrollable apps list (the `LazyColumn` text rows or the
 * `LazyVerticalGrid` icon-only grid) and overlays top/bottom chevrons on
 * whichever edge has more content scrolled past, mirroring the dock and
 * notification-bar overflow treatment so a long list is discoverable as
 * scrollable instead of relying on the user guessing.
 */
@Composable
private fun AppListOverflowChevronBox(
    canScrollUp: Boolean,
    canScrollDown: Boolean,
    chevronContentDescription: String,
    onScrollPageUp: () -> Unit,
    onScrollPageDown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        content()
        if (canScrollUp) {
            OverflowScrollChevron(
                icon = Icons.Filled.KeyboardArrowUp,
                contentDescription = chevronContentDescription,
                alignment = Alignment.TopCenter,
                yEdgeOffset = -VerticalScrollChevronEdgeOffset,
                testTag = APPS_LIST_SCROLL_TOP_CHEVRON_TAG,
                tapTargetWidth = VerticalScrollChevronTapTargetSize,
                onClick = onScrollPageUp,
            )
        }
        if (canScrollDown) {
            OverflowScrollChevron(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = chevronContentDescription,
                alignment = Alignment.BottomCenter,
                yEdgeOffset = VerticalScrollChevronEdgeOffset,
                testTag = APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG,
                tapTargetWidth = VerticalScrollChevronTapTargetSize,
                onClick = onScrollPageDown,
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
    xEdgeOffset: Dp = 0.dp,
    yEdgeOffset: Dp = 0.dp,
    tapTargetWidth: Dp? = null,
    tapTargetHeight: Dp? = tapTargetWidth,
    iconRequiredSize: Dp? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick != null && tapTargetWidth != null && tapTargetHeight != null) {
        // The chevron's tap target must not overlap the sibling scrollable's
        // hit area. Compose dispatches pointer events at any given position to
        // the topmost overlapping sibling only, so a chevron Box sitting on
        // top of the Row will swallow a swipe that started on it — even when
        // the chevron's pointerInput never consumes the down. Sizing the Box
        // to just the chevron's overhang area (e.g. 18 dp wide for horizontal
        // chevrons positioned at xEdgeOffset = ±18.dp) keeps it fully outside
        // the row, while `iconRequiredSize` lets the visible chevron icon
        // overflow back over the row's edge so the affordance still looks
        // anchored on the icon strip. Taps on the visible part of the icon
        // that lands inside the row fall through to the row's own pointerInput
        // (which already pages on first/last 32.dp taps).
        Box(
            modifier = Modifier
                .align(alignment)
                .offset(x = xEdgeOffset, y = yEdgeOffset)
                .size(width = tapTargetWidth, height = tapTargetHeight)
                .pointerInput(onClick) {
                    detectTapGestures(onTap = { onClick() })
                }
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    onClick {
                        onClick()
                        true
                    }
                }
                .testTag(testTag),
            contentAlignment = alignment,
        ) {
            ChevronIcon(
                icon = icon,
                contentDescription = contentDescription,
                modifier = if (iconRequiredSize != null) {
                    Modifier.requiredSize(iconRequiredSize)
                } else {
                    Modifier
                },
            )
        }
        return
    }
    ChevronIcon(
        icon = icon,
        contentDescription = contentDescription,
        modifier = Modifier
            .align(alignment)
            .offset(x = xEdgeOffset, y = yEdgeOffset)
            .testTag(testTag),
    )
}

@Composable
private fun ChevronIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                shape = CircleShape,
            )
            .padding(2.dp),
    )
}

private val HorizontalScrollChevronEdgeOffset = 18.dp
private val HorizontalScrollChevronTapTargetSize = 32.dp
private val VerticalScrollChevronEdgeOffset = 18.dp
private val VerticalScrollChevronTapTargetSize = 32.dp

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    isLoading: Boolean = false,
    dockLimit: Int,
    isIconOnly: Boolean,
    iconSizeDp: Int,
    highlightFirst: Boolean,
    reverseLayout: Boolean = false,
    // Anything that should yank the list back to the natural top (item 0). The
    // search query is the canonical caller: `rememberLazyListState` /
    // `rememberLazyGridState` survives query changes, so without this reset a
    // user who scrolled down to find a substring match and then typed another
    // character would stay at the old offset — likely past the end of the new
    // shorter result set, showing blank space. Launching an app clears the
    // query too, so this also resets the scroll for the next time the user
    // returns to Home.
    scrollResetKey: Any? = null,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onAppsCardBoundsChanged: (Rect?) -> Unit = {},
    onAppListBoundsChanged: (Rect?) -> Unit = {},
) {
    LaunchedEffect(isLoading, apps.isEmpty()) {
        if (isLoading || apps.isEmpty()) {
            onAppsCardBoundsChanged(null)
            onAppListBoundsChanged(null)
        }
    }
    SectionCard(
        modifier
            .onGloballyPositioned { coords ->
                onAppsCardBoundsChanged(Rect(coords.positionInRoot(), coords.size.toSize()))
            }
            .testTag(APPS_CARD_TAG),
    ) {
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
            val chevronDescription = stringResource(R.string.apps_list_scroll_more_hint)
            if (isIconOnly) {
                val gridState = rememberLazyGridState()
                LaunchedEffect(scrollResetKey) { gridState.scrollToItem(0) }
                // In reverseLayout, the visual top is at the END of the data,
                // so the chevron predicate that asks "can we scroll visually
                // up / down" swaps to canScrollForward / canScrollBackward
                // respectively.
                val canScrollUp = if (reverseLayout) gridState.canScrollForward else gridState.canScrollBackward
                val canScrollDown = if (reverseLayout) gridState.canScrollBackward else gridState.canScrollForward
                val scope = rememberCoroutineScope()
                AppListOverflowChevronBox(
                    canScrollUp = canScrollUp,
                    canScrollDown = canScrollDown,
                    chevronContentDescription = chevronDescription,
                    onScrollPageUp = {
                        scope.launch { gridState.scrollOneVisualPage(up = true, reverseLayout = reverseLayout) }
                    },
                    onScrollPageDown = {
                        scope.launch { gridState.scrollOneVisualPage(up = false, reverseLayout = reverseLayout) }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    IconOnlyAppGrid(
                        apps = apps,
                        dockLimit = dockLimit,
                        iconSizeDp = iconSizeDp,
                        highlightFirst = highlightFirst,
                        reverseLayout = reverseLayout,
                        state = gridState,
                        onBoundsChanged = onAppListBoundsChanged,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onHideApp = onHideApp,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(scrollResetKey) { listState.scrollToItem(0) }
                val canScrollUp = if (reverseLayout) listState.canScrollForward else listState.canScrollBackward
                val canScrollDown = if (reverseLayout) listState.canScrollBackward else listState.canScrollForward
                val scope = rememberCoroutineScope()
                AppListOverflowChevronBox(
                    canScrollUp = canScrollUp,
                    canScrollDown = canScrollDown,
                    chevronContentDescription = chevronDescription,
                    onScrollPageUp = {
                        scope.launch { listState.scrollOneVisualPage(up = true, reverseLayout = reverseLayout) }
                    },
                    onScrollPageDown = {
                        scope.launch { listState.scrollOneVisualPage(up = false, reverseLayout = reverseLayout) }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        reverseLayout = reverseLayout,
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coords ->
                                onAppListBoundsChanged(
                                    Rect(coords.positionInRoot(), coords.size.toSize()),
                                )
                            }
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
}

private suspend fun LazyGridState.scrollOneVisualPage(up: Boolean, reverseLayout: Boolean) {
    val direction = visualPageScrollDirection(up = up, reverseLayout = reverseLayout)
    animateScrollBy(direction * layoutInfo.viewportSize.height.toFloat())
}

private suspend fun LazyListState.scrollOneVisualPage(
    up: Boolean,
    reverseLayout: Boolean,
) {
    val direction = visualPageScrollDirection(up = up, reverseLayout = reverseLayout)
    animateScrollBy(direction * layoutInfo.viewportSize.height.toFloat())
}

private fun visualPageScrollDirection(up: Boolean, reverseLayout: Boolean): Float =
    when {
        up && reverseLayout -> 1f
        up -> -1f
        reverseLayout -> -1f
        else -> 1f
    }

@Composable
private fun IconOnlyAppGrid(
    apps: List<InstalledApp>,
    dockLimit: Int,
    iconSizeDp: Int,
    highlightFirst: Boolean,
    state: LazyGridState,
    reverseLayout: Boolean = false,
    onBoundsChanged: (Rect?) -> Unit = {},
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive((iconSizeDp + 8).dp),
        state = state,
        reverseLayout = reverseLayout,
        modifier = Modifier
            .fillMaxSize()
            .heightIn(min = iconSizeDp.dp)
            .onGloballyPositioned { coords ->
                onBoundsChanged(
                    Rect(coords.positionInRoot(), coords.size.toSize()),
                )
            }
            .testTag(APPS_LIST_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(
            8.dp,
            if (reverseLayout) Alignment.Bottom else Alignment.Top,
        ),
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
                    contentDescription = app.displayName
                    selected = isActive
                }
                .padding(4.dp)
                .testTag("$APP_ICON_ONLY_BUTTON_TAG:${app.displayName}"),
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
                    contentDescription = app.displayName
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
                .testTag("$APP_ROW_TAG:${app.displayName}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(
                app = app,
                size = 40.dp,
                backgroundColor = if (isActive) highlightColor else MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                app.displayName,
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
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onOpenAppInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (app.isDocked) R.string.app_menu_undock else R.string.app_menu_dock)) },
            modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onToggleDock(app, dockLimit)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_reset_rank)) },
            modifier = Modifier.testTag("$RESET_RANK_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onResetRank(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_hide)) },
            modifier = Modifier.testTag("$HIDE_APP_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onHideApp(app)
            },
        )
    }
}

/**
 * Long-press menu for the recents row. The recents bar is launch history, not
 * a curated list, so Reset rank (a usage-count concept) and Hide (which
 * removes from every surface) don't belong here — Reset rank is orthogonal
 * since recents isn't ranked, and Hide is incoherent on an icon you just
 * launched. Dismiss is the per-icon equivalent of swiping a notification away
 * — drops just this entry off the bar without touching launch counts.
 */
@Composable
private fun RecentAppActionsMenu(
    expanded: Boolean,
    app: InstalledApp,
    onDismissMenu: () -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissMenu,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onOpenAppInfo(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (app.isDocked) R.string.app_menu_undock else R.string.app_menu_dock)) },
            modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onToggleDock(app, Int.MAX_VALUE)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_dismiss)) },
            modifier = Modifier.testTag("$DISMISS_RECENT_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onDismissRecent(app)
            },
        )
    }
}

/**
 * Long-press menu for the notification bar. The bar surfaces apps because the
 * system has flagged them as actively notifying — so the right actions are
 * notification-shaped, not app-shaped. Dismiss cancels the user-visible
 * notifications for the package (which clears the icon from the bar);
 * Settings opens Android's per-app notification settings so the user can
 * mute or block the app at the source. App info / Dock / Reset rank / Hide
 * are reachable from the main app list and would be off-context here.
 */
@Composable
private fun NotifyingAppActionsMenu(
    expanded: Boolean,
    app: InstalledApp,
    onDismissMenu: () -> Unit,
    onDismissNotifications: (InstalledApp) -> Unit,
    onOpenNotificationSettings: (InstalledApp) -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissMenu,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_dismiss)) },
            modifier = Modifier.testTag("$DISMISS_NOTIFICATIONS_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onDismissNotifications(app)
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.app_menu_settings)) },
            modifier = Modifier.testTag("$NOTIFICATION_SETTINGS_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onOpenNotificationSettings(app)
            },
        )
    }
}

// Keep app-action menus out of Android window focus so opening them does not
// clear the focused search field and collapse the IME. The menu content still
// renders as Compose semantics, but this popup should stay scoped to app
// actions where keyboard preservation is more important than modal focus.
private val AppActionsMenuPopupProperties = PopupProperties(focusable = false)

@Composable
private fun DockedAppButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    isDragged: Boolean,
    dragOffsetX: Float,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onReportSlotCenter: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val slopPx = with(LocalDensity.current) { 8.dp.toPx() }
    // Wrap the parent's drag callbacks in updated-state holders so the
    // long-running pointerInput coroutine always invokes the freshest
    // closure (recompositions reallocate the lambdas every frame).
    val latestOnReportSlotCenter by rememberUpdatedState(onReportSlotCenter)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    Box(
        modifier = Modifier
            // onGloballyPositioned sits outside the graphicsLayer so it
            // reports the icon's static slot centre, not its translated
            // visual centre — that's what the parent compares against.
            .onGloballyPositioned { coords ->
                val center = coords.positionInParent().x + coords.size.width / 2f
                latestOnReportSlotCenter(center)
            }
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                if (isDragged) {
                    translationX = dragOffsetX
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.85f
                }
            },
    ) {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.displayName }
                .padding(4.dp)
                .testTag("$DOCK_APP_TAG:${app.displayName}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app = app, size = dockIconSizeDp.dp, testTag = DOCK_APP_ICON_TAG)
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { onLaunchApp(app) },
                )
                // Long-press arms a drag. If the finger crosses the 8 dp slop
                // the parent gets onDragStart / onDrag / onDragEnd and the
                // icon "lifts" via the graphicsLayer above; releasing without
                // crossing the slop opens the AppActionsMenu instead. Once
                // the long-press fires we consume every pointer change so the
                // enclosing horizontalScroll can't snatch the gesture.
                .pointerInput(app.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPress.consume()
                        var dragging = false
                        var totalDx = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (!change.pressed) {
                                if (dragging) {
                                    latestOnDragEnd()
                                } else {
                                    menuExpanded = true
                                }
                                change.consume()
                                break
                            }
                            val dx = change.positionChange().x
                            totalDx += dx
                            if (!dragging && abs(totalDx) > slopPx) {
                                dragging = true
                                latestOnDragStart()
                                // Carry the full pre-slop displacement into
                                // the first dispatch so the icon snaps to
                                // where the finger actually is, not back to
                                // its slot centre.
                                latestOnDrag(totalDx)
                            } else if (dragging) {
                                latestOnDrag(dx)
                            }
                            change.consume()
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = app.displayName
                    // The pointerInput drag detector above only fires on
                    // touch, so accessibility services / keyboard / switch
                    // input would otherwise have no path to the long-press
                    // menu. Re-expose it as a SemanticsAction so TalkBack's
                    // "long press" gesture and equivalent non-touch entry
                    // points still surface App info / Undock / Reset rank /
                    // Hide on dock icons.
                    onLongClick(label = null) {
                        menuExpanded = true
                        true
                    }
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
private fun DockAddButton(dockIconSizeDp: Int) {
    val context = LocalContext.current
    val hint = stringResource(R.string.dock_add_button_hint)
    val description = stringResource(R.string.dock_add_button_description)
    Box(
        modifier = Modifier
            .testTag(DOCK_ADD_BUTTON_TAG)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .size(dockIconSizeDp.dp)
                .semantics {
                    contentDescription = description
                    role = Role.Button
                }
                .clickable { Toast.makeText(context, hint, Toast.LENGTH_LONG).show() },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.padding((dockIconSizeDp * 0.25f).dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    onHideRecentsFromAppListChanged: (Boolean) -> Unit = {},
    onNotificationPullDownBehaviorChanged: (NotificationPullDownBehavior) -> Unit = {},
    onKeyboardAutoShownChanged: (Boolean) -> Unit = {},
    onAgendaEnabledChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onUnhideApp: (InstalledApp) -> Unit,
    onOpenLauncherAppInfo: () -> Unit,
    onOpenPlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
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
        SettingsBuildBannerSlot(
            playUpdate = state.playUpdate,
            buildSourceInfo = rememberBuildSourceInfo(),
            onOpenPlayUpdate = onOpenPlayUpdate,
            onDismissPlayUpdate = onDismissPlayUpdate,
        )
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
                    Text(stringResource(R.string.settings_app_list_layout_title), style = MaterialTheme.typography.titleMedium)
                }
                AppListLayoutDropdown(
                    isIconOnly = state.isAppListIconOnly,
                    onIconOnlyChanged = onAppListIconOnlyChanged,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_app_list_sort_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                AppListSortOrderDropdown(
                    selected = state.appListSortOrder,
                    onSortOrderChanged = onAppListSortOrderChanged,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_pull_down_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                NotificationPullDownBehaviorDropdown(
                    selected = state.notificationPullDownBehavior,
                    onBehaviorChanged = onNotificationPullDownBehaviorChanged,
                )
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_hide_recents_from_app_list_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (state.isRecentsAlwaysShown) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Switch(
                    checked = state.isHideRecentsFromAppList,
                    onCheckedChange = onHideRecentsFromAppListChanged,
                    enabled = state.isRecentsAlwaysShown,
                    modifier = Modifier.testTag(HIDE_RECENTS_FROM_APP_LIST_SWITCH_TAG),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_keyboard_auto_show_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = state.isKeyboardAutoShown,
                    onCheckedChange = onKeyboardAutoShownChanged,
                    modifier = Modifier.testTag(KEYBOARD_AUTO_SHOW_SWITCH_TAG),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_show_agenda_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = state.isAgendaEnabled,
                    onCheckedChange = onAgendaEnabledChanged,
                    modifier = Modifier.testTag(SHOW_AGENDA_SWITCH_TAG),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_theme_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                ThemeModeDropdown(
                    selected = state.themeMode,
                    onThemeModeChanged = onThemeModeChanged,
                )
            }
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
private fun SettingsBuildBannerSlot(
    playUpdate: PlayUpdateState,
    buildSourceInfo: BuildSourceInfo?,
    onOpenPlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
) {
    val update = playUpdate as? PlayUpdateState.Available
    if (update?.shouldPrompt == true) {
        PlayUpdateBanner(
            onOpenPlayUpdate = onOpenPlayUpdate,
            onDismissPlayUpdate = onDismissPlayUpdate,
        )
    } else if (buildSourceInfo != null) {
        LocalBuildBanner(buildSourceInfo = buildSourceInfo)
    }
}

@Composable
private fun PlayUpdateBanner(
    onOpenPlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
) {
    SectionCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PLAY_UPDATE_BANNER_TAG)
            .semantics { role = Role.Button }
            .clickable(onClick = onOpenPlayUpdate),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.play_update_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.play_update_banner_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            TextButton(
                onClick = onOpenPlayUpdate,
                modifier = Modifier.testTag(PLAY_UPDATE_BANNER_UPDATE_TAG),
            ) {
                Text(stringResource(R.string.play_update_banner_update_button))
            }
            IconButton(
                onClick = onDismissPlayUpdate,
                modifier = Modifier
                    .testTag(PLAY_UPDATE_BANNER_DISMISS_TAG)
                    .zIndex(1f),
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = stringResource(R.string.play_update_banner_dismiss_description),
                )
            }
        }
    }
}

@Composable
private fun LocalBuildBanner(buildSourceInfo: BuildSourceInfo) {
    SectionCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(LOCAL_BUILD_BANNER_TAG),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildSourceInfo.displayBranch(),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = buildSourceInfo.displaySuffix(),
                maxLines = 1,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun rememberBuildSourceInfo(): BuildSourceInfo? =
    remember {
        buildSourceInfoFromConfig()
    }

@Composable
private fun AppListLayoutDropdown(
    isIconOnly: Boolean,
    onIconOnlyChanged: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = if (isIconOnly) {
        R.string.settings_app_list_layout_option_icons
    } else {
        R.string.settings_app_list_layout_option_text
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(APP_LIST_LAYOUT_DROPDOWN_TAG),
        ) {
            Text(stringResource(selectedLabelRes))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(APP_LIST_LAYOUT_DROPDOWN_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_layout_option_text)) },
                modifier = Modifier.testTag(APP_LIST_LAYOUT_OPTION_TEXT_TAG),
                onClick = {
                    expanded = false
                    onIconOnlyChanged(false)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_layout_option_icons)) },
                modifier = Modifier.testTag(APP_LIST_LAYOUT_OPTION_ICONS_TAG),
                onClick = {
                    expanded = false
                    onIconOnlyChanged(true)
                },
            )
        }
    }
}

@Composable
private fun AppListSortOrderDropdown(
    selected: AppListSortOrder,
    onSortOrderChanged: (AppListSortOrder) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = when (selected) {
        AppListSortOrder.Usage -> R.string.settings_app_list_sort_option_usage
        AppListSortOrder.UsageReversed -> R.string.settings_app_list_sort_option_usage_reversed
        AppListSortOrder.Alphabetical -> R.string.settings_app_list_sort_option_name
        AppListSortOrder.AlphabeticalReversed -> R.string.settings_app_list_sort_option_name_reversed
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(APP_LIST_SORT_DROPDOWN_TAG),
        ) {
            Text(stringResource(selectedLabelRes))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(APP_LIST_SORT_DROPDOWN_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_sort_option_usage)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_USAGE_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.Usage)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_sort_option_usage_reversed)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_USAGE_REVERSED_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.UsageReversed)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_sort_option_name)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_NAME_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.Alphabetical)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_app_list_sort_option_name_reversed)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_NAME_REVERSED_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.AlphabeticalReversed)
                },
            )
        }
    }
}

@Composable
private fun NotificationPullDownBehaviorDropdown(
    selected: NotificationPullDownBehavior,
    onBehaviorChanged: (NotificationPullDownBehavior) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = selected.labelRes()
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(PULL_DOWN_BEHAVIOR_DROPDOWN_TAG),
        ) {
            Text(stringResource(selectedLabelRes))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(PULL_DOWN_BEHAVIOR_DROPDOWN_MENU_TAG),
        ) {
            NotificationPullDownBehavior.entries.forEach { behavior ->
                DropdownMenuItem(
                    text = { Text(stringResource(behavior.labelRes())) },
                    modifier = Modifier.testTag(behavior.optionTag()),
                    onClick = {
                        expanded = false
                        onBehaviorChanged(behavior)
                    },
                )
            }
        }
    }
}

private fun NotificationPullDownBehavior.labelRes(): Int =
    when (this) {
        NotificationPullDownBehavior.None -> R.string.settings_pull_down_option_none
        NotificationPullDownBehavior.System -> R.string.settings_pull_down_option_system
        NotificationPullDownBehavior.BarBelow -> R.string.settings_pull_down_option_bar_below
        NotificationPullDownBehavior.BarAbove -> R.string.settings_pull_down_option_bar_above
    }

private fun NotificationPullDownBehavior.optionTag(): String =
    when (this) {
        NotificationPullDownBehavior.None -> PULL_DOWN_BEHAVIOR_OPTION_NONE_TAG
        NotificationPullDownBehavior.System -> PULL_DOWN_BEHAVIOR_OPTION_SYSTEM_TAG
        NotificationPullDownBehavior.BarBelow -> PULL_DOWN_BEHAVIOR_OPTION_BAR_BELOW_TAG
        NotificationPullDownBehavior.BarAbove -> PULL_DOWN_BEHAVIOR_OPTION_BAR_ABOVE_TAG
    }

@Composable
private fun ThemeModeDropdown(
    selected: ThemeMode,
    onThemeModeChanged: (ThemeMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = selected.labelRes()
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(THEME_MODE_DROPDOWN_TAG),
        ) {
            Text(stringResource(selectedLabelRes))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(THEME_MODE_DROPDOWN_MENU_TAG),
        ) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(stringResource(mode.labelRes())) },
                    modifier = Modifier.testTag(mode.optionTag()),
                    onClick = {
                        expanded = false
                        onThemeModeChanged(mode)
                    },
                )
            }
        }
    }
}

private fun ThemeMode.labelRes(): Int =
    when (this) {
        ThemeMode.System -> R.string.settings_theme_option_system
        ThemeMode.Light -> R.string.settings_theme_option_light
        ThemeMode.Dark -> R.string.settings_theme_option_dark
    }

private fun ThemeMode.optionTag(): String =
    when (this) {
        ThemeMode.System -> THEME_MODE_OPTION_SYSTEM_TAG
        ThemeMode.Light -> THEME_MODE_OPTION_LIGHT_TAG
        ThemeMode.Dark -> THEME_MODE_OPTION_DARK_TAG
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
                text = { Text(stringResource(R.string.settings_about_action)) },
                modifier = Modifier.testTag(SETTINGS_ABOUT_ACTION_TAG),
                onClick = {
                    expanded = false
                    aboutVisible = true
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
                text = { Text(stringResource(R.string.settings_report_bug_action)) },
                modifier = Modifier.testTag(SETTINGS_REPORT_BUG_ACTION_TAG),
                onClick = {
                    expanded = false
                    val activity = context.findActivity() ?: return@DropdownMenuItem
                    scope.launch { BugReport.share(activity) }
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
    val unhideDescription = stringResource(R.string.settings_hidden_apps_unhide_description, app.displayName)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("$SETTINGS_HIDDEN_APPS_ROW_TAG:${app.displayName}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(app = app, size = 32.dp)
        Text(
            text = app.displayName,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        IconButton(
            onClick = { onUnhideApp(app) },
            modifier = Modifier.testTag("$SETTINGS_HIDDEN_APPS_UNHIDE_TAG:${app.displayName}"),
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
    val context = LocalContext.current
    val privacyPolicyUrl = stringResource(R.string.settings_about_privacy_policy_url)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(SETTINGS_ABOUT_DIALOG_TAG),
        title = { Text(stringResource(R.string.settings_about_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(
                        R.string.settings_about_version_value,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_about_privacy_policy),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .testTag(SETTINGS_ABOUT_PRIVACY_POLICY_TAG)
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(intent)
                            } catch (exception: ActivityNotFoundException) {
                                LauncherDebugLog.warning(
                                    "privacy policy link: no activity for $privacyPolicyUrl",
                                    exception,
                                )
                            }
                        },
                )
            }
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
    // user can see the size impact of enabling each bar: the notification bar
    // plus every additional card (dock, recents) each eats one bar of vertical
    // space out of the apps card.
    val totalPreviewHeight =
        previewHeight * SETTINGS_PREVIEW_BAR_COUNT +
            SETTINGS_PREVIEW_SPACING_DP.dp * (SETTINGS_PREVIEW_BAR_COUNT - 1)
    val bottomCardCount =
        (if (state.isDockEnabled) 1 else 0) + (if (state.isRecentsAlwaysShown) 1 else 0)
    val showNotificationBarPreview = state.notificationPullDownBehavior.showsLauncherNotificationBar
    val showNotificationBarAbove = state.notificationPullDownBehavior == NotificationPullDownBehavior.BarAbove
    val fixedBarCount = (if (showNotificationBarPreview) 1 else 0) + bottomCardCount
    val appListHeight =
        totalPreviewHeight - (previewHeight + SETTINGS_PREVIEW_SPACING_DP.dp) * fixedBarCount
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_PREVIEW_SPACING_DP.dp),
    ) {
        if (showNotificationBarPreview && showNotificationBarAbove) {
            NotificationBarCard(
                notifyingApps = state.notifyingApps,
                isVisible = true,
                hasNotificationAccess = true,
                dockIconSizeDp = dockIconSizeDp,
                modifier = Modifier.height(previewHeight),
                onLaunchApp = onLaunchApp,
                onDismissNotifications = {},
                onOpenNotificationSettings = {},
                onRequestNotificationAccess = {},
                onDismiss = {},
            )
        }
        AppsCard(
            apps = state.filteredApps,
            dockLimit = Int.MAX_VALUE,
            isIconOnly = state.isAppListIconOnly,
            iconSizeDp = dockIconSizeDp,
            highlightFirst = state.query.isNotBlank(),
            reverseLayout = state.appListSortOrder.isReversed,
            scrollResetKey = state.query,
            modifier = Modifier.height(appListHeight),
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onResetRank = onResetRank,
            onHideApp = onHideApp,
        )
        // Forced access-granted so toggling the setting shows the inline bar at
        // its natural height rather than the taller permission CTA.
        if (showNotificationBarPreview && !showNotificationBarAbove) {
            NotificationBarCard(
                notifyingApps = state.notifyingApps,
                isVisible = true,
                hasNotificationAccess = true,
                dockIconSizeDp = dockIconSizeDp,
                modifier = Modifier.height(previewHeight),
                onLaunchApp = onLaunchApp,
                onDismissNotifications = {},
                onOpenNotificationSettings = {},
                onRequestNotificationAccess = {},
                onDismiss = {},
            )
        }
        if (state.isDockEnabled) {
            DockCard(
                dockedApps = state.dockedApps,
                dockIconSizeDp = dockIconSizeDp,
                dockIconCount = state.dockIconCount,
                modifier = Modifier.height(previewHeight),
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onReorderDock = { _, _ -> },
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
            onDismissRecent = {},
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
            .clip(MaterialTheme.shapes.medium)
            .testTag("$testTag:${app.displayName}"),
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
        app.disambiguator?.takeIf { it.isNotEmpty() }?.let { label ->
            disambiguatorBadge(label)?.let { badge ->
                val flagSp = (APP_ICON_CORNER_BADGE_SIZE_DP - 2).sp
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .size(APP_ICON_CORNER_BADGE_SIZE_DP.dp)
                        .semantics { contentDescription = "${app.displayName} ${badge.contentDescription}" }
                        .testTag("$APP_ICON_DISAMBIGUATOR_TAG:${app.displayName}"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = badge.glyph,
                        fontSize = flagSp,
                        lineHeight = flagSp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

private data class DisambiguatorBadge(
    val glyph: String,
    val contentDescription: String,
)

private fun disambiguatorBadge(label: String): DisambiguatorBadge? {
    val normalized = label.trim().uppercase()
    return when (normalized) {
        "INTL" -> DisambiguatorBadge(INTL_GLOBE, "globe")
        "UK" -> DisambiguatorBadge(countryFlag("GB"), "flag")
        else -> normalized.takeIf { code ->
            code.length == 2 && code.all { it in 'A'..'Z' }
        }?.let { code -> DisambiguatorBadge(countryFlag(code), "flag") }
    }
}

private fun countryFlag(countryCode: String): String =
    countryCode.map { codePoint ->
        Character.toChars(REGIONAL_INDICATOR_BASE + (codePoint - 'A')).concatToString()
    }.joinToString(separator = "")

@Composable
private fun selectionHighlightColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFF274C7A) else Color(0xFFCFE2FF)

@Composable
private fun selectionHighlightOnColor(): Color =
    if (isSystemInDarkTheme()) Color(0xFFE6EEFA) else Color(0xFF0B2A5B)

private const val MIN_DOCKED_APPS = 1
private const val SETTINGS_PREVIEW_CARD_CHROME_DP = 40
private const val SETTINGS_PREVIEW_BAR_COUNT = 4
private const val SETTINGS_PREVIEW_SPACING_DP = 16

// Notification badge dot — sized to read as "presence" rather than a count or
// number badge, matching Android's standard notification dot. Sits in the
// top-right corner of the icon with a thin surface-coloured ring so it stays
// legible against busy app icons.
private const val NOTIFICATION_BADGE_SIZE_DP = 12

// Play update badge dot — same "presence" treatment as the notification dot,
// scaled down for the smaller search-field gear icon.
private const val PLAY_UPDATE_BADGE_SIZE_DP = 8
private const val INTL_GLOBE = "\uD83C\uDF10"
private const val REGIONAL_INDICATOR_BASE = 0x1F1E6
