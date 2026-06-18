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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun HomeScreen(
    state: LauncherUiState,
    innerPadding: PaddingValues,
    bodyReady: Boolean,
    landscapeTier: HomeLandscapeTier = HomeLandscapeTier.Full,
    searchRevealed: Boolean = false,
    primaryBottomPadding: Dp = 0.dp,
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
    keyboardShowRequests: SharedFlow<Unit> = MutableSharedFlow(),
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onToggleWorkDock: (InstalledApp, Int) -> Unit = onToggleDock,
    onReorderDock: (String, Int, Int) -> Unit = { _, _, _ -> },
    onReorderWorkDock: (String, Int, Int) -> Unit = { _, _, _ -> },
    onMergeDock: (String, String) -> Unit = { _, _ -> },
    onMergeWorkDock: (String, String) -> Unit = { _, _ -> },
    onRemoveFromDockFolder: (String, String) -> Unit = { _, _ -> },
    onRemoveFromWorkDockFolder: (String, String) -> Unit = { _, _ -> },
    onUndockFromDockFolder: (String, String) -> Unit = { _, _ -> },
    onUndockFromWorkDockFolder: (String, String) -> Unit = { _, _ -> },
    onExplodeDockFolder: (String) -> Unit = {},
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onAppListBoundsChanged: (Rect?) -> Unit = {},
    onBarScrollRegionChanged: (BarScrollRegion?) -> Unit = {},
    onDockDragChanged: (Boolean) -> Unit = {},
) {
    val configuration = LocalConfiguration.current
    // Size the dock from the short screen edge (the portrait width), so rotating
    // to landscape keeps the icons at their size instead of ballooning to fill
    // the wider row; the dock row is centered at that size (see `DockCard`),
    // leaving even margins in landscape.
    //
    // `dockIconSizing` takes the persisted target icon size (dp) and returns the
    // rendered per-row count (how many fit) plus the grown-to-fill size. Because
    // the icon is a fixed dp, it honors the system "Display size" setting for
    // free (a dp grows in pixels as density grows) and is immune to screen-
    // resolution changes — the dp-width simply shrinks as Display size grows, so
    // fewer, bigger icons render.
    //
    // TODO: the short edge still moves under width changes that grow both
    // dimensions; revisit for foldable unfold, free-form / multi-window
    // resize, narrow split-screen panes, and connected-display setups.
    val dockReferenceWidthDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val dockSizing = dockIconSizing(dockReferenceWidthDp, state.dockIconSizeDp)
    val dockIconCount = dockSizing.slotCount
    val dockIconSizeDp = dockSizing.iconSizeDp
    // Once the window is wider than portrait (landscape), the fixed-size dock
    // no longer fills the row. Narrow the dock card to the width its icons
    // occupy and center it (see the dock slot below) so the gray card sits as
    // an island with the screen background showing in the margins, instead of
    // a bar stretched edge-to-edge. Portrait keeps the full-width card.
    val isWiderThanPortrait = configuration.screenWidthDp > configuration.screenHeightDp
    // Card width = the icon row's footprint + the card's own padding + a small
    // slack. The slack matters: `Modifier.weight(1f)` only avoids the
    // pixel-rounding wrap (the v403 regression) when the row has a little room
    // beyond the icons' rounded-up pixel widths. The full-width portrait card
    // gets this for free — the slot-count math reserves DOCK_HORIZONTAL_PADDING_DP
    // (64) of chrome while the real chrome is only ~48 — so mirror that ~16dp
    // here, otherwise the last icon wraps to a second row at exact-fit widths.
    val dockRowSlackDp = DOCK_ITEM_SPACING_DP * 2
    val dockCardWidthDp = (
        dockRowContentWidthDp(dockIconCount, dockIconSizeDp) +
            dockRowSlackDp + SECTION_CARD_PADDING_DP * 2
        ).dp
    // Custom Layout (not Column) so the dock's max-height constraint is
    // derived from the actual measured search-card height in the same
    // measurement pass. A `Column { weight(1f) }` plus state-tracked search
    // height would either jitter for a frame or rely on Compose's
    // state-batching to update both `bodyReady` and the height in time. The
    // measurement order here — search → dock-with-cap → apps-with-remainder —
    // makes the apps-list minimum a hard constraint that the dock can never
    // squeeze, regardless of how many apps the user has docked.
    val showWorkDock = state.isWorkDockEnabled && state.isWorkProfileActive
    // The dock slot is reserved whenever *either* dock has content to render,
    // but only while the search field is empty. Typing a query hides both
    // docks so the freed space goes to the filtered results the user is
    // actually scanning — the docked apps surface in that list instead (the
    // ViewModel stops deduping them out of `filteredApps` once the query is
    // non-blank, so they stay reachable while the dock row is gone).
    // A user who turns off "Show dock" but keeps "Show work dock" on (with
    // an active work profile) still gets a dock surface — the work card
    // simply renders on its own without the personal card above it.
    //
    // In the cramped-landscape Compact state the dock(s) are dropped entirely:
    // the viewport can't fit the full experience, so rather than clip the dock
    // off the bottom of the screen we give the whole area to the app list. The
    // dock only renders in Full (which includes all of portrait). Revealing the
    // search box in Compact does not bring the dock back.
    val isDockSlotPresent =
        bodyReady && state.query.isBlank() &&
            landscapeTier == HomeLandscapeTier.Full &&
            (state.isDockEnabled || showWorkDock)
    val isHome = state.destination is LauncherDestination.Home
    // In the cramped-landscape Compact state the search box doesn't fit alongside
    // the keyboard and an app row, so it's hidden until the user reveals it with a
    // pull-up; Full (and all of portrait) keeps it visible. It also stays visible
    // whenever a query is active — hiding the box would otherwise leave the list
    // filtered with no way to see or clear the query (e.g. after a rotation /
    // resume resets the reveal while the query is still retained).
    val showSearchCard = landscapeTier != HomeLandscapeTier.Compact ||
        searchRevealed ||
        state.query.isNotBlank()
    // Auto-show the keyboard only when it fits (Full), or when the user explicitly
    // revealed the box in the Compact state — a pull-up is an explicit request, so
    // it shows the keyboard even with auto-show off.
    val autoShowKeyboard = isHome && (
        (state.isKeyboardAutoShown && landscapeTier == HomeLandscapeTier.Full) ||
            (searchRevealed && landscapeTier == HomeLandscapeTier.Compact)
        )
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(bottom = primaryBottomPadding)
            .padding(innerPadding)
            .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
            .testTag(HOME_SCREEN_TAG),
        content = {
            // Index 0: search card, or a zero-size spacer when the cramped
            // landscape Compact state hides it. The slot is always emitted so the
            // layout's measurable indices below stay stable.
            if (showSearchCard) {
                SearchCard(
                    query = state.query,
                    autoShowKeyboard = autoShowKeyboard,
                    showPlayUpdateBadge = state.playUpdate.showBadge,
                    placeholderSuffix = searchPlaceholderSuffix,
                    suggestion = searchInlineSuggestion(
                        query = state.query,
                        topMatch = state.filteredApps.firstOrNull(),
                    ),
                    keyboardShowRequests = keyboardShowRequests,
                    onQueryChanged = onQueryChanged,
                    onClearQuery = onClearQuery,
                    onOpenSettings = onOpenSettings,
                    onLaunchActiveApp = onLaunchActiveApp,
                )
            } else {
                Spacer(modifier = Modifier.size(0.dp))
            }
            // Index 1: apps card OR a placeholder spacer that fills the
            // remaining space during the cold-start holdback so the search
            // card stays pinned to the top.
            // `bodyReady` flips one frame after TypeLauncherApp first
            // composes and stays true for the lifetime of the activity
            // composition; the holdback is a cold-start optimisation, not
            // a per-mount one. See the comment on `homeBodyReady` in
            // TypeLauncherApp for the why.
            if (bodyReady) {
                AppsCard(
                    apps = state.filteredApps,
                    isLoading = state.isLoadingApps,
                    overflowChevronsReady = state.isFreshAppLoadComplete,
                    dockLimit = Int.MAX_VALUE,
                    // The cramped-landscape Compact tier always renders the app
                    // list as an icon grid sorted by usage with the most-used app
                    // at the visual bottom, overriding the persisted "App list"
                    // and "Sort apps by" choices — the list is the only launch
                    // surface there, so it favors density and thumb-reach. Both
                    // read the live `landscapeTier` param (which can lead the
                    // `state.homeLandscapeTier` snapshot by a frame on rotation).
                    layout = effectiveAppListLayout(state.appListLayout, landscapeTier),
                    iconSizeDp = dockIconSizeDp,
                    highlightFirst = state.query.isNotBlank(),
                    reverseLayout = effectiveAppListSortOrder(state.appListSortOrder, landscapeTier).isReversed,
                    scrollResetKey = state.query,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onToggleDock = onToggleDock,
                    onResetRank = onResetRank,
                    onRenameApp = onRenameApp,
                    onSetAppIconOverride = onSetAppIconOverride,
                    onClearAppIconOverride = onClearAppIconOverride,
                    onSetAppBadge = onSetAppBadge,
                    onHideApp = onHideApp,
                    onAppListBoundsChanged = onAppListBoundsChanged,
                )
            } else {
                Spacer(modifier = Modifier.fillMaxSize())
            }
            // Index 2: dock card OR a zero-size spacer when neither dock is
            // visible. The wrapping `Column` lays both dock cards out with
            // the shared `HOME_CARD_SPACING_DP` gap between them, matching
            // the gap the outer Layout uses between every other pair of
            // home cards.
            //
            // The work dock is capped at a content-driven row count via
            // `Modifier.heightIn`: one row when `workApps <= dockIconCount`,
            // two rows once the user has docked more than `dockIconCount`
            // work apps, and never taller than `MAX_WORK_DOCK_ROWS`
            // regardless of how many apps land in the work dock — extra
            // work apps scroll inside the card. The row count is derived
            // from the work dock's own content only (not the personal dock,
            // the apps list, or the viewport), so the work card never
            // resizes in response to anything but the user adding or
            // removing a work app.
            //
            // The personal dock carries `Modifier.weight(1f, fill = false)`,
            // so Compose measures the work dock first (against its capped
            // height) and gives the personal dock whatever the slot has
            // left, falling back to the personal dock's natural height when
            // there is slack. A heavily-docked personal can therefore never
            // starve the work card to zero height, and a small personal
            // dock does not stretch to fill the slot and crowd the apps
            // list above. The personal dock's own `verticalScroll` handles
            // the rare case where its natural content exceeds the remaining
            // budget.
            if (isDockSlotPresent) {
                // In a wider-than-portrait window, narrow the dock card(s) to
                // the width their icons occupy and center them, so the gray
                // card sits as an island with the screen background showing in
                // the margins instead of a bar stretched edge-to-edge. The
                // SectionCard always fills its parent's width, so the narrowing
                // is applied to this wrapping Column (the card can't size
                // itself); the surrounding Box centers it. Portrait fills the
                // width exactly as before.
                val dockColumnModifier = if (isWiderThanPortrait) {
                    Modifier.width(dockCardWidthDp)
                } else {
                    Modifier.fillMaxWidth()
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = dockColumnModifier,
                        verticalArrangement = Arrangement.spacedBy(HOME_CARD_SPACING_DP.dp),
                    ) {
                        if (state.isDockEnabled) {
                            DockCard(
                                dockedApps = state.dockedApps,
                                dockPositions = state.dockPositions,
                                dockFolders = state.dockFolders,
                                foldersEnabled = state.isDockFoldersEnabled,
                                dockIconSizeDp = dockIconSizeDp,
                                dockIconCount = dockIconCount,
                                dockLayout = state.dockLayout,
                                modifier = Modifier.weight(1f, fill = false),
                                onLaunchApp = onLaunchApp,
                                onOpenAppInfo = onOpenAppInfo,
                                onToggleDock = onToggleDock,
                                onReorderDock = onReorderDock,
                                onMergeDock = onMergeDock,
                                onRemoveFromFolder = onRemoveFromDockFolder,
                                onUndockFromFolder = onUndockFromDockFolder,
                                onExplodeFolder = onExplodeDockFolder,
                                onResetRank = onResetRank,
                                onRenameApp = onRenameApp,
                                onSetAppIconOverride = onSetAppIconOverride,
                                onClearAppIconOverride = onClearAppIconOverride,
                                onSetAppBadge = onSetAppBadge,
                                onHideApp = onHideApp,
                                onDragStateChanged = onDockDragChanged,
                                showAddButtonHint = state.shouldShowDockAddHint,
                            )
                        }
                        if (showWorkDock) {
                            // Match `DockCard`'s own row-count calculation (which
                            // is `maxOccupiedRow + 1` over the resolved-positions
                            // map, not just `ceil(size / dockIconCount)`) so a
                            // sparse persisted layout — e.g. four apps with one
                            // pinned at row 1 — gets the two-row cap it actually
                            // needs. Then clamp at `MAX_WORK_DOCK_ROWS`, and at
                            // 1 on very short viewports where a two-row work
                            // dock would crowd the personal dock out of the
                            // slot. Every supported Android phone in normal
                            // portrait is taller than the threshold; foldable
                            // narrow modes and old compact phones fall back to
                            // the previous single-row behaviour.
                            val maxWorkRows = if (
                                configuration.screenHeightDp >= SMALL_SCREEN_TWO_ROW_WORK_DOCK_THRESHOLD_DP
                            ) {
                                MAX_WORK_DOCK_ROWS
                            } else {
                                1
                            }
                            val workRows = dockRowCount(
                                state.workDockedApps.map { app -> app.id } +
                                    state.workDockFolders.map { folder -> folder.id },
                                state.workDockPositions,
                                dockIconCount,
                            ).coerceAtMost(maxWorkRows)
                            val workRowHeightDp = dockSlotHeightDp(
                                dockIconSizeDp,
                                state.dockLayout,
                                LocalDensity.current.fontScale,
                            )
                            val workMaxHeightDp = workRows * workRowHeightDp +
                                (workRows - 1) * DOCK_ITEM_SPACING_DP +
                                SECTION_CARD_PADDING_DP * 2
                            DockCard(
                                dockedApps = state.workDockedApps,
                                dockPositions = state.workDockPositions,
                                dockFolders = state.workDockFolders,
                                foldersEnabled = state.isDockFoldersEnabled,
                                dockIconSizeDp = dockIconSizeDp,
                                dockIconCount = dockIconCount,
                                dockLayout = state.dockLayout,
                                modifier = Modifier.heightIn(max = workMaxHeightDp.dp),
                                onLaunchApp = onLaunchApp,
                                onOpenAppInfo = onOpenAppInfo,
                                onToggleDock = onToggleWorkDock,
                                onReorderDock = onReorderWorkDock,
                                onMergeDock = onMergeWorkDock,
                                onRemoveFromFolder = onRemoveFromWorkDockFolder,
                                onUndockFromFolder = onUndockFromWorkDockFolder,
                                onExplodeFolder = onExplodeDockFolder,
                                onResetRank = onResetRank,
                                onRenameApp = onRenameApp,
                                onSetAppIconOverride = onSetAppIconOverride,
                                onClearAppIconOverride = onClearAppIconOverride,
                                onSetAppBadge = onSetAppBadge,
                                onHideApp = onHideApp,
                                onDragStateChanged = onDockDragChanged,
                                tags = DockTestTags.Work,
                                showAddButtonHint = state.shouldShowWorkDockAddHint,
                            )
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.size(0.dp))
            }
            // Index 3: the bottom bar (recents). Always emitted so the
            // measurable count is stable; the card collapses to zero height
            // when closed, so a closed bar lays out identically to having no
            // bar at all. When the bar opens it takes the bottom-most slot and
            // the search / apps / dock above it all shift up to make room —
            // "everything moves up".
            HomeBottomBar(
                state = state,
                dockIconSizeDp = dockIconSizeDp,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onDismissRecent = onDismissRecent,
                onBarScrollRegionChanged = onBarScrollRegionChanged,
            )
        },
    ) { measurables, constraints ->
        val spacingPx = HOME_CARD_SPACING_DP.dp.roundToPx()
        // Reserve at least APP_LIST_MIN_VISIBLE_ROWS rows for the apps list.
        // Each app-list row is ≈ dockIconSizeDp + 2 * DOCK_ITEM_SPACING_DP
        // (icon-only mode); text rows are 56dp regardless of icon size, and
        // the floor here is the larger of the two so neither layout mode is
        // squeezed.
        val appRowHeightPx = (dockIconSizeDp + DOCK_ITEM_SPACING_DP * 2).dp.roundToPx()
        val appListMinPx = APP_LIST_MIN_VISIBLE_ROWS * appRowHeightPx

        val search = measurables[0].measure(
            constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight),
        )
        // No card-gap below a hidden (zero-height) search box, so the app grid
        // sits flush at the top in the cramped-landscape Compact state.
        val searchSpacingPx = if (search.height > 0) spacingPx else 0
        val belowSearch = (constraints.maxHeight - search.height - searchSpacingPx).coerceAtLeast(0)

        // Bottom bar first: it owns the bottom-most slot, so the dock and apps
        // list lay out against whatever it leaves. Capped — like the dock — so
        // it can never squeeze the apps list below its minimum visible rows.
        // Closed bars measure to zero, collapsing this back to the no-bar layout.
        val barMaxPx = (belowSearch - appListMinPx - spacingPx).coerceAtLeast(0)
        val bar = measurables[3].measure(
            constraints.copy(minHeight = 0, maxHeight = barMaxPx),
        )
        val barSpacingPx = if (bar.height > 0) spacingPx else 0
        val belowBar = (belowSearch - bar.height - barSpacingPx).coerceAtLeast(0)

        val dockMaxPx = if (isDockSlotPresent) {
            (belowBar - appListMinPx - spacingPx).coerceAtLeast(0)
        } else {
            0
        }
        val dock = measurables[2].measure(
            constraints.copy(minHeight = 0, maxHeight = dockMaxPx),
        )
        val dockSpacingPx = if (dock.height > 0) spacingPx else 0
        val appHeight = (belowBar - dock.height - dockSpacingPx).coerceAtLeast(0)
        val apps = measurables[1].measure(
            constraints.copy(minHeight = appHeight, maxHeight = appHeight),
        )

        layout(constraints.maxWidth, constraints.maxHeight) {
            search.place(0, 0)
            apps.place(0, search.height + searchSpacingPx)
            var y = search.height + searchSpacingPx + apps.height
            if (dock.height > 0) {
                dock.place(0, y + spacingPx)
                y += spacingPx + dock.height
            }
            if (bar.height > 0) {
                bar.place(0, y + spacingPx)
            }
        }
    }
}

