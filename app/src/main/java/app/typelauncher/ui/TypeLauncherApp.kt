package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Drag must clear this many pixels of horizontal movement before the carousel
// claims the gesture from child handlers and starts rubber-banding the page.
private const val CAROUSEL_TOUCH_SLOP_DP = 8

// Fraction of page width the user must drag (raw, before rubber-band damping)
// to commit a screen change on release. Combined with the rubber-band easing,
// this is a meaningful tug — much harder to trigger accidentally than the old
// flat 48dp threshold.
private const val CAROUSEL_COMMIT_DISTANCE_RATIO = 0.4f

// Release velocity (in dp/s) above which a fling commits even if the raw drag
// distance is below the commit ratio. Lets a quick flick still advance a page.
private const val CAROUSEL_FLING_COMMIT_VELOCITY_DP_PER_SEC = 800f

// If at release the velocity is in the opposite direction of the net drag and
// faster than this, treat the gesture as cancelled — the user pulled and then
// pulled back, so they don't want to commit.
private const val CAROUSEL_BACKWARD_VELOCITY_CANCEL_DP_PER_SEC = 200f

// Drag distance the user must travel vertically before either pull gesture
// commits — same value for both directions so a pull-up and a pull-down feel
// equally deliberate. Pull-down on Home opens the notification bar (or, when
// the bar is already up, the system shade); pull-up on Home opens the recents
// bar. The threshold matches the carousel's horizontal commit so all three
// directions share the same "this was a real intentional drag" budget.
private const val VERTICAL_PULL_THRESHOLD_DP = 96

// Once the app list has loaded, wait this long for the soft keyboard to come
// up before signalling "home ready" anyway. Hardware keyboards, IME-disabled
// test environments, and slow IME starts can all keep WindowInsets.isImeVisible
// false indefinitely; we don't want to defer the agenda load forever in those
// cases.
private const val HOME_READY_IME_TIMEOUT_MS = 1500L

private var SemanticsPropertyReceiver.carouselVirtualPage by CarouselVirtualPageKey

@Composable
internal fun TypeLauncherApp(
    viewModel: LauncherViewModel,
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestDefaultLauncher: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        LauncherDebugLog.event("TypeLauncherApp state ${state.debugSummary()}")
    }
    // ON_RESUME refresh is handled by MainActivity.onResume; we don't add a Compose
    // observer for the same event because it would refresh permission-driven UI twice
    // per resume.

    TypeLauncherApp(
        state = state,
        onQueryChanged = viewModel::setQuery,
        onClearQuery = { viewModel.setQuery("") },
        onLaunchActiveApp = viewModel::launchActiveApp,
        onLaunchApp = viewModel::launchApp,
        onOpenAppInfo = viewModel::openAppInfo,
        onToggleDock = viewModel::toggleDock,
        onResetRank = viewModel::resetRank,
        onHideApp = viewModel::hideApp,
        onUnhideApp = viewModel::unhideApp,
        onOpenSettings = viewModel::openSettings,
        onCloseSettings = viewModel::closeSettings,
        onOpenLauncherAppInfo = viewModel::openLauncherAppInfo,
        onRequestDefaultLauncher = onRequestDefaultLauncher,
        onDockEnabledChanged = viewModel::setDockEnabled,
        onAppListIconOnlyChanged = viewModel::setAppListIconOnly,
        onDockVisibleIconCountChanged = viewModel::setDockVisibleIconCount,
        onAppListSortOrderChanged = viewModel::setAppListSortOrder,
        onRecentsAlwaysShownChanged = viewModel::setRecentsAlwaysShown,
        onNotificationsEnabledChanged = viewModel::setNotificationsEnabled,
        onShowAgenda = viewModel::showAgenda,
        onShowWidgets = viewModel::showWidgets,
        onShowHome = viewModel::showHome,
        onHomeReady = viewModel::onHomeReady,
        onSetRecentsOpen = viewModel::setRecentsOpen,
        onSetNotificationBarOpen = viewModel::setNotificationBarOpen,
        onRequestNotificationAccess = viewModel::openNotificationAccessSettings,
        appWidgetHost = appWidgetHost,
        appWidgetManager = appWidgetManager,
        onAddWidget = onAddWidget,
        onDismissWidgetPicker = onDismissWidgetPicker,
        onSelectWidget = onSelectWidget,
        onRemoveWidget = onRemoveWidget,
        onRequestCalendarPermission = onRequestCalendarPermission,
        onOpenAgendaEvent = viewModel::openAgendaEvent,
        onSwipeDown = onSwipeDown,
    )
}