/**
 * The home screen's bottom bar: the recents bar (revealed by a pull-up). The
 * card animates itself open/closed via its own `AnimatedVisibility`; when
 * closed the bar collapses to zero height and the home layout above it fills
 * the space.
 */
@Composable
internal fun HomeBottomBar(
    state: LauncherUiState,
    dockIconSizeDp: Int,
    modifier: Modifier = Modifier,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit,
    onBarScrollRegionChanged: (BarScrollRegion?) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(HOME_BOTTOM_BAR_TAG),
    ) {
        RecentsCard(
            recentApps = state.recentApps,
            isVisible = state.isRecentsOpen,
            dockIconSizeDp = dockIconSizeDp,
            onLaunchApp = onLaunchApp,
            onOpenAppInfo = onOpenAppInfo,
            onToggleDock = onToggleDock,
            onDismissRecent = onDismissRecent,
            onBarScrollRegionChanged = onBarScrollRegionChanged,
        )
    }
}

@Composable
private fun SearchCard(
    query: String,
    autoShowKeyboard: Boolean,
    showPlayUpdateBadge: Boolean,
    placeholderSuffix: String,
    suggestion: InlineSearchSuggestion?,
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
        Box {
            LauncherFilterField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = stringResource(R.string.app_search_hint, placeholderSuffix),
                modifier = Modifier
                    .focusRequester(focusRequester)
                    // Swallow held-Enter key repeats before they reach the text
                    // field core: the core maps a hardware Enter ACTION_DOWN to
                    // the IME Search action (-> onSearch -> onLaunchActiveApp)
                    // and does so for *every* repeat, so a held key fired one
                    // launch per repeat — and once the first launch cleared the
                    // query, the next repeat shoved the user into settings. The
                    // preview phase runs ancestors-first, so this filter sees
                    // the event before the core consumes it. This restores the
                    // key-repeat filtering the legacy editor-action path had
                    // before the Compose rewrite.
                    .onPreviewKeyEvent { event ->
                        event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                            event.nativeKeyEvent.repeatCount > 0
                    }
                    .onKeyEvent { event ->
                        // Fallback for hosts whose text-field core doesn't map
                        // Enter to the IME action; repeats never get here (the
                        // preview filter above consumed them).
                        if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER &&
                            event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        ) {
                            onLaunchActiveApp()
                            true
                        } else {
                            false
                        }
                    }
                    .testTag(SEARCH_FIELD_TAG),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        FilterClearButton(
                            onClick = onClearQuery,
                            contentDescription = stringResource(R.string.app_search_clear_button_description),
                        )
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
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus(force = false)
                        onLaunchActiveApp()
                    },
                ),
            )
            if (suggestion != null) {
                // Overlay the inline autocomplete suggestion on the field's right
                // edge. A plain Text consumes no pointer events, so tap-to-focus
                // still reaches the field underneath.
                SearchSuggestionOverlay(
                    suggestion = suggestion,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .testTag(SEARCH_SUGGESTION_TAG),
                )
            }
        }
    }
}

/**
 * Set of test tags applied to the dock card and its slot contents.
 * Two pre-defined values: [Personal] for the home dock and [Work] for the
 * work-apps dock rendered below it. Lets a single [DockCard] implementation
 * back both surfaces while keeping their screenshot tests addressable
 * independently.
 */
internal data class DockTestTags(
    val cardTag: String,
    val listTag: String,
    val appTag: String,
    val appIconTag: String,
    val addButtonTag: String,
    val folderTag: String,
) {
    companion object {
        val Personal = DockTestTags(
            cardTag = DOCK_CARD_TAG,
            listTag = DOCK_LIST_TAG,
            appTag = DOCK_APP_TAG,
            appIconTag = DOCK_APP_ICON_TAG,
            addButtonTag = DOCK_ADD_BUTTON_TAG,
            folderTag = DOCK_FOLDER_TAG,
        )
        val Work = DockTestTags(
            cardTag = WORK_DOCK_CARD_TAG,
            listTag = WORK_DOCK_LIST_TAG,
            appTag = WORK_DOCK_APP_TAG,
            appIconTag = WORK_DOCK_APP_ICON_TAG,
            addButtonTag = WORK_DOCK_ADD_BUTTON_TAG,
            folderTag = WORK_DOCK_FOLDER_TAG,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DockCard(
    dockedApps: List<InstalledApp>,
    dockPositions: Map<String, DockPosition>,
    dockIconSizeDp: Int,
    dockIconCount: Int,
    dockLayout: DockLayout,
    modifier: Modifier = Modifier,
    dockFolders: List<ResolvedDockFolder> = emptyList(),
    foldersEnabled: Boolean = false,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onReorderDock: (String, Int, Int) -> Unit,
    onMergeDock: (String, String) -> Unit = { _, _ -> },
    onRemoveFromFolder: (String, String) -> Unit = { _, _ -> },
    onUndockFromFolder: (String, String) -> Unit = { _, _ -> },
    onExplodeFolder: (String) -> Unit = {},
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    onDragStateChanged: (Boolean) -> Unit = {},
    tags: DockTestTags = DockTestTags.Personal,
    // Defaults to false so inert callsites (Settings preview, future
    // screenshot-only renders) never advertise the onboarding affordance.
    // The Home callsites pass `state.shouldShowDockAddHint` /
    // `state.shouldShowWorkDockAddHint` explicitly.
    showAddButtonHint: Boolean = false,
) {
    // Drag-to-reorder state is hoisted here so the pointer loop can compare
    // the dragged icon's center against every rendered slot, including empty
    // cells. This keeps sparse dock locations addressable instead of forcing
    // the icons through a packed list. `draggedAppId` holds the occupant being
    // dragged — an app id *or* a folder id.
    var draggedAppId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // When folders are enabled, the occupant id the dragged icon is hovering
    // over for a merge (null = no merge pending; on release the dragged icon
    // joins this target's folder). Always null when folders are disabled, so
    // the dock keeps its pre-folders reorder/swap physics.
    var hoveredMergeTargetId by remember { mutableStateOf<String?>(null) }
    // The folder whose popup grid is open, or null. Cleared on rotation /
    // recomposition reset like the actions menu.
    var openFolderId by remember { mutableStateOf<String?>(null) }
    val slotCenters = remember { mutableStateMapOf<DockPosition, Offset>() }
    // Occupant id list = loose docked apps + folders. Both flow through the
    // same grid machinery; `resolvedDockPositions` keys by id regardless.
    val occupantIds = dockedApps.map { app -> app.id } + dockFolders.map { folder -> folder.id }
    val latestOccupantIds by rememberUpdatedState(occupantIds.toSet())
    val resolvedPositions = resolvedDockPositions(occupantIds, dockPositions, dockIconCount)
    val occupantByPosition = resolvedPositions.entries.associate { (id, position) -> position to id }
    val latestOnReorderDock by rememberUpdatedState(onReorderDock)
    val latestOnMergeDock by rememberUpdatedState(onMergeDock)
    val latestOnDragStateChanged by rememberUpdatedState(onDragStateChanged)
    val scrollState = rememberScrollState()
    val columns = dockIconCount.coerceAtLeast(1)
    val occupiedPositions = resolvedPositions.values.toSet()
    val maxOccupiedRow = occupiedPositions.maxOfOrNull { position -> position.row } ?: 0
    val rowCount = (maxOccupiedRow + 1).coerceAtLeast(1)
    val appByPosition = dockedApps.mapNotNull { app ->
        resolvedPositions[app.id]?.let { position -> position to app }
    }.toMap()
    val folderByPosition = dockFolders.mapNotNull { folder ->
        resolvedPositions[folder.id]?.let { position -> position to folder }
    }.toMap()
    val firstEmptyPosition = (0 until rowCount)
        .asSequence()
        .flatMap { row -> (0 until columns).asSequence().map { column -> DockPosition(row, column) } }
        .firstOrNull { position -> position !in occupiedPositions }
    val showAddButton = showAddButtonHint &&
        draggedAppId == null &&
        rowCount == 1 &&
        firstEmptyPosition != null

    // Shared drag handlers for both app and folder slots, keyed by occupant id.
    val onOccupantDragStart: (String) -> Unit = { occupantId ->
        draggedAppId = occupantId
        dragOffset = Offset.Zero
        hoveredMergeTargetId = null
    }
    val onOccupantDrag: (String, Offset) -> Unit = { occupantId, delta ->
        val visibleCenters = slotCenters.filterKeys { slot ->
            slot.row in 0 until rowCount && slot.column in 0 until columns
        }
        handleDockDrag(
            delta = delta,
            draggedAppId = occupantId,
            currentOccupantIds = latestOccupantIds,
            currentDockPositions = resolvedPositions,
            slotCenters = visibleCenters,
            onReorder = latestOnReorderDock,
            currentOffset = dragOffset,
            setOffset = { dragOffset = it },
            mergeEnabled = foldersEnabled,
            occupantByPosition = occupantByPosition,
            onMergeTarget = { hoveredMergeTargetId = it },
        )
    }
    val onOccupantDragEnd: (String) -> Unit = { occupantId ->
        val target = hoveredMergeTargetId
        if (foldersEnabled && target != null && target != occupantId) {
            latestOnMergeDock(occupantId, target)
        }
        draggedAppId = null
        dragOffset = Offset.Zero
        hoveredMergeTargetId = null
    }

    SectionCard(modifier.testTag(tags.cardTag)) {
        // Every dock slot is a direct sibling under one parent so each
        // `key(slotKey)` movable group lives in the same Compose
        // slot-table parent. That preserves per-icon `pointerInput`
        // modifier nodes across mid-drag swaps: when an icon's keyed
        // group moves to a different position in the grid, Compose
        // recognises it as a sibling move and the in-flight gesture
        // coroutine survives.
        //
        // Three layout details have to hold simultaneously for that
        // sibling-move invariant to apply, and dropping any one of them
        // re-introduces the "drag drops after one cell" bug:
        //   1. `key()` wraps the whole `if/else` block, not just the
        //      `if (app != null)` branch. With `key()` inside the
        //      branch, the keyed group is parented to the per-iteration
        //      replaceable group of the conditional, so any swap that
        //      flips a slot between occupied and empty would be a
        //      cross-parent move.
        //   2. The for-loop is flat (one `for (slotIndex …)`), not
        //      nested. The Compose compiler wraps each iteration of
        //      the outer `for (row …)` loop in its own group, so
        //      nested loops put row-0 keys and row-1 keys in different
        //      parents and a cross-row drag falls back to remove + add.
        //   3. The container is a single `FlowRow(maxItemsInEachRow =
        //      columns)`, not `Column { for (row) Row { … } }`. Per-row
        //      `Row` composables are also separate parents — same
        //      cross-row breakage as (2).
        //
        // Empty slots use a position-keyed sentinel so the dragged
        // icon's `app.id` key can never collide with a freshly-empty
        // slot at the same iteration index.
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .testTag(tags.listTag),
            horizontalArrangement = Arrangement.spacedBy(DOCK_ITEM_SPACING_DP.dp),
            verticalArrangement = Arrangement.spacedBy(DOCK_ITEM_SPACING_DP.dp),
            maxItemsInEachRow = columns,
        ) {
            for (slotIndex in 0 until rowCount * columns) {
                val row = slotIndex / columns
                val column = slotIndex % columns
                val position = DockPosition(row, column)
                val app = appByPosition[position]
                val folder = folderByPosition[position]
                // The key wraps the whole `when` (invariant 1) and every slot
                // is a flat sibling of the one FlowRow (invariants 2 + 3); a
                // folder occupant keys by its `folder:`-prefixed id, which can
                // never collide with an app id or the empty-slot sentinel.
                val slotKey = app?.id ?: folder?.id ?: "dock-empty-${position.row}-${position.column}"
                key(slotKey) {
                    when {
                        app != null -> DockedAppButton(
                            app = app,
                            dockIconSizeDp = dockIconSizeDp,
                            dockLayout = dockLayout,
                            isDragged = draggedAppId == app.id,
                            isMergeTarget = foldersEnabled && hoveredMergeTargetId == app.id,
                            dragOffset = if (draggedAppId == app.id) dragOffset else Offset.Zero,
                            modifier = Modifier.weight(1f),
                            appTag = tags.appTag,
                            appIconTag = tags.appIconTag,
                            onLaunchApp = onLaunchApp,
                            onOpenAppInfo = onOpenAppInfo,
                            onToggleDock = onToggleDock,
                            onResetRank = onResetRank,
                            onRenameApp = onRenameApp,
                            onSetAppIconOverride = onSetAppIconOverride,
                            onClearAppIconOverride = onClearAppIconOverride,
                            onSetAppBadge = onSetAppBadge,
                            onHideApp = onHideApp,
                            onReportSlotCenter = { center -> slotCenters[position] = center },
                            onDragStart = { onOccupantDragStart(app.id) },
                            onDrag = { delta -> onOccupantDrag(app.id, delta) },
                            onDragEnd = { onOccupantDragEnd(app.id) },
                            onLongPressArmed = { armed -> latestOnDragStateChanged(armed) },
                        )
                        folder != null -> DockFolderButton(
                            folder = folder,
                            dockIconSizeDp = dockIconSizeDp,
                            dockLayout = dockLayout,
                            isDragged = draggedAppId == folder.id,
                            isMergeTarget = foldersEnabled && hoveredMergeTargetId == folder.id,
                            dragOffset = if (draggedAppId == folder.id) dragOffset else Offset.Zero,
                            modifier = Modifier.weight(1f),
                            folderTag = tags.folderTag,
                            appIconTag = tags.appIconTag,
                            onOpen = { openFolderId = folder.id },
                            onExplode = { onExplodeFolder(folder.id) },
                            onReportSlotCenter = { center -> slotCenters[position] = center },
                            onDragStart = { onOccupantDragStart(folder.id) },
                            onDrag = { delta -> onOccupantDrag(folder.id, delta) },
                            onDragEnd = { onOccupantDragEnd(folder.id) },
                            onLongPressArmed = { armed -> latestOnDragStateChanged(armed) },
                        )
                        showAddButton && position == firstEmptyPosition -> DockAddButton(
                            dockIconSizeDp = dockIconSizeDp,
                            dockLayout = dockLayout,
                            modifier = Modifier.weight(1f),
                            addButtonTag = tags.addButtonTag,
                            onReportSlotCenter = { center -> slotCenters[position] = center },
                        )
                        else -> EmptyDockSlot(
                            dockIconSizeDp = dockIconSizeDp,
                            dockLayout = dockLayout,
                            modifier = Modifier.weight(1f),
                            onReportSlotCenter = { center -> slotCenters[position] = center },
                        )
                    }
                }
            }
        }
    }
    // Opening a folder is NOT gated on `foldersEnabled`: a user who created
    // folders and later turned the setting off must still be able to open them
    // (launch members, move one out, or explode) — otherwise the apps inside are
    // stranded, since folder members are also deduped out of the app list. The
    // setting only gates *creating* folders (the merge path), not opening or
    // dismantling existing ones.
    val openFolder = dockFolders.firstOrNull { folder -> folder.id == openFolderId }
    if (openFolder != null) {
        DockFolderPopup(
            folder = openFolder,
            dockIconSizeDp = dockIconSizeDp,
            appIconTag = tags.appIconTag,
            onDismiss = { openFolderId = null },
            onLaunchApp = { app ->
                openFolderId = null
                onLaunchApp(app)
            },
            onOpenAppInfo = onOpenAppInfo,
            onRemoveFromFolder = { appId -> onRemoveFromFolder(openFolder.id, appId) },
            onUndockFromFolder = { appId -> onUndockFromFolder(openFolder.id, appId) },
            onExplodeFolder = {
                onExplodeFolder(openFolder.id)
                openFolderId = null
            },
            onRenameApp = onRenameApp,
            onResetRank = onResetRank,
            onSetAppIconOverride = onSetAppIconOverride,
            onClearAppIconOverride = onClearAppIconOverride,
            onSetAppBadge = onSetAppBadge,
            onHideApp = onHideApp,
        )
    }
}

// Corner radius of a folder tile / merge-preview background, on the 4dp grid.
private const val DOCK_FOLDER_CORNER_RADIUS_DP = 12

// Fixed width of the folder popup grid (the documented 4×4 cap). Independent of
// the dock's own column count, so a narrow dock / large icons can't force the
// popup into a tall single-column list that overflows the dialog.
private const val DOCK_FOLDER_POPUP_COLUMNS = 4

// Tile padding stays on the 4dp grid. The 2dp inter-cell gap is intentionally
// off-grid: it is the *intra-glyph* gap between the four sub-icons of one
// composite folder tile, not layout spacing between sibling elements (the 4dp
// grid governs the latter). At the default 43dp icon the tile's inner area is
// ~35dp and each sub-icon is ~17dp, so two sub-icons plus a 4dp gap would
// overflow the tile; 2dp keeps the 2x2 reading as a single folder mark.
private const val DOCK_FOLDER_MINI_GAP_DP = 2
private const val DOCK_FOLDER_TILE_PADDING_DP = 4

/**
 * A folder occupying one dock slot. Renders a 2×2 mini-icon (the first four
 * members, one per corner) inside a rounded tile; a tap opens the folder popup,
 * a long-press arms the same drag the apps use (so a folder reorders or merges
 * like any occupant), and a long-press-release opens a small folder menu.
 *
 * The outer `Box` mirrors [DockedAppButton]'s contract exactly — same
 * `onReportSlotCenter` via `positionInRoot`, same `zIndex` / `graphicsLayer`
 * lift — so the drag hit-testing in [DockCard] treats a folder slot
 * identically to an app slot.
 */
@Composable
private fun DockFolderButton(
    folder: ResolvedDockFolder,
    dockIconSizeDp: Int,
    dockLayout: DockLayout,
    isDragged: Boolean,
    dragOffset: Offset,
    modifier: Modifier = Modifier,
    isMergeTarget: Boolean = false,
    folderTag: String = DOCK_FOLDER_TAG,
    appIconTag: String = DOCK_APP_ICON_TAG,
    onOpen: () -> Unit,
    onExplode: () -> Unit = {},
    onReportSlotCenter: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onLongPressArmed: (Boolean) -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val slopPx = with(density) { 8.dp.toPx() }
    val slotMinHeight = dockSlotHeightDp(dockIconSizeDp, dockLayout, density.fontScale).dp
    val folderName = folder.name
    val contentDescription = folderName ?: stringResource(R.string.dock_folder_content_description)
    val latestOnReportSlotCenter by rememberUpdatedState(onReportSlotCenter)
    val latestOnOpen by rememberUpdatedState(onOpen)
    val latestOnExplode by rememberUpdatedState(onExplode)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnLongPressArmed by rememberUpdatedState(onLongPressArmed)
    Box(
        modifier = modifier
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                latestOnReportSlotCenter(
                    Offset(pos.x + coords.size.width / 2f, pos.y + coords.size.height / 2f),
                )
            }
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                if (isDragged) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.85f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .semantics { this.contentDescription = contentDescription }
                .defaultMinSize(minHeight = slotMinHeight)
                .testTag("$folderTag:${folder.id}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DockFolderMiniIcon(
                folder = folder,
                dockIconSizeDp = dockIconSizeDp,
                appIconTag = appIconTag,
                emphasized = isMergeTarget,
            )
            if (dockLayout == DockLayout.TitleBelow && folderName != null) {
                Text(
                    folderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = { latestOnOpen() },
                )
                // Same gesture skeleton as DockedAppButton: long-press arms a
                // drag (so a folder can be reordered or merged), and a
                // release without crossing slop opens the folder menu rather
                // than launching.
                .pointerInput(folder.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPress.consume()
                        latestOnLongPressArmed(true)
                        var dragging = false
                        var totalDelta = Offset.Zero
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) {
                                    if (!dragging && !change.isConsumed) {
                                        menuExpanded = true
                                    }
                                    change.consume()
                                    break
                                }
                                val delta = change.positionChange()
                                totalDelta += delta
                                if (!dragging && totalDelta.getDistance() > slopPx) {
                                    dragging = true
                                    latestOnDragStart()
                                    latestOnDrag(totalDelta)
                                } else if (dragging) {
                                    latestOnDrag(delta)
                                }
                                change.consume()
                            }
                        } finally {
                            if (dragging) {
                                latestOnDragEnd()
                            }
                            latestOnLongPressArmed(false)
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                    onLongClick(label = null) {
                        menuExpanded = true
                        true
                    }
                },
        )
        DockFolderActionsMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onOpenFolder = latestOnOpen,
            onExplodeFolder = latestOnExplode,
        )
    }
}

/**
 * The 2×2 mini-icon for a folder tile: the first four members, one per corner,
 * inside a rounded tile. Unused corners stay blank; members past four appear
 * only in the open popup.
 */