@Composable
internal fun TypeLauncherApp(
    state: LauncherUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onUnhideApp: (InstalledApp) -> Unit,
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenLauncherAppInfo: () -> Unit = {},
    onRequestDefaultLauncher: () -> Unit,
    onDockEnabledChanged: (Boolean) -> Unit,
    onAppListIconOnlyChanged: (Boolean) -> Unit,
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onRecentsAlwaysShownChanged: (Boolean) -> Unit = {},
    onNotificationsEnabledChanged: (Boolean) -> Unit = {},
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    onHomeReady: () -> Unit = {},
    onSetRecentsOpen: (Boolean) -> Unit = {},
    onSetNotificationBarOpen: (Boolean) -> Unit = {},
    onRequestNotificationAccess: () -> Unit = {},
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
    onSwipeDown: () -> Unit = {},
) {
    LaunchedEffect(state.screen, state.isSettingsOpen, state.isAppListIconOnly) {
        LauncherDebugLog.event("TypeLauncherApp render target=${if (state.isSettingsOpen) "Settings" else state.screen}")
    }
    HomeReadySignal(
        // Gate on the fresh load, not the spinner: on a warm start with cached
        // metadata, `isLoadingApps` is `false` while `installed_apps_load` is
        // still running on IO. Firing here would race the fresh app load —
        // exactly what this signal exists to prevent.
        appsReady = state.isFreshAppLoadComplete,
        onHomeReady = onHomeReady,
    )
    // Cold-start one-frame holdback for the home body (apps grid, notification
    // bar, dock, recents). Hoisted here so it survives HomeScreen unmount /
    // remount cycles (Settings open/close, returning from carousel screens):
    // the holdback is a cold-start optimisation, not something we want to
    // re-trigger on routine navigation. TypeLauncherApp itself only unmounts
    // on configuration change, where re-deferring is the right call anyway.
    var homeBodyReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        homeBodyReady = true
    }
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime),
    ) { innerPadding ->
        if (state.isSettingsOpen) {
            SettingsScreen(
                state = state,
                innerPadding = innerPadding,
                onCloseSettings = onCloseSettings,
                onRequestDefaultLauncher = onRequestDefaultLauncher,
                onDockEnabledChanged = onDockEnabledChanged,
                onAppListIconOnlyChanged = onAppListIconOnlyChanged,
                onDockVisibleIconCountChanged = onDockVisibleIconCountChanged,
                onAppListSortOrderChanged = onAppListSortOrderChanged,
                onRecentsAlwaysShownChanged = onRecentsAlwaysShownChanged,
                onNotificationsEnabledChanged = onNotificationsEnabledChanged,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
                onUnhideApp = onUnhideApp,
                onOpenLauncherAppInfo = onOpenLauncherAppInfo,
            )
        } else {
            SwipeNavigationBox(
                screen = state.screen,
                isNotificationBarOpen = state.isNotificationBarOpen,
                isNotificationsEnabled = state.isNotificationsEnabled,
                isRecentsOpen = state.isRecentsOpen,
                onShowAgenda = onShowAgenda,
                onShowWidgets = onShowWidgets,
                onShowHome = onShowHome,
                onSetNotificationBarOpen = onSetNotificationBarOpen,
                onSetRecentsOpen = onSetRecentsOpen,
                onSwipeDown = onSwipeDown,
            ) { pageScreen ->
                when (pageScreen) {
                    LauncherScreen.Home -> HomeScreen(
                        state = state,
                        innerPadding = innerPadding,
                        bodyReady = homeBodyReady,
                        onQueryChanged = onQueryChanged,
                        onClearQuery = onClearQuery,
                        onLaunchActiveApp = onLaunchActiveApp,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onHideApp = onHideApp,
                        onOpenSettings = onOpenSettings,
                        onSetNotificationBarOpen = onSetNotificationBarOpen,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                    )
                    LauncherScreen.Widgets -> WidgetsScreen(
                        widgetIds = state.widgetIds,
                        availableWidgets = state.availableWidgets,
                        isAddingWidget = state.isAddingWidget,
                        isLoadingAvailableWidgets = state.isLoadingAvailableWidgets,
                        appWidgetHost = appWidgetHost,
                        appWidgetManager = appWidgetManager,
                        innerPadding = innerPadding,
                        onAddWidget = onAddWidget,
                        onDismissWidgetPicker = onDismissWidgetPicker,
                        onSelectWidget = onSelectWidget,
                        onRemoveWidget = onRemoveWidget,
                    )
                    LauncherScreen.Agenda -> AgendaScreen(
                        agenda = state.agenda,
                        innerPadding = innerPadding,
                        onRequestCalendarPermission = onRequestCalendarPermission,
                        onOpenAgendaEvent = onOpenAgendaEvent,
                    )
                }
            }
        }
    }
}