@Composable
internal fun DockFolderMiniIcon(
    folder: ResolvedDockFolder,
    dockIconSizeDp: Int,
    appIconTag: String = DOCK_APP_ICON_TAG,
    emphasized: Boolean = false,
) {
    val tileSizeDp = dockIconSizeDp.dp
    val cellSizeDp = (dockIconSizeDp * 0.4f).dp
    val corners = folder.members.take(4)
    val tileColor = if (emphasized) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier.size((dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP).dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(tileSizeDp)
                .background(tileColor, RoundedCornerShape(DOCK_FOLDER_CORNER_RADIUS_DP.dp))
                .padding(DOCK_FOLDER_TILE_PADDING_DP.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(DOCK_FOLDER_MINI_GAP_DP.dp)) {
                for (rowIndex in 0 until 2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(DOCK_FOLDER_MINI_GAP_DP.dp)) {
                        for (columnIndex in 0 until 2) {
                            val member = corners.getOrNull(rowIndex * 2 + columnIndex)
                            if (member != null) {
                                AppIcon(
                                    app = member,
                                    size = cellSizeDp,
                                    testTag = appIconTag,
                                )
                            } else {
                                Spacer(modifier = Modifier.size(cellSizeDp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockFolderActionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenFolder: () -> Unit,
    onExplodeFolder: () -> Unit,
) {
    LauncherDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_open_folder)) },
            onClick = {
                onDismiss()
                onOpenFolder()
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_explode)) },
            onClick = {
                onDismiss()
                onExplodeFolder()
            },
        )
    }
}

/**
 * The folder popup: a grid of the folder's member apps hosted in a [Dialog] so
 * it floats above the dock's `FlowRow` instead of participating in its layout.
 * Tapping a member launches it; long-pressing a member opens a menu with
 * "Move out" plus the usual app actions. No `TextField` lives here,
 * so the Robolectric Dialog-idle hang does not apply; the body is split into
 * [DockFolderPopupContent] so the screenshot test can render it without the
 * popup window anyway.
 */
@Composable
private fun DockFolderPopup(
    folder: ResolvedDockFolder,
    dockIconSizeDp: Int,
    appIconTag: String,
    onDismiss: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onRemoveFromFolder: (String) -> Unit,
    onUndockFromFolder: (String) -> Unit,
    onExplodeFolder: () -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit,
    onClearAppIconOverride: (InstalledApp) -> Unit,
    onSetAppBadge: (InstalledApp, String?) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(DOCK_FOLDER_CORNER_RADIUS_DP.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            DockFolderPopupContent(
                folder = folder,
                dockIconSizeDp = dockIconSizeDp,
                appIconTag = appIconTag,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onRemoveFromFolder = onRemoveFromFolder,
                onUndockFromFolder = onUndockFromFolder,
                onExplodeFolder = onExplodeFolder,
                onRenameApp = onRenameApp,
                onResetRank = onResetRank,
                onSetAppIconOverride = onSetAppIconOverride,
                onClearAppIconOverride = onClearAppIconOverride,
                onSetAppBadge = onSetAppBadge,
                onHideApp = onHideApp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DockFolderPopupContent(
    folder: ResolvedDockFolder,
    dockIconSizeDp: Int,
    appIconTag: String = DOCK_APP_ICON_TAG,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onRemoveFromFolder: (String) -> Unit,
    onUndockFromFolder: (String) -> Unit = {},
    onExplodeFolder: () -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit,
    onClearAppIconOverride: (InstalledApp) -> Unit,
    onSetAppBadge: (InstalledApp, String?) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    // A fixed 4-wide grid (the folder cap), independent of the dock's column
    // count, plus a bounded scroll so even a full 16-member folder stays
    // reachable on a short viewport or a narrow / large-icon dock.
    val perRow = folder.members.size.coerceAtMost(DOCK_FOLDER_POPUP_COLUMNS).coerceAtLeast(1)
    val maxPopupHeightDp = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
    Column(
        modifier = Modifier
            .padding(SECTION_CARD_PADDING_DP.dp)
            .heightIn(max = maxPopupHeightDp)
            .verticalScroll(rememberScrollState())
            .testTag(DOCK_FOLDER_POPUP_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        folder.name?.let { name ->
            Text(name, style = MaterialTheme.typography.titleMedium)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            maxItemsInEachRow = perRow,
        ) {
            folder.members.forEach { member ->
                DockFolderMemberButton(
                    app = member,
                    dockIconSizeDp = dockIconSizeDp,
                    appIconTag = appIconTag,
                    onLaunchApp = onLaunchApp,
                    onOpenAppInfo = onOpenAppInfo,
                    onRemoveFromFolder = { onRemoveFromFolder(member.id) },
                    onUndockFromFolder = { onUndockFromFolder(member.id) },
                    onRenameApp = onRenameApp,
                    onResetRank = onResetRank,
                    onSetAppIconOverride = onSetAppIconOverride,
                    onClearAppIconOverride = onClearAppIconOverride,
                    onSetAppBadge = onSetAppBadge,
                    onHideApp = onHideApp,
                )
            }
        }
    }
}

@Composable
private fun DockFolderMemberButton(
    app: InstalledApp,
    dockIconSizeDp: Int,
    appIconTag: String,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onRemoveFromFolder: () -> Unit,
    onUndockFromFolder: () -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit,
    onClearAppIconOverride: (InstalledApp) -> Unit,
    onSetAppBadge: (InstalledApp, String?) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Column(
            modifier = Modifier
                .width((dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP * 2).dp)
                .combinedClickable(
                    onClick = { onLaunchApp(app) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuExpanded = true
                    },
                )
                .padding(vertical = 4.dp)
                .testTag("$DOCK_FOLDER_POPUP_TAG:${app.displayName}"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AppIcon(app = app, size = dockIconSizeDp.dp, testTag = appIconTag)
            Text(
                app.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DockFolderMemberActionsMenu(
            expanded = menuExpanded,
            app = app,
            onDismiss = { menuExpanded = false },
            onOpenAppInfo = onOpenAppInfo,
            onRemoveFromFolder = onRemoveFromFolder,
            onUndockFromFolder = onUndockFromFolder,
            onRenameApp = onRenameApp,
            onResetRank = onResetRank,
            onSetAppIconOverride = onSetAppIconOverride,
            onClearAppIconOverride = onClearAppIconOverride,
            onSetAppBadge = onSetAppBadge,
            onHideApp = onHideApp,
        )
    }
}

@Composable
private fun DockFolderMemberActionsMenu(
    expanded: Boolean,
    app: InstalledApp,
    onDismiss: () -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onRemoveFromFolder: () -> Unit,
    onUndockFromFolder: () -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit,
    onClearAppIconOverride: (InstalledApp) -> Unit,
    onSetAppBadge: (InstalledApp, String?) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
) {
    var editDialogVisible by remember { mutableStateOf(false) }
    LauncherDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_app_info)) },
            onClick = {
                onDismiss()
                onOpenAppInfo(app)
            },
        )
        // "Move out" pops the app back to a loose dock icon; "Undock" takes it
        // off the dock entirely. Both leave the folder.
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_move_out)) },
            onClick = {
                onDismiss()
                onRemoveFromFolder()
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_undock)) },
            onClick = {
                onDismiss()
                onUndockFromFolder()
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_edit)) },
            onClick = {
                onDismiss()
                editDialogVisible = true
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_hide)) },
            onClick = {
                onDismiss()
                onHideApp(app)
            },
        )
    }
    if (editDialogVisible) {
        EditAppDialog(
            app = app,
            onSave = { newName ->
                onRenameApp(app, newName)
                editDialogVisible = false
            },
            onRestoreDefaults = {
                onRenameApp(app, "")
                editDialogVisible = false
            },
            onPickIcon = { onSetAppIconOverride(app) },
            onClearIcon = { onClearAppIconOverride(app) },
            onSetBadge = { glyph -> onSetAppBadge(app, glyph) },
            onDismiss = { editDialogVisible = false },
        )
    }
}

/**
 * Moves the dragged icon to the nearest rendered dock slot, including empty
 * cells. After each persisted move the visual offset is rebased from the old
 * slot center to the new slot center so the lifted icon stays under the finger
 * while the grid recomposes around it.
 *
 * When [mergeEnabled] is true (folders on), the same nearest-slot boundary that
 * used to *swap* onto an occupied neighbor instead arms a *merge*: if the
 * nearest slot is closer than the current slot AND occupied by a different
 * occupant, the drag holds position (no reorder) and reports that occupant via
 * [onMergeTarget]. An empty nearest slot still reorders, so dragging *past* an
 * occupied icon toward an empty slot reorders exactly as before. Merge therefore
 * wins precisely where a swap used to — there is no radius gap for a swap to
 * sneak through first. [onMergeTarget] is called once per invocation with the
 * current target (or null), so the caller can render / clear the preview.
 */
internal fun handleDockDrag(
    delta: Offset,
    draggedAppId: String?,
    // Occupant ids currently in the dock — app ids *and* folder ids. A folder
    // is dragged exactly like an app, so the guard accepts any live occupant.
    currentOccupantIds: Set<String>,
    currentDockPositions: Map<String, DockPosition>,
    slotCenters: Map<DockPosition, Offset>,
    onReorder: (String, Int, Int) -> Unit,
    currentOffset: Offset,
    setOffset: (Offset) -> Unit,
    mergeEnabled: Boolean = false,
    occupantByPosition: Map<DockPosition, String> = emptyMap(),
    onMergeTarget: (String?) -> Unit = {},
) {
    if (draggedAppId == null) return
    if (draggedAppId !in currentOccupantIds) return
    var newOffset = currentOffset + delta
    var currentPosition = currentDockPositions[draggedAppId] ?: run {
        onMergeTarget(null)
        setOffset(newOffset)
        return
    }
    var currentCenter = slotCenters[currentPosition] ?: run {
        onMergeTarget(null)
        setOffset(newOffset)
        return
    }
    var mergeTarget: String? = null
    while (true) {
        val draggedCenter = currentCenter + newOffset
        val nearest = slotCenters.minByOrNull { (_, center) -> (center - draggedCenter).getDistance() }
            ?: break
        val targetPosition = nearest.key
        if (targetPosition == currentPosition) {
            break
        }
        val targetCenter = nearest.value
        if ((targetCenter - draggedCenter).getDistance() >= (currentCenter - draggedCenter).getDistance()) {
            break
        }
        val occupant = occupantByPosition[targetPosition]
        if (mergeEnabled && occupant != null && occupant != draggedAppId) {
            // Closer to an occupied neighbor than to our own slot: this is a
            // merge, not a swap. Hold position and report the target; release
            // commits the merge.
            mergeTarget = occupant
            break
        }
        onReorder(draggedAppId, targetPosition.row, targetPosition.column)
        newOffset += currentCenter - targetCenter
        currentPosition = targetPosition
        currentCenter = targetCenter
    }
    onMergeTarget(mergeTarget)
    setOffset(newOffset)
}

/**
 * Recents card is a secondary bar in the keyboard tray once a keyboard-height
 * reservation exists. The old in-column Show recents setting has been removed;
 * pull-up now asks for the keyboard because recents is already part of the tray.
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
    onBarScrollRegionChanged: (BarScrollRegion?) -> Unit = {},
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
                onBarScrollRegionChanged = onBarScrollRegionChanged,
            )
        }
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
    onBarScrollRegionChanged: (BarScrollRegion?) -> Unit = {},
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
        onBarScrollRegionChanged = onBarScrollRegionChanged,
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
    onBarScrollRegionChanged: (BarScrollRegion?) -> Unit = {},
    content: @Composable RowScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val isScrollRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    DisposableEffect(Unit) {
        onDispose { onBarScrollRegionChanged(null) }
    }
    var hasMeasuredContent by remember { mutableStateOf(false) }
    var overflowSlopPx by remember { mutableStateOf(0) }
    // The carousel reserves a horizontal drag for this strip when it starts on
    // the strip and the strip can still scroll that way. The decision reuses the
    // same overflowSlopPx the chevrons and pin-to-end apply, so a row that only
    // overflows by a rounding pixel pages normally instead of reserving a drag
    // it can barely move. Remembered so the reported region carries a stable
    // lambda; it reads scrollState/overflowSlopPx live at call time.
    val canScrollInDirection = remember(scrollState, isScrollRtl) {
        { rawDragX: Float ->
            barStripCanScrollInDirection(
                rawDragX = rawDragX,
                scrollValue = scrollState.value,
                scrollMaxValue = scrollState.maxValue,
                overflowSlopPx = overflowSlopPx,
                isRtl = isScrollRtl,
            )
        }
    }
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
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            // Report the *viewport* (the visible icon strip) in root
            // coordinates, not the scrolled content — so it stays put under the
            // finger as the row scrolls. The strip excludes the card's 16dp
            // padding, which keeps that padding as page-swipe territory.
            .onGloballyPositioned { coords ->
                onBarScrollRegionChanged(
                    BarScrollRegion(
                        boundsInRoot = Rect(coords.positionInRoot(), coords.size.toSize()),
                        canScrollInDirection = canScrollInDirection,
                    ),
                )
            },
    ) {
        // Stretch the row to at least the viewport width so the centered
        // arrangement has space to distribute when the icons fit on one
        // screen, but use the raw px from `BoxWithConstraints.constraints`
        // (not `maxWidth.dp`) — the Dp round-trip can land 1 px above the
        // viewport on non-integer densities and trip a spurious overflow
        // chevron when the row content actually fits.
        val viewportPx = constraints.maxWidth
        val scope = rememberCoroutineScope()
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val pageBack: () -> Unit = {
            scope.launch { scrollState.scrollOneHorizontalPage(backward = true, viewportPx = viewportPx) }
        }
        val pageForward: () -> Unit = {
            scope.launch { scrollState.scrollOneHorizontalPage(backward = false, viewportPx = viewportPx) }
        }
        Row(
            modifier = rowModifier
                .pointerInput(showStartChevron, showEndChevron, viewportPx, isRtl) {
                    detectTapGestures { offset ->
                        val overhang = HorizontalScrollChevronIconRowOverhang.toPx()
                        // Pointer x is physical (left origin) while the
                        // chevron flags and Alignment.CenterStart/CenterEnd
                        // placement are logical, so under RTL the start
                        // chevron renders at the physical right. Compare in
                        // logical space or the two tap bands act inverted
                        // (and the lone end chevron's band does nothing).
                        val logicalX = if (isRtl) size.width - offset.x else offset.x
                        when {
                            showStartChevron && logicalX <= overhang -> pageBack()
                            showEndChevron && logicalX >= size.width - overhang -> pageForward()
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
                    // visibly fit. Without this, `pinToEndKey` rows (recents)
                    // auto-scroll to that 1 px maxValue, lift
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
 * recents-bar overflow treatment so a long list is discoverable as
 * scrollable instead of relying on the user guessing.
 */
@Composable
private fun AppListOverflowChevronBox(
    canScrollUp: Boolean,
    canScrollDown: Boolean,
    chevronsReady: Boolean,
    chevronContentDescription: String,
    onScrollPageUp: () -> Unit,
    onScrollPageDown: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        content()
        if (chevronsReady && canScrollUp) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = -VerticalScrollChevronEdgeOffset),
            ) {
                AppListOverflowChevron(
                    icon = Icons.Filled.KeyboardArrowUp,
                    contentDescription = chevronContentDescription,
                    testTag = APPS_LIST_SCROLL_TOP_CHEVRON_TAG,
                    onClick = onScrollPageUp,
                )
            }
        }
        if (chevronsReady && canScrollDown) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = VerticalScrollChevronEdgeOffset),
            ) {
                AppListOverflowChevron(
                    icon = Icons.Filled.KeyboardArrowDown,
                    contentDescription = chevronContentDescription,
                    testTag = APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG,
                    onClick = onScrollPageDown,
                )
            }
        }
    }
}

@Composable
private fun AppListOverflowChevron(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(VerticalScrollChevronTapTargetSize)
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
        contentAlignment = Alignment.Center,
    ) {
        ChevronIcon(
            icon = icon,
            contentDescription = contentDescription,
        )
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
// The chevron's own Box sits at offset(±HorizontalScrollChevronEdgeOffset)
// and is HorizontalScrollChevronEdgeOffset wide, so it stays fully outside
// the row. The visible icon uses requiredSize(HorizontalScrollChevronTapTargetSize)
// and overflows back over the row's edge by (TapTargetSize − EdgeOffset) dp
// on each side, anchoring the affordance to the icon strip. The row's own
// pointerInput pages back/forward only when a tap lands in that overflow
// band — past it, the tap is on an app icon, not on the chevron, and the
// row leaves it alone so the app's own click handler can take it.
private val HorizontalScrollChevronIconRowOverhang =
    HorizontalScrollChevronTapTargetSize - HorizontalScrollChevronEdgeOffset
private val VerticalScrollChevronEdgeOffset = 18.dp
private val VerticalScrollChevronTapTargetSize = 32.dp

@Composable
private fun AppsCard(
    apps: List<InstalledApp>,
    isLoading: Boolean = false,
    overflowChevronsReady: Boolean = true,
    dockLimit: Int,
    layout: AppListLayout,
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
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    onAppListBoundsChanged: (Rect?) -> Unit = {},
) {
    LaunchedEffect(isLoading, apps.isEmpty()) {
        if (isLoading || apps.isEmpty()) {
            onAppListBoundsChanged(null)
        }
    }
    // NameBelow and IconOnly both render the grid; only NameBeside renders rows.
    val isGrid = layout != AppListLayout.NameBeside
    val showLabels = layout == AppListLayout.NameBelow
    val chevronLayoutKey = remember(apps, layout, reverseLayout) {
        AppListChevronLayoutKey(
            appIds = apps.map { it.id },
            layout = layout,
            reverseLayout = reverseLayout,
        )
    }
    var measuredChevronLayoutKey by remember { mutableStateOf<AppListChevronLayoutKey?>(null) }
    val isCurrentAppSetMeasured = measuredChevronLayoutKey == chevronLayoutKey
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
            val chevronDescription = stringResource(R.string.apps_list_scroll_more_hint)
            if (isGrid) {
                val gridState = rememberLazyGridState()
                LaunchedEffect(scrollResetKey) { gridState.scrollToItem(0) }
                // In reverseLayout, the visual top is at the END of the data,
                // so the chevron predicate that asks "can we scroll visually
                // up / down" swaps to canScrollForward / canScrollBackward
                // respectively.
                val canScrollUp = if (reverseLayout) gridState.canScrollForward else gridState.canScrollBackward
                val canScrollDown = if (reverseLayout) gridState.canScrollBackward else gridState.canScrollForward
                // layoutInfo is state rewritten after every measure pass, so
                // reading it directly in composition subscribes this whole
                // card scope to every frame of a fling. derivedStateOf
                // confines the invalidation to the measured/not-measured flip.
                val viewportMeasured by remember(gridState) {
                    derivedStateOf { gridState.layoutInfo.viewportSize.height > 0 }
                }
                val chevronsReady = overflowChevronsReady &&
                    isCurrentAppSetMeasured &&
                    viewportMeasured
                val scope = rememberCoroutineScope()
                AppListOverflowChevronBox(
                    canScrollUp = canScrollUp,
                    canScrollDown = canScrollDown,
                    chevronsReady = chevronsReady,
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
                        showLabel = showLabels,
                        highlightFirst = highlightFirst,
                        reverseLayout = reverseLayout,
                        state = gridState,
                        onBoundsChanged = { bounds ->
                            onAppListBoundsChanged(bounds)
                            measuredChevronLayoutKey = chevronLayoutKey
                        },
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onRenameApp = onRenameApp,
                        onSetAppIconOverride = onSetAppIconOverride,
                        onClearAppIconOverride = onClearAppIconOverride,
                        onSetAppBadge = onSetAppBadge,
                        onHideApp = onHideApp,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LaunchedEffect(scrollResetKey) { listState.scrollToItem(0) }
                val canScrollUp = if (reverseLayout) listState.canScrollForward else listState.canScrollBackward
                val canScrollDown = if (reverseLayout) listState.canScrollBackward else listState.canScrollForward
                // Same derivedStateOf rationale as the icon-only branch above:
                // don't subscribe this scope to every-frame layoutInfo writes.
                val viewportMeasured by remember(listState) {
                    derivedStateOf { listState.layoutInfo.viewportSize.height > 0 }
                }
                val chevronsReady = overflowChevronsReady &&
                    isCurrentAppSetMeasured &&
                    viewportMeasured
                val scope = rememberCoroutineScope()
                AppListOverflowChevronBox(
                    canScrollUp = canScrollUp,
                    canScrollDown = canScrollDown,
                    chevronsReady = chevronsReady,
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
                                measuredChevronLayoutKey = chevronLayoutKey
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
                                onRenameApp = onRenameApp,
                                onSetAppIconOverride = onSetAppIconOverride,
                                onClearAppIconOverride = onClearAppIconOverride,
                                onSetAppBadge = onSetAppBadge,
                                onHideApp = onHideApp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class AppListChevronLayoutKey(
    val appIds: List<String>,
    val layout: AppListLayout,
    val reverseLayout: Boolean,
)

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
internal fun IconOnlyAppGrid(
    apps: List<InstalledApp>,
    dockLimit: Int,
    iconSizeDp: Int,
    highlightFirst: Boolean,
    state: LazyGridState,
    showLabel: Boolean = false,
    reverseLayout: Boolean = false,
    onBoundsChanged: (Rect?) -> Unit = {},
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
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
                showLabel = showLabel,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onRenameApp = onRenameApp,
                onSetAppIconOverride = onSetAppIconOverride,
                onClearAppIconOverride = onClearAppIconOverride,
                onSetAppBadge = onSetAppBadge,
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
    showLabel: Boolean = false,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val highlightColor = selectionHighlightColor()
    val highlightOnColor = selectionHighlightOnColor()
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
            if (showLabel) {
                Text(
                    app.displayName,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) highlightOnColor else MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    // Single line so every tile is the same height and the grid
                    // rows stay even; longer names ellipsize rather than wrap.
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
            onRenameApp = onRenameApp,
            onSetAppIconOverride = onSetAppIconOverride,
            onClearAppIconOverride = onClearAppIconOverride,
            onSetAppBadge = onSetAppBadge,
            onHideApp = onHideApp,
            isFlatSurface = true,
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
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
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
            onRenameApp = onRenameApp,
            onSetAppIconOverride = onSetAppIconOverride,
            onClearAppIconOverride = onClearAppIconOverride,
            onSetAppBadge = onSetAppBadge,
            onHideApp = onHideApp,
            isFlatSurface = true,
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
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    // True on the flat app list / recents, where a folder member should not
    // offer a dock toggle (folder membership is managed in the folder popup).
    // Dock tiles pass false so a tile always toggles its own dock, even when
    // the same app is foldered on the other dock (the cross-dock case).
    isFlatSurface: Boolean = false,
) {
    // Boolean rather than the InstalledApp itself so dialog visibility doesn't
    // re-evaluate identity-based equality on every parent recomposition; the
    // dialog reads `app` directly from this composable's parameter.
    var editDialogVisible by remember { mutableStateOf(false) }
    LauncherDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onOpenAppInfo(app)
            },
        )
        // A folder member on a flat surface (app list / recents) gets no dock
        // toggle: re-docking it no-ops in the store and folder membership is
        // managed in the folder popup (Move out / Undock). Dock tiles still
        // show the toggle for their own dock.
        if (!(isFlatSurface && app.isInFolder)) {
            DropdownMenuItem(
                text = {
                    LauncherMenuItemText(
                        stringResource(
                            if (app.isDocked || app.isWorkDocked) R.string.app_menu_undock
                            else R.string.app_menu_dock,
                        ),
                    )
                },
                modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.displayName}"),
                onClick = {
                    onDismiss()
                    onToggleDock(app, dockLimit)
                },
            )
        }
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_reset_rank)) },
            modifier = Modifier.testTag("$RESET_RANK_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onResetRank(app)
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_edit)) },
            modifier = Modifier.testTag("$EDIT_APP_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                editDialogVisible = true
            },
        )
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_hide)) },
            modifier = Modifier.testTag("$HIDE_APP_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismiss()
                onHideApp(app)
            },
        )
    }
    if (editDialogVisible) {
        EditAppDialog(
            app = app,
            onSave = { newName ->
                onRenameApp(app, newName)
                editDialogVisible = false
            },
            onRestoreDefaults = {
                onRenameApp(app, "")
                editDialogVisible = false
            },
            onPickIcon = {
                // Keep the dialog open while the system picker is up so the
                // user lands back here after choosing — and once the new
                // icon flows through `markVisibility`, the preview at the
                // top of the dialog refreshes to show it.
                onSetAppIconOverride(app)
            },
            onClearIcon = {
                onClearAppIconOverride(app)
            },
            onSetBadge = { glyph -> onSetAppBadge(app, glyph) },
            onDismiss = { editDialogVisible = false },
        )
    }
}

/**
 * Dialog that lets the user override an app's display label and launcher
 * icon, and review the publisher's shipped values (system label + Android
 * package name). Submits the trimmed text via [onSave]; an empty string is
 * forwarded so the ViewModel can drop the rename override consistently with
 * [onRestoreDefaults], which also fires when the user taps the inline
 * "Restore defaults" action — only shown when a rename override is currently
 * in effect.
 *
 * Tapping [onPickIcon] kicks off the system file picker for an SVG/PNG/
 * JPEG/WEBP image. The picker activity runs outside this composable; the
 * chosen URI flows back through `LauncherViewModel.setAppIconOverride` and
 * the next markVisibility pass mirrors the new `customIconPath` onto every
 * `InstalledApp` instance — including the `app` parameter passed in here
 * the next time the parent recomposes — so the preview at the top of the
 * dialog refreshes without the dialog needing to close. [onClearIcon]
 * drops the icon override and is hidden until one is in effect, mirroring
 * the rename-restore behaviour.
 *
 * The button slots stay single-button (Material3 AlertDialog assumes one
 * widget per slot); the rename Restore + the icon controls live in the body
 * so they do not contend with Save / Cancel for the slot.
 */