/**
 * Fires `onHomeReady` exactly once, after the fresh `LauncherApps` query has
 * returned (`appsReady`) and either the soft keyboard is visible or
 * [HOME_READY_IME_TIMEOUT_MS] has elapsed since the apps loaded. The downstream
 * signal kicks off the deferred initial agenda load so it doesn't contend with
 * the cold-start app list IO or the keyboard show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeReadySignal(
    appsReady: Boolean,
    onHomeReady: () -> Unit,
) {
    val imeVisible = WindowInsets.isImeVisible
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(appsReady, imeVisible, fired) {
        if (fired || !appsReady) return@LaunchedEffect
        if (!imeVisible) {
            // Wait for the IME — but don't wait forever (hardware keyboards,
            // Robolectric, IME-disabled tests).
            delay(HOME_READY_IME_TIMEOUT_MS)
        }
        fired = true
        onHomeReady()
    }
}

@Composable
private fun SwipeNavigationBox(
    screen: LauncherScreen,
    isNotificationBarOpen: Boolean,
    isNotificationsEnabled: Boolean,
    isRecentsOpen: Boolean,
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    onSetNotificationBarOpen: (Boolean) -> Unit,
    onSetRecentsOpen: (Boolean) -> Unit,
    onSwipeDown: () -> Unit,
    content: @Composable (LauncherScreen) -> Unit,
) {
    // Both pull gestures dispatch from this single carousel-level handler so
    // they're triggerable from anywhere on Home that doesn't have a more
    // specific consumer (margins, dock surface, recents/notification bars,
    // and — at their top/bottom edges — the apps list itself). Down opens the
    // notification bar then the system shade; up opens the recents bar. With
    // the "Show notifications" setting off, the bar stage is skipped — the
    // first pull-down expands the system shade directly, matching how the
    // Widgets and Agenda screens behave.
    // We capture the latest values via rememberUpdatedState so the dispatch
    // lambdas keep stable identities and don't re-key the pointerInput
    // mid-gesture.
    val currentScreen by rememberUpdatedState(screen)
    val currentBarOpen by rememberUpdatedState(isNotificationBarOpen)
    val currentNotificationsEnabled by rememberUpdatedState(isNotificationsEnabled)
    val currentRecentsOpen by rememberUpdatedState(isRecentsOpen)
    val currentSetBarOpen by rememberUpdatedState(onSetNotificationBarOpen)
    val currentSetRecentsOpen by rememberUpdatedState(onSetRecentsOpen)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val swipeDownDispatch = remember<() -> Unit> {
        {
            if (currentScreen == LauncherScreen.Home &&
                currentNotificationsEnabled &&
                !currentBarOpen
            ) {
                currentSetBarOpen(true)
            } else {
                currentOnSwipeDown()
            }
        }
    }
    val swipeUpDispatch = remember<() -> Unit> {
        {
            // Pull-up only does anything on Home — the recents bar lives on
            // Home, and there's no second-stage hand-off (the system has no
            // pull-up gesture we'd want to defer to).
            if (currentScreen == LauncherScreen.Home && !currentRecentsOpen) {
                currentSetRecentsOpen(true)
            }
        }
    }
    val pagerState = rememberPagerState(
        initialPage = LauncherScreen.initialCarouselPage(screen),
        pageCount = { LauncherScreen.carouselPageCount },
    )
    val settledPage = pagerState.settledPage
    val density = LocalDensity.current
    val touchSlopPx = with(density) { CAROUSEL_TOUCH_SLOP_DP.dp.toPx() }
    val flingCommitVelocityPxPerSec = with(density) {
        CAROUSEL_FLING_COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
    }
    val backwardVelocityCancelPxPerSec = with(density) {
        CAROUSEL_BACKWARD_VELOCITY_CANCEL_DP_PER_SEC.dp.toPx()
    }
    val verticalPullThresholdPx = with(density) {
        VERTICAL_PULL_THRESHOLD_DP.dp.toPx()
    }
    val coroutineScope = rememberCoroutineScope()
    // Hold off on composing carousel pages other than the visible one until the
    // first frame has rendered. The visible page is what triggers the soft
    // keyboard via Home's focusRequester, and any extra layout work on the same
    // frame (e.g. the agenda's calendar query) delays that show by hundreds of
    // ms on cold start.
    var offscreenPagesReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        offscreenPagesReady = true
    }

    LaunchedEffect(screen) {
        val targetPage = LauncherScreen.closestCarouselPage(
            currentPage = pagerState.currentPage,
            screen = screen,
        )
        LauncherDebugLog.event(
            "SwipeNavigationBox screen=$screen currentPage=${pagerState.currentPage} targetPage=$targetPage",
        )
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
    LaunchedEffect(settledPage) {
        LauncherDebugLog.event(
            "SwipeNavigationBox settledPage=$settledPage screen=${LauncherScreen.fromCarouselPage(settledPage)}",
        )
        when (LauncherScreen.fromCarouselPage(settledPage)) {
            LauncherScreen.Agenda -> if (screen != LauncherScreen.Agenda) onShowAgenda()
            LauncherScreen.Widgets -> if (screen != LauncherScreen.Widgets) onShowWidgets()
            LauncherScreen.Home -> if (screen != LauncherScreen.Home) onShowHome()
        }
    }

    HorizontalPager(
        state = pagerState,
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxSize()
            .testTag(CAROUSEL_TAG)
            .semantics {
                carouselVirtualPage = pagerState.settledPage
            }
            .pointerInput(
                pagerState,
                touchSlopPx,
                flingCommitVelocityPxPerSec,
                backwardVelocityCancelPxPerSec,
            ) {
                awaitEachGesture {
                    val downChange = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val pageWidthPx = size.width.toFloat().coerceAtLeast(1f)
                    val commitDistancePx = pageWidthPx * CAROUSEL_COMMIT_DISTANCE_RATIO
                    val dragStartPage = pagerState.currentPage
                    var rawDragX = 0f
                    var rawDragY = 0f
                    var displayDeltaPx = 0f
                    var claimed = false
                    val velocityTracker = VelocityTracker()
                    // Seed the tracker with the DOWN sample so short flicks get
                    // a representative velocity at release — without this, the
                    // tracker only sees later MOVE samples and can read 0 px/s
                    // for gestures that finish in a single frame.
                    velocityTracker.addPosition(downChange.uptimeMillis, downChange.position)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { change ->
                            val delta = change.positionChange()
                            rawDragX += delta.x
                            rawDragY += delta.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (!claimed &&
                                abs(rawDragX) > touchSlopPx &&
                                abs(rawDragX) > abs(rawDragY)
                            ) {
                                claimed = true
                            }
                            if (claimed) {
                                val newDisplay = rubberBand(rawDragX, pageWidthPx)
                                    .coerceIn(-pageWidthPx, pageWidthPx)
                                val moveBy = newDisplay - displayDeltaPx
                                displayDeltaPx = newDisplay
                                if (moveBy != 0f) {
                                    // dispatchRawDelta uses scroll-axis sign, which is
                                    // opposite of finger drag (drag finger left → next
                                    // page → positive scroll delta), so flip the sign.
                                    pagerState.dispatchRawDelta(-moveBy)
                                }
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!claimed) {
                        return@awaitEachGesture
                    }

                    val releaseVelocity = velocityTracker.calculateVelocity().x
                    val dragDirection = when {
                        rawDragX < 0f -> 1
                        rawDragX > 0f -> -1
                        else -> 0
                    }
                    // Finger and pager move in opposite directions: dragging finger
                    // left (rawDragX < 0) advances the pager forward, so a forward
                    // intent corresponds to releaseVelocity also being negative.
                    // "Backwards at release" → velocity sign opposite of rawDragX sign.
                    val velocityOpposesDrag = dragDirection != 0 &&
                        abs(releaseVelocity) >= backwardVelocityCancelPxPerSec &&
                        sign(releaseVelocity) == -sign(rawDragX)
                    val distanceCommits = abs(rawDragX) >= commitDistancePx
                    val flingCommits = dragDirection != 0 &&
                        abs(releaseVelocity) >= flingCommitVelocityPxPerSec &&
                        sign(releaseVelocity) == sign(rawDragX)
                    val committed = dragDirection != 0 &&
                        !velocityOpposesDrag &&
                        (distanceCommits || flingCommits)

                    LauncherDebugLog.event(
                        "SwipeNavigationBox release rawDragX=$rawDragX velocityX=$releaseVelocity " +
                            "distanceCommits=$distanceCommits flingCommits=$flingCommits " +
                            "velocityOpposes=$velocityOpposesDrag committed=$committed",
                    )

                    val targetPage = if (committed) dragStartPage + dragDirection else dragStartPage
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(targetPage)
                    }
                }
            }
            .pointerInput(verticalPullThresholdPx, swipeDownDispatch, swipeUpDispatch) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    var verticalDragPx = 0f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            verticalDragPx += change.positionChange().y
                        }
                    } while (event.changes.any { it.pressed })

                    when {
                        verticalDragPx >= verticalPullThresholdPx -> {
                            LauncherDebugLog.event(
                                "SwipeNavigationBox swipe down verticalDragPx=$verticalDragPx",
                            )
                            swipeDownDispatch()
                        }
                        verticalDragPx <= -verticalPullThresholdPx -> {
                            LauncherDebugLog.event(
                                "SwipeNavigationBox swipe up verticalDragPx=$verticalDragPx",
                            )
                            swipeUpDispatch()
                        }
                    }
                }
            },
    ) { page ->
        val pageScreen = LauncherScreen.fromCarouselPage(page)
        if (pageScreen == screen || offscreenPagesReady) {
            content(pageScreen)
        } else {
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Maps a raw drag distance to a damped display offset that asymptotes at the
 * page width. The returned magnitude is always less than `pageWidth`, so a
 * single drag can never push the pager past one page boundary even if the user
 * keeps dragging — they have to release and start a new gesture.
 *
 * Shape: at |x| = pageWidth, output ≈ 0.5 · pageWidth; at |x| = 2·pageWidth,
 * output ≈ 0.67 · pageWidth; output → pageWidth as |x| → ∞.
 */
internal fun rubberBand(rawDragPx: Float, pageWidthPx: Float): Float {
    if (pageWidthPx <= 0f) return 0f
    return rawDragPx * pageWidthPx / (pageWidthPx + abs(rawDragPx))
}