@Composable
internal fun EditAppDialog(
    app: InstalledApp,
    onSave: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onSetBadge: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Low-level `Dialog` + custom `Surface` rather than `AlertDialog`: the
    // material `AlertDialog`'s `text` slot wraps its content in a vertically
    // scrollable `Column` with unbounded height constraints, and any
    // `TextField` / `OutlinedTextField` placed inside it measures itself
    // recursively against those constraints under Robolectric, causing
    // `composeRule.waitForIdle` to spin past 60 s. Hand-rolling the layout
    // keeps the same visual structure (title + body + buttons) without the
    // scrollable wrapper, breaking the loop.
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EDIT_APP_DIALOG_TAG),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            EditAppDialogContent(
                app = app,
                onSave = onSave,
                onRestoreDefaults = onRestoreDefaults,
                onPickIcon = onPickIcon,
                onClearIcon = onClearIcon,
                onSetBadge = onSetBadge,
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * The dialog body, factored out of [EditAppDialog] so a Robolectric
 * screenshot test can compose just the content without the surrounding
 * `Dialog` popup window. A `TextField` rendered inside Compose's
 * `Dialog` window does not settle on Robolectric (`waitForIdle` blows
 * past 60 s during initial composition), so the test renders this
 * content directly inside an activity-hosted Compose tree instead.
 * The visual layout matches what the user sees inside the popup
 * because [EditAppDialog] wraps exactly this content in a `Dialog` +
 * `Surface`.
 */
@Composable
internal fun EditAppDialogContent(
    app: InstalledApp,
    onSave: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onPickIcon: () -> Unit,
    onClearIcon: () -> Unit,
    onSetBadge: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // rememberSaveable so a configuration change (rotation, dark-mode toggle,
    // font-scale change) while the dialog is open keeps the user's typed text
    // instead of resetting it to the app's current name. Keyed on app.id so
    // reusing the slot for a different app still resets to that app's name.
    var text by rememberSaveable(app.id) { mutableStateOf(app.customName ?: app.name) }
    var badgePickerVisible by remember { mutableStateOf(false) }
    val hasOverride = !app.customName.isNullOrBlank()
    val hasIconOverride = app.customIconPath != null
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.edit_app_dialog_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EDIT_APP_DIALOG_ICON_ROW_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Render the icon at 48 dp so the user sees roughly what the app
            // list will display. `AppIcon` honours `customIconPath` via the
            // shared `AppIconLoader.load` path, so it shows the live override
            // when one is set without any extra plumbing.
            AppIcon(
                app = app,
                size = 48.dp,
                testTag = EDIT_APP_DIALOG_ICON_TAG,
            )
            Column(modifier = Modifier.weight(1f)) {
                TextButton(
                    onClick = onPickIcon,
                    modifier = Modifier.testTag(EDIT_APP_DIALOG_CHOOSE_ICON_TAG),
                ) {
                    Text(stringResource(R.string.edit_app_dialog_choose_icon))
                }
                if (hasIconOverride) {
                    TextButton(
                        onClick = onClearIcon,
                        modifier = Modifier.testTag(EDIT_APP_DIALOG_CLEAR_ICON_TAG),
                    ) {
                        Text(stringResource(R.string.edit_app_dialog_clear_icon))
                    }
                }
            }
        }
        EditAppDialogBadgeRow(
            app = app,
            onChooseBadge = { badgePickerVisible = true },
        )
        TextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            label = { Text(stringResource(R.string.edit_app_dialog_label)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(EDIT_APP_DIALOG_FIELD_TAG),
        )
        Text(
            text = stringResource(R.string.edit_app_dialog_original, app.name),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.edit_app_dialog_package, app.packageName),
            modifier = Modifier.testTag(EDIT_APP_DIALOG_PACKAGE_TAG),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasOverride) {
            TextButton(
                onClick = onRestoreDefaults,
                modifier = Modifier
                    .align(Alignment.Start)
                    .testTag(EDIT_APP_DIALOG_RESTORE_TAG),
            ) {
                Text(stringResource(R.string.edit_app_dialog_restore))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(EDIT_APP_DIALOG_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.edit_app_dialog_cancel))
            }
            TextButton(
                onClick = { onSave(text) },
                modifier = Modifier.testTag(EDIT_APP_DIALOG_SAVE_TAG),
            ) {
                Text(stringResource(R.string.edit_app_dialog_save))
            }
        }
    }
    if (badgePickerVisible) {
        BadgePickerDialog(
            currentBadge = app.customBadge,
            onPickBadge = { glyph ->
                onSetBadge(glyph)
            },
            onDismiss = { badgePickerVisible = false },
        )
    }
}

/**
 * Single row inside [EditAppDialogContent] that previews the app's current
 * corner badge (or a "Default" placeholder when none is set) and exposes a
 * "Choose badge" button. Tapping the button opens [BadgePickerDialog]. The
 * row sits below the icon row so the user reads "icon, then badge, then
 * label" — the same top-to-bottom order as the corner-badge stacking on
 * the rendered launcher tile.
 */
@Composable
private fun EditAppDialogBadgeRow(
    app: InstalledApp,
    onChooseBadge: () -> Unit,
) {
    val customBadgeGlyph = app.customBadge?.takeIf { it.isNotEmpty() }
    val previewGlyph = customBadgeGlyph
        ?: app.effectiveDisambiguator
            ?.takeIf { it.isNotEmpty() }
            ?.let(::disambiguatorBadge)
            ?.glyph
    val defaultLabel = stringResource(R.string.edit_app_dialog_badge_default_label)
    val previewLabel = previewGlyph ?: defaultLabel
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(EDIT_APP_DIALOG_BADGE_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .testTag(EDIT_APP_DIALOG_BADGE_PREVIEW_TAG)
                .semantics {
                    contentDescription =
                        "${app.displayName} badge: $previewLabel"
                },
            contentAlignment = Alignment.Center,
        ) {
            if (previewGlyph != null) {
                Text(
                    text = previewGlyph,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                )
            } else {
                Text(
                    text = defaultLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.edit_app_dialog_badge_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(
                onClick = onChooseBadge,
                modifier = Modifier.testTag(EDIT_APP_DIALOG_CHOOSE_BADGE_TAG),
            ) {
                Text(stringResource(R.string.edit_app_dialog_choose_badge))
            }
        }
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
    LauncherDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissMenu,
        properties = AppActionsMenuPopupProperties,
    ) {
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_app_info)) },
            modifier = Modifier.testTag("$APP_INFO_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onOpenAppInfo(app)
            },
        )
        // A folder member has no plain dock toggle here: re-docking a foldered
        // app no-ops in the store, so the item would be a dead button. Folder
        // membership is managed in the folder popup (Move out / Undock).
        if (!app.isInFolder) {
            DropdownMenuItem(
                text = {
                    LauncherMenuItemText(
                        stringResource(
                            if (app.isDocked || app.isWorkDocked) {
                                R.string.app_menu_undock
                            } else {
                                R.string.app_menu_dock
                            },
                        ),
                    )
                },
                modifier = Modifier.testTag("$TOGGLE_DOCK_ACTION_TAG:${app.displayName}"),
                onClick = {
                    onDismissMenu()
                    onToggleDock(app, Int.MAX_VALUE)
                },
            )
        }
        DropdownMenuItem(
            text = { LauncherMenuItemText(stringResource(R.string.app_menu_dismiss)) },
            modifier = Modifier.testTag("$DISMISS_RECENT_ACTION_TAG:${app.displayName}"),
            onClick = {
                onDismissMenu()
                onDismissRecent(app)
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
    dockLayout: DockLayout,
    isDragged: Boolean,
    dragOffset: Offset,
    modifier: Modifier = Modifier,
    // True while another icon is being dragged onto this one: the slot morphs
    // into a closed-folder preview tile (the existing app shown inside a folder
    // background) so a release here reads as "drop to put these in a folder."
    isMergeTarget: Boolean = false,
    appTag: String = DOCK_APP_TAG,
    appIconTag: String = DOCK_APP_ICON_TAG,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    onReportSlotCenter: (Offset) -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onLongPressArmed: (Boolean) -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val slopPx = with(density) { 8.dp.toPx() }
    // Honor the system font scale so a larger accessibility font lifts the
    // floor and the `labelSmall` line never clips against the next row.
    val slotMinHeight = dockSlotHeightDp(dockIconSizeDp, dockLayout, density.fontScale).dp
    // Wrap the parent's drag callbacks in updated-state holders so the
    // long-running pointerInput coroutine always invokes the freshest
    // closure (recompositions reallocate the lambdas every frame).
    val latestOnReportSlotCenter by rememberUpdatedState(onReportSlotCenter)
    val latestOnDragStart by rememberUpdatedState(onDragStart)
    val latestOnDrag by rememberUpdatedState(onDrag)
    val latestOnDragEnd by rememberUpdatedState(onDragEnd)
    val latestOnLongPressArmed by rememberUpdatedState(onLongPressArmed)
    Box(
        modifier = modifier
            // onGloballyPositioned sits outside the graphicsLayer so it
            // reports the icon's static slot centre, not its translated
            // visual centre — that's what the parent compares against.
            // positionInRoot() (not positionInParent()) puts every slot in
            // one window-wide coordinate space; with the dock's slots laid
            // out by FlowRow as siblings of one parent, positionInParent()
            // would lose the per-row vertical offset and the drag handler
            // could not tell rows apart on multi-row docks.
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                val center = Offset(
                    pos.x + coords.size.width / 2f,
                    pos.y + coords.size.height / 2f,
                )
                latestOnReportSlotCenter(center)
            }
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                if (isDragged) {
                    translationX = dragOffset.x
                    translationY = dragOffset.y
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.85f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .semantics { contentDescription = app.displayName }
                // `defaultMinSize` so the `labelSmall` line can grow past the
                // 20 dp default-scale floor at large accessibility font sizes
                // (where the line height alone exceeds it); the Column then
                // sizes to its content and lifts the FlowRow row with it.
                .defaultMinSize(minHeight = slotMinHeight)
                .testTag("$appTag:${app.displayName}"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size((dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP).dp)
                    .then(
                        if (isMergeTarget) {
                            Modifier.background(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(DOCK_FOLDER_CORNER_RADIUS_DP.dp),
                            )
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Shrink the icon when it becomes a merge target so it reads as
                // sitting *inside* the forming folder tile.
                AppIcon(
                    app = app,
                    size = (if (isMergeTarget) (dockIconSizeDp * 0.6f) else dockIconSizeDp.toFloat()).dp,
                    testTag = appIconTag,
                )
            }
            if (dockLayout == DockLayout.TitleBelow) {
                Text(
                    app.displayName,
                    modifier = Modifier.testTag("$DOCK_APP_TITLE_TAG:${app.displayName}"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                // Long-press fires haptic feedback and arms the dock for
                // either a reorder (if the finger crosses 8 dp slop) or for
                // opening the AppActionsMenu (if the user releases without
                // crossing slop). The menu is intentionally not opened at
                // the long-press timeout: `DropdownMenu` uses a `Popup` whose
                // window remains touch-modal within its own bounds even with
                // `focusable = false`, so the moment the user dragged their
                // finger into the popup region Android would send the
                // original window an `ACTION_CANCEL` and the drag would
                // drop one slot in. Deferring the menu until release means
                // the popup never exists while a reorder is in flight, so
                // the drag survives until the finger actually lifts.
                //
                // `latestOnLongPressArmed(true)` runs the moment the
                // long-press fires (before any slop accounting) so the
                // carousel's gesture surface is suppressed from then on:
                // small pre-long-press drift within the long-press touch
                // slop can already push the carousel's accumulated rawDragX
                // above its own 8 dp claim threshold, and without the early
                // signal a small post-long-press move would let the carousel
                // page Home → Widgets/Agenda before the dock's own slop
                // accounting (which restarts at zero after long-press) had a
                // chance to set the suppression latch.
                //
                // The pointerInput is attached per-icon (not at the
                // DockCard level) on purpose: the parent dock is a single
                // FlowRow with every slot's `key(app.id)` keyed group as a
                // direct sibling, so a slot swap mid-drag is recognised by
                // Compose as a sibling move and this pointerInput modifier
                // node — and its in-flight gesture coroutine — survive the
                // recomposition. Hoisting drag detection up the tree
                // would force a slot-centre hit-test on every press
                // (problematic for taps in card padding or near empty
                // cells); keeping it on the icon means the icon's
                // `clickable` hit region is the natural press boundary.
                .pointerInput(app.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                            ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longPress.consume()
                        latestOnLongPressArmed(true)
                        var dragging = false
                        var totalDelta = Offset.Zero
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) {
                                    // A release that arrives already consumed
                                    // is the framework's cancel signal (the
                                    // system stole the gesture), not a user
                                    // lift — the same convention
                                    // waitForUpOrCancellation uses. A canceled
                                    // long-press must not pop the actions
                                    // menu.
                                    if (!dragging && !change.isConsumed) {
                                        menuExpanded = true
                                    }
                                    change.consume()
                                    break
                                }
                                val delta = change.positionChange()
                                totalDelta += delta
                                if (!dragging && totalDelta.getDistance() > slopPx) {
                                    dragging = true
                                    latestOnDragStart()
                                    // Carry the full pre-slop displacement into
                                    // the first dispatch so the icon snaps to
                                    // where the finger actually is, not back to
                                    // its slot centre.
                                    latestOnDrag(totalDelta)
                                } else if (dragging) {
                                    latestOnDrag(delta)
                                }
                                change.consume()
                            }
                        } finally {
                            // Runs on every exit: clean release, the tracked
                            // pointer vanishing from the stream, and
                            // cancellation of the gesture coroutine (activity
                            // pause mid-drag, node detach). Ending the drag
                            // only on the clean-release path left DockCard's
                            // draggedAppId/dragOffset latched, so the icon
                            // kept rendering lifted at its last offset until
                            // some new drag overwrote the state.
                            if (dragging) {
                                latestOnDragEnd()
                            }
                            latestOnLongPressArmed(false)
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
            onRenameApp = onRenameApp,
            onSetAppIconOverride = onSetAppIconOverride,
            onClearAppIconOverride = onClearAppIconOverride,
            onSetAppBadge = onSetAppBadge,
            onHideApp = onHideApp,
        )
    }
}

@Composable
private fun EmptyDockSlot(
    dockIconSizeDp: Int,
    dockLayout: DockLayout,
    modifier: Modifier = Modifier,
    onReportSlotCenter: (Offset) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .height(dockSlotHeightDp(dockIconSizeDp, dockLayout, fontScale).dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                onReportSlotCenter(
                    Offset(
                        pos.x + coords.size.width / 2f,
                        pos.y + coords.size.height / 2f,
                    ),
                )
            },
    )
}

@Composable
private fun DockAddButton(
    dockIconSizeDp: Int,
    dockLayout: DockLayout,
    modifier: Modifier = Modifier,
    addButtonTag: String = DOCK_ADD_BUTTON_TAG,
    onReportSlotCenter: ((Offset) -> Unit)? = null,
) {
    val context = LocalContext.current
    val hint = stringResource(R.string.dock_add_button_hint)
    val description = stringResource(R.string.dock_add_button_description)
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = modifier
            .height(dockSlotHeightDp(dockIconSizeDp, dockLayout, fontScale).dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                onReportSlotCenter?.invoke(
                    Offset(
                        pos.x + coords.size.width / 2f,
                        pos.y + coords.size.height / 2f,
                    ),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .testTag(addButtonTag)
                .size((dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP).dp),
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
}

/**
 * "Show work dock" settings row. Visible whenever a managed profile is
 * present on the device (`isWorkProfileConfigured`). The switch is only
 * interactable when the profile is currently unpaused — tapping or
 * long-pressing the row while the profile is in quiet mode shows a transient
 * "Work profile is off" toast instead of toggling, so the user can see the
 * option exists but understands why it can't change right now.
 */
@Composable
private fun WorkDockSettingsRow(
    isWorkDockEnabled: Boolean,
    isWorkProfileActive: Boolean,
    onWorkDockEnabledChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val disabledHint = stringResource(R.string.settings_work_dock_disabled_toast)
    val showDisabledHint = {
        Toast.makeText(context, disabledHint, Toast.LENGTH_SHORT).show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = !isWorkProfileActive,
                onClick = { showDisabledHint() },
                onLongClick = { showDisabledHint() },
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.settings_work_dock_enabled_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Switch(
            checked = isWorkDockEnabled,
            onCheckedChange = onWorkDockEnabledChanged,
            enabled = isWorkProfileActive,
            modifier = Modifier.testTag(WORK_DOCK_ENABLED_SWITCH_TAG),
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
    onAppListLayoutChanged: (AppListLayout) -> Unit,
    onDockLayoutChanged: (DockLayout) -> Unit = {},
    onShowDockedAppsInListChanged: (Boolean) -> Unit = {},
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onWorkDockEnabledChanged: (Boolean) -> Unit = {},
    onFoldersEnabledChanged: (Boolean) -> Unit = {},
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onKeyboardAutoShownChanged: (Boolean) -> Unit = {},
    onAgendaEnabledChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onIconShapeChanged: (IconShape) -> Unit = {},
    onIconThemeChanged: (IconTheme) -> Unit = {},
    onUnhideApp: (InstalledApp) -> Unit,
    onOpenLauncherAppInfo: () -> Unit,
    onOpenPlayUpdate: () -> Unit,
    onCompletePlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    // Derive the slider's range, value, and the preview's icon size from the
    // short screen edge and the persisted target icon size, so Settings shows
    // exactly what Home draws. `dockIconCount` is the *rendered* per-row count
    // (`dockIconSizing(...).slotCount`) — re-derived here rather than stored, so
    // the slider self-heals to the real number that fits this device / Display
    // size on every open.
    val liveReferenceWidthDp = minOf(configuration.screenWidthDp, configuration.screenHeightDp)
    val slotCountRange = dockSlotCountRange(liveReferenceWidthDp)
    val dockSizing = dockIconSizing(liveReferenceWidthDp, state.dockIconSizeDp)
    val dockIconCount = dockSizing.slotCount
    val dockIconSizeDp = dockSizing.iconSizeDp
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
            onCompletePlayUpdate = onCompletePlayUpdate,
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
                    selected = state.appListLayout,
                    onLayoutChanged = onAppListLayoutChanged,
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
                        stringResource(R.string.settings_dock_layout_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                DockLayoutDropdown(
                    selected = state.dockLayout,
                    onLayoutChanged = onDockLayoutChanged,
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
                        stringResource(R.string.settings_show_docked_apps_in_list_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Switch(
                    checked = state.isShowDockedAppsInList,
                    onCheckedChange = onShowDockedAppsInListChanged,
                    // The flag controls the main-list dedup for both docks,
                    // so keep the switch interactable whenever either dock
                    // is actually rendered — otherwise a user with the
                    // personal dock off and the work dock on would have no
                    // way to make work-docked apps searchable again.
                    enabled = state.isDockEnabled ||
                        (state.isWorkDockEnabled && state.isWorkProfileActive),
                    modifier = Modifier.testTag(SHOW_DOCKED_APPS_IN_LIST_SWITCH_TAG),
                )
            }
            if (state.isWorkProfileConfigured) {
                WorkDockSettingsRow(
                    isWorkDockEnabled = state.isWorkDockEnabled,
                    isWorkProfileActive = state.isWorkProfileActive,
                    onWorkDockEnabledChanged = onWorkDockEnabledChanged,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_dock_folders_enabled_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(R.string.settings_dock_folders_enabled_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.isDockFoldersEnabled,
                    onCheckedChange = onFoldersEnabledChanged,
                    enabled = state.isDockEnabled ||
                        (state.isWorkDockEnabled && state.isWorkProfileActive),
                    modifier = Modifier.testTag(DOCK_FOLDERS_ENABLED_SWITCH_TAG),
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_icon_shape_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconShapeDropdown(
                    selected = state.iconShape,
                    onIconShapeChanged = onIconShapeChanged,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_icon_theme_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                IconThemeDropdown(
                    selected = state.iconTheme,
                    onIconThemeChanged = onIconThemeChanged,
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
            dockIconCount = dockIconCount,
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
    onCompletePlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
) {
    val update = playUpdate as? PlayUpdateState.Available
    if (update?.shouldPrompt == true) {
        PlayUpdateBanner(
            progress = update.progress,
            onOpenPlayUpdate = onOpenPlayUpdate,
            onCompletePlayUpdate = onCompletePlayUpdate,
            onDismissPlayUpdate = onDismissPlayUpdate,
        )
    } else if (buildSourceInfo != null) {
        LocalBuildBanner(buildSourceInfo = buildSourceInfo)
    }
}

@Composable
private fun PlayUpdateBanner(
    progress: UpdateProgress,
    onOpenPlayUpdate: () -> Unit,
    onCompletePlayUpdate: () -> Unit,
    onDismissPlayUpdate: () -> Unit,
) {
    val isInFlight = progress is UpdateProgress.Starting || progress is UpdateProgress.Downloading
    val isDownloaded = progress is UpdateProgress.Downloaded
    val cardOnClick: () -> Unit = when {
        isDownloaded -> onCompletePlayUpdate
        isInFlight -> ({})
        else -> onOpenPlayUpdate
    }
    val titleRes = when (progress) {
        UpdateProgress.Starting, UpdateProgress.Downloading -> R.string.play_update_banner_updating_title
        UpdateProgress.Downloaded -> R.string.play_update_banner_downloaded_title
        UpdateProgress.Idle -> R.string.play_update_banner_title
    }
    val bodyRes = when (progress) {
        UpdateProgress.Starting, UpdateProgress.Downloading -> R.string.play_update_banner_updating_body
        UpdateProgress.Downloaded -> R.string.play_update_banner_downloaded_body
        UpdateProgress.Idle -> R.string.play_update_banner_body
    }
    SectionCard(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(PLAY_UPDATE_BANNER_TAG)
            .semantics { role = Role.Button }
            .clickable(enabled = !isInFlight, onClick = cardOnClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            when {
                isInFlight -> {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag(PLAY_UPDATE_BANNER_PROGRESS_TAG),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                isDownloaded -> {
                    TextButton(
                        onClick = onCompletePlayUpdate,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.testTag(PLAY_UPDATE_BANNER_RESTART_TAG),
                    ) {
                        Text(stringResource(R.string.play_update_banner_restart_button))
                    }
                }
                else -> {
                    TextButton(
                        onClick = onOpenPlayUpdate,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        modifier = Modifier.testTag(PLAY_UPDATE_BANNER_UPDATE_TAG),
                    ) {
                        Text(stringResource(R.string.play_update_banner_update_button))
                    }
                }
            }
            if (!isInFlight) {
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
    selected: AppListLayout,
    onLayoutChanged: (AppListLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = when (selected) {
        AppListLayout.NameBeside -> R.string.settings_app_list_layout_option_name_beside
        AppListLayout.NameBelow -> R.string.settings_app_list_layout_option_name_below
        AppListLayout.IconOnly -> R.string.settings_app_list_layout_option_icon_only
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
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(APP_LIST_LAYOUT_DROPDOWN_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_layout_option_name_beside)) },
                modifier = Modifier.testTag(APP_LIST_LAYOUT_OPTION_NAME_BESIDE_TAG),
                onClick = {
                    expanded = false
                    onLayoutChanged(AppListLayout.NameBeside)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_layout_option_name_below)) },
                modifier = Modifier.testTag(APP_LIST_LAYOUT_OPTION_NAME_BELOW_TAG),
                onClick = {
                    expanded = false
                    onLayoutChanged(AppListLayout.NameBelow)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_layout_option_icon_only)) },
                modifier = Modifier.testTag(APP_LIST_LAYOUT_OPTION_ICON_ONLY_TAG),
                onClick = {
                    expanded = false
                    onLayoutChanged(AppListLayout.IconOnly)
                },
            )
        }
    }
}

@Composable
private fun DockLayoutDropdown(
    selected: DockLayout,
    onLayoutChanged: (DockLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabelRes = when (selected) {
        DockLayout.IconOnly -> R.string.settings_app_list_layout_option_icon_only
        DockLayout.TitleBelow -> R.string.settings_app_list_layout_option_name_below
    }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(DOCK_LAYOUT_DROPDOWN_TAG),
        ) {
            Text(stringResource(selectedLabelRes))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(DOCK_LAYOUT_DROPDOWN_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_layout_option_icon_only)) },
                modifier = Modifier.testTag(DOCK_LAYOUT_OPTION_ICON_ONLY_TAG),
                onClick = {
                    expanded = false
                    onLayoutChanged(DockLayout.IconOnly)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_layout_option_name_below)) },
                modifier = Modifier.testTag(DOCK_LAYOUT_OPTION_TITLE_BELOW_TAG),
                onClick = {
                    expanded = false
                    onLayoutChanged(DockLayout.TitleBelow)
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
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(APP_LIST_SORT_DROPDOWN_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_sort_option_usage)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_USAGE_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.Usage)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_sort_option_usage_reversed)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_USAGE_REVERSED_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.UsageReversed)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_sort_option_name)) },
                modifier = Modifier.testTag(APP_LIST_SORT_OPTION_NAME_TAG),
                onClick = {
                    expanded = false
                    onSortOrderChanged(AppListSortOrder.Alphabetical)
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_list_sort_option_name_reversed)) },
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
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(THEME_MODE_DROPDOWN_MENU_TAG),
        ) {
            ThemeMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { LauncherMenuItemText(stringResource(mode.labelRes())) },
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
private fun IconShapeDropdown(
    selected: IconShape,
    onIconShapeChanged: (IconShape) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(ICON_SHAPE_DROPDOWN_TAG),
        ) {
            Text(stringResource(selected.labelRes()))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(ICON_SHAPE_DROPDOWN_MENU_TAG),
        ) {
            IconShape.entries.forEach { shape ->
                DropdownMenuItem(
                    text = { LauncherMenuItemText(stringResource(shape.labelRes())) },
                    modifier = Modifier.testTag(shape.optionTag()),
                    onClick = {
                        expanded = false
                        onIconShapeChanged(shape)
                    },
                )
            }
        }
    }
}

private fun IconShape.labelRes(): Int =
    when (this) {
        IconShape.System -> R.string.settings_icon_shape_option_system
        IconShape.Circle -> R.string.settings_icon_shape_option_circle
        IconShape.Squircle -> R.string.settings_icon_shape_option_squircle
    }

private fun IconShape.optionTag(): String =
    when (this) {
        IconShape.System -> ICON_SHAPE_OPTION_SYSTEM_TAG
        IconShape.Circle -> ICON_SHAPE_OPTION_CIRCLE_TAG
        IconShape.Squircle -> ICON_SHAPE_OPTION_SQUIRCLE_TAG
    }

@Composable
private fun IconThemeDropdown(
    selected: IconTheme,
    onIconThemeChanged: (IconTheme) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier.testTag(ICON_THEME_DROPDOWN_TAG),
        ) {
            Text(stringResource(selected.labelRes()))
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
            )
        }
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(ICON_THEME_DROPDOWN_MENU_TAG),
        ) {
            IconTheme.entries.forEach { theme ->
                DropdownMenuItem(
                    text = { LauncherMenuItemText(stringResource(theme.labelRes())) },
                    modifier = Modifier.testTag(theme.optionTag()),
                    onClick = {
                        expanded = false
                        onIconThemeChanged(theme)
                    },
                )
            }
        }
    }
}

private fun IconTheme.labelRes(): Int =
    when (this) {
        IconTheme.Default -> R.string.settings_icon_theme_option_default
        IconTheme.Monochrome -> R.string.settings_icon_theme_option_monochrome
    }

private fun IconTheme.optionTag(): String =
    when (this) {
        IconTheme.Default -> ICON_THEME_OPTION_DEFAULT_TAG
        IconTheme.Monochrome -> ICON_THEME_OPTION_MONOCHROME_TAG
    }

@Composable
private fun SettingsOverflowMenu(onOpenLauncherAppInfo: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var aboutVisible by remember { mutableStateOf(false) }
    var consentVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dockSettings = remember(context) { DockSettingsStore(context) }
    fun startBugReport() {
        val activity = context.findActivity() ?: return
        scope.launch { BugReport.share(activity) }
    }
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
        LauncherDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.testTag(SETTINGS_OVERFLOW_MENU_TAG),
        ) {
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_about_action)) },
                modifier = Modifier.testTag(SETTINGS_ABOUT_ACTION_TAG),
                onClick = {
                    expanded = false
                    aboutVisible = true
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_app_info_action)) },
                modifier = Modifier.testTag(SETTINGS_APP_INFO_ACTION_TAG),
                onClick = {
                    expanded = false
                    onOpenLauncherAppInfo()
                },
            )
            DropdownMenuItem(
                text = { LauncherMenuItemText(stringResource(R.string.settings_report_bug_action)) },
                modifier = Modifier.testTag(SETTINGS_REPORT_BUG_ACTION_TAG),
                onClick = {
                    expanded = false
                    if (dockSettings.isBugReportConsentSuppressed) {
                        startBugReport()
                    } else {
                        consentVisible = true
                    }
                },
            )
        }
    }
    if (aboutVisible) {
        AboutDialog(onDismiss = { aboutVisible = false })
    }
    if (consentVisible) {
        BugReportConsentDialog(
            onDismiss = { consentVisible = false },
            onConfirm = { suppressFuture ->
                consentVisible = false
                if (suppressFuture) {
                    dockSettings.isBugReportConsentSuppressed = true
                }
                startBugReport()
            },
        )
    }
}

@Composable
internal fun BugReportConsentDialog(
    onDismiss: () -> Unit,
    onConfirm: (suppressFuture: Boolean) -> Unit,
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(BUG_REPORT_CONSENT_DIALOG_TAG),
        title = { Text(stringResource(R.string.bug_report_consent_dialog_title)) },
        text = {
            Column {
                Text(stringResource(R.string.bug_report_consent_dialog_body))
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dontShowAgain = !dontShowAgain }
                        .testTag(BUG_REPORT_CONSENT_DONT_SHOW_AGAIN_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it },
                        modifier = Modifier.testTag(BUG_REPORT_CONSENT_DONT_SHOW_AGAIN_CHECKBOX_TAG),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bug_report_consent_dialog_dont_show_again))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(dontShowAgain) },
                modifier = Modifier.testTag(BUG_REPORT_CONSENT_CONFIRM_TAG),
            ) {
                Text(stringResource(R.string.bug_report_consent_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag(BUG_REPORT_CONSENT_CANCEL_TAG),
            ) {
                Text(stringResource(R.string.bug_report_consent_dialog_cancel))
            }
        },
    )
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
    dockIconCount: Int,
) {
    val previewHeight = (dockIconSizeDp + SETTINGS_PREVIEW_CARD_CHROME_DP).dp
    // The dock preview tracks the live `state.dockLayout`: `TitleBelow` adds a
    // `labelSmall` strip beneath each icon, so a fixed `previewHeight` would
    // clip the title in the preview the moment the user picks the option from
    // the dropdown sitting just above. `SETTINGS_PREVIEW_CARD_CHROME_DP` is
    // exactly `DOCK_ITEM_VERTICAL_PADDING_DP + SECTION_CARD_PADDING_DP * 2`,
    // so adding the slot delta on top keeps the chrome math consistent.
    val dockPreviewHeight = previewHeight +
        (dockSlotHeightDp(dockIconSizeDp, state.dockLayout, LocalDensity.current.fontScale) -
            (dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP)).dp
    // Total preview footprint is fixed at SETTINGS_PREVIEW_BAR_COUNT bars so the
    // user can see the space reserved for secondary bars and the dock.
    val totalPreviewHeight =
        previewHeight * SETTINGS_PREVIEW_BAR_COUNT +
            SETTINGS_PREVIEW_SPACING_DP.dp * (SETTINGS_PREVIEW_BAR_COUNT - 1)
    val dockPreviewBudget = if (state.isDockEnabled) {
        dockPreviewHeight + SETTINGS_PREVIEW_SPACING_DP.dp
    } else {
        0.dp
    }
    val appListHeight = totalPreviewHeight -
        (previewHeight + SETTINGS_PREVIEW_SPACING_DP.dp) -
        dockPreviewBudget
    // The preview is visual-only on every interaction surface:
    //   - clearAndSetSemantics on the wrapper strips the cards' descendant
    //     semantics from the MERGED tree, so TalkBack, Switch Access,
    //     keyboard / D-pad navigation, and tests calling performClick()
    //     against the merged tree can't reach the descendants' click /
    //     long-press / combinedClickable actions at all. This also blocks
    //     long-press menus and the Edit-app dialog from being armed via
    //     accessibility, which the no-op-callback approach alone could
    //     not — the long-press handlers in AppsCard / DockedAppButton
    //     set local state (menuExpanded, editDialogVisible) before any
    //     callback fires, so blocking the callback isn't enough.
    //   - The descendants still exist in the UNMERGED tree, which is what
    //     the existing settings-preview screenshot tests rely on — they
    //     query APPS_CARD_TAG, DOCK_CARD_TAG, DOCK_RECENTS_CARD_TAG,
    //     DOCK_APP_ICON_TAG with useUnmergedTree = true. Future preview
    //     tests must follow that same convention.
    //   - Every card callback is also wired to a no-op as defense in
    //     depth: a test that opts into the unmerged tree and calls
    //     performClick() still hits a no-op rather than the live
    //     launcher state.
    //   - The transparent overlay sibling below claims the touch hit path
    //     on top of the cards so their inner LazyColumns / clickables /
    //     long-press handlers never see pointer events at all. The overlay
    //     never consumes, which lets the settings page's outer
    //     verticalScroll still drive vertical drags that start inside the
    //     preview region.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {},
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SETTINGS_PREVIEW_SPACING_DP.dp),
        ) {
            AppsCard(
                apps = state.filteredApps,
                dockLimit = Int.MAX_VALUE,
                layout = state.appListLayout,
                iconSizeDp = dockIconSizeDp,
                highlightFirst = state.query.isNotBlank(),
                reverseLayout = state.appListSortOrder.isReversed,
                scrollResetKey = state.query,
                modifier = Modifier.height(appListHeight),
                onLaunchApp = {},
                onOpenAppInfo = {},
                onToggleDock = { _, _ -> },
                onResetRank = {},
                onRenameApp = { _, _ -> },
                onSetAppIconOverride = {},
                onClearAppIconOverride = {},
                onSetAppBadge = { _, _ -> },
                onHideApp = {},
            )
            if (state.isDockEnabled) {
                DockCard(
                    dockedApps = state.dockedApps,
                    dockPositions = state.dockPositions,
                    dockFolders = state.dockFolders,
                    foldersEnabled = state.isDockFoldersEnabled,
                    dockIconSizeDp = dockIconSizeDp,
                    dockIconCount = dockIconCount,
                    dockLayout = state.dockLayout,
                    modifier = Modifier.height(dockPreviewHeight),
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onReorderDock = { _, _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onSetAppIconOverride = {},
                    onClearAppIconOverride = {},
                    onSetAppBadge = { _, _ -> },
                    onHideApp = {},
                )
            }
            // Mirror Home: recents is always a secondary bar, independent of the dock.
            RecentsCard(
                recentApps = state.recentApps,
                isVisible = true,
                dockIconSizeDp = dockIconSizeDp,
                modifier = Modifier.height(previewHeight),
                onLaunchApp = {},
                onOpenAppInfo = {},
                onToggleDock = { _, _ -> },
                onDismissRecent = {},
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent()
                        } while (event.changes.any { it.pressed })
                    }
                },
        )
    }
}

// Internal (not private) so the corner-badge font-scale regression test can
// compose the icon directly under a fontScale-overridden Density.
@Composable
internal fun AppIcon(
    app: InstalledApp,
    size: androidx.compose.ui.unit.Dp,
    testTag: String = APP_ICON_TAG,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val bitmap = rememberAppIconBitmap(app, size)
    val shape = LocalAppIconShape.current.toComposeShape()
    Box(
        modifier = Modifier
            .size(size)
            .testTag("$testTag:${app.displayName}"),
    ) {
        // Only the Surface clips to the icon shape, not the parent Box —
        // otherwise the corner badge (aligned BottomStart, below) would be
        // clipped by the shape and the flag/globe glyph cut off at the corner.
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
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
        // The work-profile badge (briefcase) rides on top of the icon but
        // OUTSIDE the shape clip, so the system-placed corner badge is never
        // sliced off where the icon corner falls beyond a round/squircle clip.
        // Drawn as its own full-size overlay rather than baked into the icon
        // bitmap for exactly that reason.
        rememberWorkBadgeOverlay(app, size)?.let { workBadge ->
            Image(
                bitmap = workBadge,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("$APP_ICON_WORK_BADGE_TAG:${app.displayName}"),
                contentScale = ContentScale.Fit,
            )
        }
        appCornerBadge(app)?.let { badge ->
            val badgeDp = (size.value * APP_ICON_CORNER_BADGE_FRACTION).dp
            // Convert through the density so the glyph stays locked to the
            // dp-sized badge box. A bare `.sp` of the same number scales
            // with the user's font-scale setting, so at accessibility font
            // sizes the flag/emoji rendered up to 2x the unclipped box and
            // spilled across the app icon.
            val flagSp = with(LocalDensity.current) { (badgeDp - 2.dp).toSp() }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(badgeDp)
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

private data class DisambiguatorBadge(
    val glyph: String,
    val contentDescription: String,
)

// Picks the corner-badge glyph to render for [app]: a user-chosen
// [InstalledApp.customBadge] always wins (so a flag the user picked from the
// badge picker overrides whatever the auto-disambiguator computed), and the
// fallback runs the existing `effectiveDisambiguator` -> `disambiguatorBadge`
// pipeline so the auto-detected country / regional badges still render for
// apps the user hasn't customised. Composable because the custom-badge
// branch resolves a localized accessibility label per device locale.
@Composable
private fun appCornerBadge(app: InstalledApp): DisambiguatorBadge? {
    app.customBadge?.takeIf { it.isNotEmpty() }?.let { glyph ->
        return DisambiguatorBadge(glyph, customBadgeContentDescription(glyph))
    }
    return app.effectiveDisambiguator?.takeIf { it.isNotEmpty() }?.let(::disambiguatorBadge)
}

// Builds a TalkBack-friendly content description for a user-chosen custom
// badge so the screen reader announces "Home"/"Work"/"World"/"United
// States" alongside the app's display name, instead of the generic word
// "badge". The auto-disambiguator path (effectiveDisambiguator ->
// disambiguatorBadge) keeps its existing "flag"/"globe" text — auto-
// detection's output is intentionally coarse (no country lookup at that
// stage), and changing it would also rewrite the existing disambiguator
// screenshot test's expectations.
@Composable
private fun customBadgeContentDescription(glyph: String): String {
    BUILT_IN_BADGE_OPTIONS.firstOrNull { it.glyph == glyph }?.labelRes?.let { res ->
        return stringResource(res)
    }
    if (glyph == WORLD_BADGE_OPTION.glyph) {
        WORLD_BADGE_OPTION.labelRes?.let { res -> return stringResource(res) }
    }
    decodeRegionalIndicatorPair(glyph)?.let { code ->
        val locale = LocalConfiguration.current.locales[0]
        val countryName = java.util.Locale.Builder().setRegion(code).build().getDisplayCountry(locale)
        if (countryName.isNotEmpty()) return countryName
    }
    // Fallback for an unrecognised glyph. The curated picker shipping
    // today never reaches this branch; it exists so a future free-form
    // entry path or a stale persisted value doesn't render an empty
    // accessibility label.
    return stringResource(R.string.edit_app_dialog_badge_label)
}

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

// The highlight palette must follow the launcher's selected theme, not the
// device night mode: the Settings `Theme` override is applied purely by
// TypeLauncherTheme's color-scheme choice (Configuration.uiMode is never
// touched), so `isSystemInDarkTheme()` would paint the light-palette
// highlight into a forced-dark app list and vice versa. Deriving dark-ness
// from the active scheme's background tracks whatever theme is actually
// rendered. Internal (not private) so the theme-mismatch regression test can
// read the resolved colors.
@Composable
internal fun selectionHighlightColor(): Color =
    if (isDarkColorScheme()) Color(0xFF274C7A) else Color(0xFFCFE2FF)

@Composable
internal fun selectionHighlightOnColor(): Color =
    if (isDarkColorScheme()) Color(0xFFE6EEFA) else Color(0xFF0B2A5B)

@Composable
private fun isDarkColorScheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

private const val SETTINGS_PREVIEW_CARD_CHROME_DP = 40
private const val SETTINGS_PREVIEW_BAR_COUNT = 3
private const val SETTINGS_PREVIEW_SPACING_DP = 16

// Play update badge dot — a "presence" dot (no count or number), matching
// Android's standard notification dot, scaled down for the smaller
// search-field gear icon.
private const val PLAY_UPDATE_BADGE_SIZE_DP = 8
