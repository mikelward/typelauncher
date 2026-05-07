package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Drag must clear this many pixels before the launcher decides whether a child
// scrollable or a launcher-level gesture owns the pointer sequence.
private const val CAROUSEL_TOUCH_SLOP_DP = 8

// Launcher-level swipes must travel this far before committing. Horizontal
// carousel swipes and vertical pull-up/down gestures share the same threshold so
// they feel equally deliberate and cannot chain more than one action.
private const val LAUNCHER_SWIPE_COMMIT_DISTANCE_DP = 96

// Release velocity (in dp/s) above which a fling commits even if the raw drag
// distance is below the commit distance. Lets a quick flick still advance a page.
private const val CAROUSEL_FLING_COMMIT_VELOCITY_DP_PER_SEC = 800f

// If at release the velocity is in the opposite direction of the net drag and
// faster than this, treat the gesture as cancelled — the user pulled and then
// pulled back, so they don't want to commit.
private const val CAROUSEL_BACKWARD_VELOCITY_CANCEL_DP_PER_SEC = 200f

// AwaitingAck should be effectively instantaneous; this timeout is a bug-report
// breadcrumb and a fail-safe so a bad future callback path cannot permanently
// deadlock launcher swipes.
private const val CAROUSEL_ACK_TIMEOUT_MS = 1500L

// Once the app list has loaded, wait this long for the soft keyboard to come
// up before signalling "home ready" anyway, when keyboard auto-show is enabled.
// Hardware keyboards, IME-disabled test environments, and slow IME starts can
// all keep WindowInsets.isImeVisible false indefinitely; we don't want to defer
// the agenda load forever in those cases.
private const val HOME_READY_IME_TIMEOUT_MS = 1500L

private val CarouselPageAnimationSpec = tween<Float>(
    durationMillis = 220,
    easing = FastOutSlowInEasing,
)

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
    onStartPlayUpdate: () -> Unit = {},
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(
        state.screen,
        state.isSettingsOpen,
        state.isLoadingApps,
        state.isFreshAppLoadComplete,
        state.filteredApps.size,
        state.dockedApps.size,
    ) {
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
        onReorderDock = viewModel::reorderDockedApps,
        onResetRank = viewModel::resetRank,
        onHideApp = viewModel::hideApp,
        onUnhideApp = viewModel::unhideApp,
        onDismissRecent = viewModel::removeRecent,
        onDismissNotifications = viewModel::dismissNotificationsFor,
        onOpenNotificationSettings = viewModel::openNotificationSettingsFor,
        onOpenSettings = viewModel::openSettings,
        onCloseSettings = viewModel::closeSettings,
        onOpenLauncherAppInfo = viewModel::openLauncherAppInfo,
        onOpenPlayUpdate = onStartPlayUpdate,
        onDismissPlayUpdate = viewModel::dismissPlayUpdate,
        onRequestDefaultLauncher = onRequestDefaultLauncher,
        onDockEnabledChanged = viewModel::setDockEnabled,
        onAppListIconOnlyChanged = viewModel::setAppListIconOnly,
        onDockVisibleIconCountChanged = viewModel::setDockVisibleIconCount,
        onAppListSortOrderChanged = viewModel::setAppListSortOrder,
        onRecentsAlwaysShownChanged = viewModel::setRecentsAlwaysShown,
        onHideRecentsFromAppListChanged = viewModel::setHideRecentsFromAppList,
        onNotificationPullDownBehaviorChanged = viewModel::setNotificationPullDownBehavior,
        onKeyboardAutoShownChanged = viewModel::setKeyboardAutoShown,
        onThemeModeChanged = viewModel::setThemeMode,
        onShowAgenda = viewModel::showAgenda,
        onShowWidgets = viewModel::showWidgets,
        onShowHome = viewModel::showHome,
        onHomeReady = viewModel::onHomeReady,
        onSetRecentsOpen = viewModel::setRecentsOpen,
        onSetNotificationBarOpen = viewModel::setNotificationBarOpen,
        onRequestShowKeyboard = viewModel::requestShowKeyboard,
        keyboardShowRequests = viewModel.keyboardShowRequests,
        onRequestNotificationAccess = viewModel::openNotificationAccessSettings,
        appWidgetHost = appWidgetHost,
        appWidgetManager = appWidgetManager,
        onAddWidget = onAddWidget,
        onDismissWidgetPicker = onDismissWidgetPicker,
        onSelectWidget = onSelectWidget,
        onRemoveWidget = onRemoveWidget,
        onResizeWidget = viewModel::resizeWidget,
        onRequestCalendarPermission = onRequestCalendarPermission,
        onOpenAgendaEvent = viewModel::openAgendaEvent,
        onSwipeDown = onSwipeDown,
        searchPlaceholderSuffix = searchPlaceholderSuffix,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TypeLauncherApp(
    state: LauncherUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onLaunchActiveApp: () -> Unit,
    onLaunchApp: (InstalledApp) -> Unit,
    onOpenAppInfo: (InstalledApp) -> Unit,
    onToggleDock: (InstalledApp, Int) -> Unit,
    onReorderDock: (Int, Int) -> Unit = { _, _ -> },
    onResetRank: (InstalledApp) -> Unit,
    onHideApp: (InstalledApp) -> Unit,
    onUnhideApp: (InstalledApp) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit = {},
    onDismissNotifications: (InstalledApp) -> Unit = {},
    onOpenNotificationSettings: (InstalledApp) -> Unit = {},
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenLauncherAppInfo: () -> Unit = {},
    onOpenPlayUpdate: () -> Unit = {},
    onDismissPlayUpdate: () -> Unit = {},
    onRequestDefaultLauncher: () -> Unit,
    onDockEnabledChanged: (Boolean) -> Unit,
    onAppListIconOnlyChanged: (Boolean) -> Unit,
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onRecentsAlwaysShownChanged: (Boolean) -> Unit = {},
    onHideRecentsFromAppListChanged: (Boolean) -> Unit = {},
    onNotificationPullDownBehaviorChanged: (NotificationPullDownBehavior) -> Unit = {},
    onKeyboardAutoShownChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    onHomeReady: () -> Unit = {},
    onSetRecentsOpen: (Boolean) -> Unit = {},
    onSetNotificationBarOpen: (Boolean) -> Unit = {},
    onRequestShowKeyboard: () -> Unit = {},
    keyboardShowRequests: SharedFlow<Unit> = MutableSharedFlow(),
    onRequestNotificationAccess: () -> Unit = {},
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onResizeWidget: (widgetId: Int, heightDp: Int) -> Unit = { _, _ -> },
    onRequestCalendarPermission: () -> Unit,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
    onSwipeDown: () -> Unit = {},
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
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
        waitForIme = state.isKeyboardAutoShown,
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
                onHideRecentsFromAppListChanged = onHideRecentsFromAppListChanged,
                onNotificationPullDownBehaviorChanged = onNotificationPullDownBehaviorChanged,
                onKeyboardAutoShownChanged = onKeyboardAutoShownChanged,
                onThemeModeChanged = onThemeModeChanged,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
                onHideApp = onHideApp,
                onUnhideApp = onUnhideApp,
                onOpenLauncherAppInfo = onOpenLauncherAppInfo,
                onOpenPlayUpdate = onOpenPlayUpdate,
                onDismissPlayUpdate = onDismissPlayUpdate,
            )
        } else {
            var homeAppListBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
            SwipeNavigationBox(
                screen = state.screen,
                isNotificationBarOpen = state.isNotificationBarOpen,
                notificationPullDownBehavior = state.notificationPullDownBehavior,
                // Pull-up's stage gating cares about whether the user is
                // already *looking* at recents, regardless of whether that's
                // because of the gesture-toggled `isRecentsOpen` or the
                // persistent `isRecentsAlwaysShown` setting (whose visibility
                // predicate is the same OR — see `LauncherUiState`).
                isRecentsVisible = state.isRecentsAlwaysShown || state.isRecentsOpen,
                onShowAgenda = onShowAgenda,
                onShowWidgets = onShowWidgets,
                onShowHome = onShowHome,
                onSetNotificationBarOpen = onSetNotificationBarOpen,
                onSetRecentsOpen = onSetRecentsOpen,
                onRequestShowKeyboard = onRequestShowKeyboard,
                onSwipeDown = onSwipeDown,
                appListBoundsInRoot = homeAppListBoundsInRoot,
            ) { pageScreen ->
                when (pageScreen) {
                    LauncherScreen.Home -> HomeScreen(
                        state = state,
                        innerPadding = innerPadding,
                        bodyReady = homeBodyReady,
                        searchPlaceholderSuffix = searchPlaceholderSuffix,
                        keyboardShowRequests = keyboardShowRequests,
                        onQueryChanged = onQueryChanged,
                        onClearQuery = onClearQuery,
                        onLaunchActiveApp = onLaunchActiveApp,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onReorderDock = onReorderDock,
                        onResetRank = onResetRank,
                        onHideApp = onHideApp,
                        onDismissRecent = onDismissRecent,
                        onDismissNotifications = onDismissNotifications,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onOpenSettings = onOpenSettings,
                        onSetNotificationBarOpen = onSetNotificationBarOpen,
                        onRequestNotificationAccess = onRequestNotificationAccess,
                        onAppListBoundsChanged = { homeAppListBoundsInRoot = it },
                    )
                    LauncherScreen.Widgets -> WidgetsScreen(
                        widgetIds = state.widgetIds,
                        availableWidgets = state.availableWidgets,
                        isAddingWidget = state.isAddingWidget,
                        isLoadingAvailableWidgets = state.isLoadingAvailableWidgets,
                        appWidgetHost = appWidgetHost,
                        appWidgetManager = appWidgetManager,
                        innerPadding = innerPadding,
                        widgetHeights = state.widgetHeights,
                        onAddWidget = onAddWidget,
                        onDismissWidgetPicker = onDismissWidgetPicker,
                        onSelectWidget = onSelectWidget,
                        onRemoveWidget = onRemoveWidget,
                        onResizeWidget = onResizeWidget,
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
 * Fires `onHomeReady` exactly once after the fresh `LauncherApps` query has
 * returned (`appsReady`). When Home is configured to auto-show the keyboard,
 * this also waits until the soft keyboard is visible or
 * [HOME_READY_IME_TIMEOUT_MS] has elapsed since the apps loaded. The downstream
 * signal kicks off the deferred initial agenda load so it doesn't contend with
 * the cold-start app list IO or an expected keyboard show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeReadySignal(
    appsReady: Boolean,
    waitForIme: Boolean,
    onHomeReady: () -> Unit,
) {
    val imeVisible = WindowInsets.isImeVisible
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(appsReady, imeVisible, waitForIme, fired) {
        if (fired || !appsReady) return@LaunchedEffect
        if (waitForIme && !imeVisible) {
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
    notificationPullDownBehavior: NotificationPullDownBehavior,
    isRecentsVisible: Boolean,
    appListBoundsInRoot: Rect?,
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    onSetNotificationBarOpen: (Boolean) -> Unit,
    onSetRecentsOpen: (Boolean) -> Unit,
    onRequestShowKeyboard: () -> Unit,
    onSwipeDown: () -> Unit,
    content: @Composable (LauncherScreen) -> Unit,
) {
    // A pointer sequence locks once, shortly after touch slop, to either the
    // child scrollable that consumed movement at gesture start or to a
    // launcher-level action. Reaching a child edge mid-gesture does not hand
    // the same drag to the carousel/pull handlers; the next gesture can claim
    // from that already-at-edge state.
    val currentScreen by rememberUpdatedState(screen)
    val currentBarOpen by rememberUpdatedState(isNotificationBarOpen)
    val currentNotificationPullDownBehavior by rememberUpdatedState(notificationPullDownBehavior)
    val currentRecentsVisible by rememberUpdatedState(isRecentsVisible)
    val currentSetBarOpen by rememberUpdatedState(onSetNotificationBarOpen)
    val currentSetRecentsOpen by rememberUpdatedState(onSetRecentsOpen)
    val currentRequestShowKeyboard by rememberUpdatedState(onRequestShowKeyboard)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentAppListBoundsInRoot by rememberUpdatedState(appListBoundsInRoot)
    val keyboard = LocalSoftwareKeyboardController.current
    val currentKeyboard by rememberUpdatedState(keyboard)
    val focusManager = LocalFocusManager.current
    val currentFocusManager by rememberUpdatedState(focusManager)
    val swipeDownDispatch = remember<() -> Unit> {
        {
            if (currentScreen == LauncherScreen.Home) {
                when (currentNotificationPullDownBehavior) {
                    NotificationPullDownBehavior.None -> Unit
                    NotificationPullDownBehavior.System -> currentOnSwipeDown()
                    NotificationPullDownBehavior.BarBelow,
                    NotificationPullDownBehavior.BarAbove,
                    -> {
                        if (currentBarOpen) {
                            currentOnSwipeDown()
                        } else {
                            currentKeyboard?.hide()
                            currentSetBarOpen(true)
                        }
                    }
                }
            } else {
                currentOnSwipeDown()
            }
        }
    }
    val swipeUpDispatch = remember<() -> Unit> {
        {
            // Pull-up only does anything on Home. If the notification bar is
            // visible, close it before treating the pull as anything else.
            // Otherwise the gesture chains: first stage opens the recents bar
            // (mirrors the launcher-bar first stage of pull-down), and once
            // recents is open a further pull-up asks the search field to grab
            // focus and re-show the soft keyboard. This is the "I dismissed
            // the IME and now want it back without tapping the search box"
            // affordance.
            if (currentScreen == LauncherScreen.Home) {
                if (currentBarOpen) {
                    currentSetBarOpen(false)
                } else if (!currentRecentsVisible) {
                    currentSetRecentsOpen(true)
                } else {
                    currentRequestShowKeyboard()
                }
            }
        }
    }
    var currentPage by remember { mutableStateOf(LauncherScreen.initialCarouselPage(screen)) }
    var carouselOffsetPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val touchSlopPx = with(density) { CAROUSEL_TOUCH_SLOP_DP.dp.toPx() }
    val flingCommitVelocityPxPerSec = with(density) {
        CAROUSEL_FLING_COMMIT_VELOCITY_DP_PER_SEC.dp.toPx()
    }
    val backwardVelocityCancelPxPerSec = with(density) {
        CAROUSEL_BACKWARD_VELOCITY_CANCEL_DP_PER_SEC.dp.toPx()
    }
    val launcherSwipeCommitDistancePx = with(density) {
        LAUNCHER_SWIPE_COMMIT_DISTANCE_DP.dp.toPx()
    }
    val scrollConsumptionTracker = remember { ScrollConsumptionTracker() }
    val coroutineScope = rememberCoroutineScope()
    var carouselAnimationJob by remember { mutableStateOf<Job?>(null) }
    var carouselTransition by remember { mutableStateOf<CarouselTransitionState>(CarouselTransitionState.Idle) }
    var allowSwipeWithUnackedScreen by remember { mutableStateOf(false) }
    fun dispatchSettledScreen(settledScreen: LauncherScreen) {
        when (settledScreen) {
            LauncherScreen.Agenda -> onShowAgenda()
            LauncherScreen.Widgets -> onShowWidgets()
            LauncherScreen.Home -> onShowHome()
        }
    }
    fun hideKeyboardForCarouselPage(targetScreen: LauncherScreen) {
        if (targetScreen != LauncherScreen.Home) {
            currentFocusManager.clearFocus(force = true)
            currentKeyboard?.hide()
        }
    }
    fun awaitScreenAck(targetPage: Int, targetScreen: LauncherScreen) {
        allowSwipeWithUnackedScreen = false
        carouselTransition = CarouselTransitionState.AwaitingAck(
            settledPage = targetPage,
            expectedScreen = targetScreen,
        )
        if (currentScreen != targetScreen) {
            dispatchSettledScreen(targetScreen)
        } else {
            carouselTransition = CarouselTransitionState.Idle
        }
    }
    suspend fun animateCarouselOffsetTo(targetOffsetPx: Float) {
        val animation = Animatable(carouselOffsetPx)
        animation.animateTo(
            targetValue = targetOffsetPx,
            animationSpec = CarouselPageAnimationSpec,
        ) {
            carouselOffsetPx = value
        }
        carouselOffsetPx = targetOffsetPx
    }
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

    LaunchedEffect(carouselTransition) {
        val transition = carouselTransition as? CarouselTransitionState.AwaitingAck ?: return@LaunchedEffect
        delay(CAROUSEL_ACK_TIMEOUT_MS)
        if (carouselTransition == transition) {
            LauncherDebugLog.warning(
                "SwipeNavigationBox ack timeout settled=${transition.settledPage} expected=${transition.expectedScreen} " +
                    "screen=$currentScreen",
            )
            allowSwipeWithUnackedScreen = true
            dispatchSettledScreen(transition.expectedScreen)
            carouselTransition = CarouselTransitionState.Idle
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag(CAROUSEL_TAG)
            .semantics {
                carouselVirtualPage = currentPage
            }
            .nestedScroll(scrollConsumptionTracker.connection)
            .pointerInput(
                scrollConsumptionTracker,
                touchSlopPx,
                launcherSwipeCommitDistancePx,
                flingCommitVelocityPxPerSec,
                backwardVelocityCancelPxPerSec,
            ) {
                awaitEachGesture {
                    val downChange = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val startConsumed = scrollConsumptionTracker.totalConsumed
                    val gestureStartPage = currentPage
                    val gestureStartScreen = LauncherScreen.fromCarouselPage(gestureStartPage)
                    val canStartCarouselGesture = carouselTransition == CarouselTransitionState.Idle &&
                        carouselAnimationJob?.isActive != true &&
                        carouselOffsetPx == 0f &&
                        (currentScreen == gestureStartScreen || allowSwipeWithUnackedScreen)
                    val pageWidthPx = size.width.toFloat().coerceAtLeast(1f)
                    var rawDragX = 0f
                    var rawDragY = 0f
                    var displayedDragX = 0f
                    var owner = LauncherGestureOwner.Undecided
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(downChange.uptimeMillis, downChange.position)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            val rawDelta = change.positionChangeIgnoreConsumed()
                            rawDragX += rawDelta.x
                            rawDragY += rawDelta.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (owner == LauncherGestureOwner.Undecided) {
                                val consumed = scrollConsumptionTracker.totalConsumed - startConsumed
                                owner = resolveLauncherGestureOwner(
                                    rawDragX = rawDragX,
                                    rawDragY = rawDragY,
                                    consumedDragX = consumed.x,
                                    consumedDragY = consumed.y,
                                    touchSlopPx = touchSlopPx,
                                )
                            }
                            if (owner == LauncherGestureOwner.HorizontalLauncher && canStartCarouselGesture) {
                                val nextDisplayedDragX = rawDragX.coerceIn(-pageWidthPx, pageWidthPx)
                                carouselOffsetPx = nextDisplayedDragX
                                displayedDragX = nextDisplayedDragX
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (owner != LauncherGestureOwner.HorizontalLauncher || !canStartCarouselGesture) {
                        return@awaitEachGesture
                    }

                    val releaseVelocity = velocityTracker.calculateVelocity().x
                    val dragDirection = when {
                        rawDragX < 0f -> 1
                        rawDragX > 0f -> -1
                        else -> 0
                    }
                    val velocityOpposesDrag = dragDirection != 0 &&
                        abs(releaseVelocity) >= backwardVelocityCancelPxPerSec &&
                        sign(releaseVelocity) == -sign(rawDragX)
                    val distanceCommits = abs(rawDragX) >= launcherSwipeCommitDistancePx
                    val flingCommits = dragDirection != 0 &&
                        abs(releaseVelocity) >= flingCommitVelocityPxPerSec &&
                        sign(releaseVelocity) == sign(rawDragX)
                    val committed = dragDirection != 0 &&
                        !velocityOpposesDrag &&
                        (distanceCommits || flingCommits)

                    LauncherDebugLog.event(
                        "SwipeNavigationBox horizontal release rawDragX=$rawDragX rawDragY=$rawDragY " +
                            "velocityX=$releaseVelocity " +
                            "distanceCommits=$distanceCommits flingCommits=$flingCommits " +
                            "velocityOpposes=$velocityOpposesDrag committed=$committed",
                    )
                    val targetPage = if (committed) {
                        (gestureStartPage + dragDirection)
                            .coerceIn(0, LauncherScreen.carouselPageCount - 1)
                    } else {
                        gestureStartPage
                    }
                    val targetScreen = LauncherScreen.fromCarouselPage(targetPage)
                    val willChangePage = committed && targetPage != gestureStartPage
                    if (willChangePage) {
                        carouselTransition = CarouselTransitionState.UserAnimating(targetPage, targetScreen)
                        hideKeyboardForCarouselPage(targetScreen)
                    }
                    carouselAnimationJob = coroutineScope.launch {
                        val targetOffsetPx = when {
                            targetPage > gestureStartPage -> -pageWidthPx
                            targetPage < gestureStartPage -> pageWidthPx
                            else -> 0f
                        }
                        animateCarouselOffsetTo(targetOffsetPx)
                        if (willChangePage &&
                            carouselTransition == CarouselTransitionState.UserAnimating(targetPage, targetScreen)
                        ) {
                            currentPage = targetPage
                            carouselOffsetPx = 0f
                            awaitScreenAck(targetPage, targetScreen)
                        } else {
                            carouselOffsetPx = 0f
                        }
                    }
                }
            }
            .pointerInput(
                scrollConsumptionTracker,
                touchSlopPx,
                launcherSwipeCommitDistancePx,
                flingCommitVelocityPxPerSec,
                backwardVelocityCancelPxPerSec,
                swipeDownDispatch,
                swipeUpDispatch,
            ) {
                awaitEachGesture {
                    val downChange = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    val startedInHomeAppList = currentScreen == LauncherScreen.Home &&
                        currentAppListBoundsInRoot?.contains(downChange.position) == true
                    val startConsumed = scrollConsumptionTracker.totalConsumed
                    var rawDragX = 0f
                    var rawDragY = 0f
                    var owner = LauncherGestureOwner.Undecided
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(downChange.uptimeMillis, downChange.position)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            val delta = change.positionChangeIgnoreConsumed()
                            rawDragX += delta.x
                            rawDragY += delta.y
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            if (owner == LauncherGestureOwner.Undecided) {
                                val consumed = scrollConsumptionTracker.totalConsumed - startConsumed
                                owner = resolveLauncherGestureOwner(
                                    rawDragX = rawDragX,
                                    rawDragY = rawDragY,
                                    consumedDragX = consumed.x,
                                    consumedDragY = consumed.y,
                                    touchSlopPx = touchSlopPx,
                                )
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (owner != LauncherGestureOwner.VerticalLauncher || startedInHomeAppList) {
                        return@awaitEachGesture
                    }

                    val releaseVelocity = velocityTracker.calculateVelocity().y
                    val velocityOpposesDrag = rawDragY != 0f &&
                        abs(releaseVelocity) >= backwardVelocityCancelPxPerSec &&
                        sign(releaseVelocity) == -sign(rawDragY)
                    val distanceCommits = abs(rawDragY) >= launcherSwipeCommitDistancePx
                    val flingCommits = rawDragY != 0f &&
                        abs(releaseVelocity) >= flingCommitVelocityPxPerSec &&
                        sign(releaseVelocity) == sign(rawDragY)
                    val committed = !velocityOpposesDrag && (distanceCommits || flingCommits)

                    when {
                        committed && rawDragY > 0f -> {
                            LauncherDebugLog.event(
                                "SwipeNavigationBox swipe down rawDragY=$rawDragY velocityY=$releaseVelocity",
                            )
                            swipeDownDispatch()
                        }
                        committed && rawDragY < 0f -> {
                            LauncherDebugLog.event(
                                "SwipeNavigationBox swipe up rawDragY=$rawDragY velocityY=$releaseVelocity",
                            )
                            swipeUpDispatch()
                        }
                    }
                }
            },
    ) {
        val pageWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        LaunchedEffect(screen, pageWidthPx) {
            when (val transition = carouselTransition) {
                is CarouselTransitionState.AwaitingAck -> {
                    if (screen == transition.expectedScreen) {
                        allowSwipeWithUnackedScreen = false
                        carouselTransition = CarouselTransitionState.Idle
                    }
                    return@LaunchedEffect
                }
                is CarouselTransitionState.UserAnimating,
                is CarouselTransitionState.ExternalAnimating,
                -> return@LaunchedEffect
                CarouselTransitionState.Idle -> Unit
            }
            if (carouselAnimationJob?.isActive == true || carouselOffsetPx != 0f) {
                return@LaunchedEffect
            }
            if (screen == LauncherScreen.fromCarouselPage(currentPage)) {
                allowSwipeWithUnackedScreen = false
            }
            val targetPage = LauncherScreen.closestCarouselPage(
                currentPage = currentPage,
                screen = screen,
            )
            LauncherDebugLog.event(
                "SwipeNavigationBox external screen=$screen settledPage=$currentPage targetPage=$targetPage",
            )
            if (targetPage != currentPage) {
                val startPage = currentPage
                allowSwipeWithUnackedScreen = false
                carouselTransition = CarouselTransitionState.ExternalAnimating(targetPage, screen)
                carouselAnimationJob = coroutineScope.launch {
                    val targetOffsetPx = if (targetPage > startPage) -pageWidthPx else pageWidthPx
                    animateCarouselOffsetTo(targetOffsetPx)
                    currentPage = targetPage
                    carouselOffsetPx = 0f
                    carouselTransition = CarouselTransitionState.Idle
                }
            } else {
                carouselTransition = CarouselTransitionState.Idle
            }
        }
        LaunchedEffect(currentPage) {
            LauncherDebugLog.event(
                "SwipeNavigationBox settledPage=$currentPage screen=${LauncherScreen.fromCarouselPage(currentPage)}",
            )
        }
        listOf(currentPage - 1, currentPage, currentPage + 1).forEach { page ->
            val pageScreen = LauncherScreen.fromCarouselPage(page)
            val translationX = (page - currentPage) * pageWidthPx + carouselOffsetPx
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { this.translationX = translationX },
            ) {
                if (page == currentPage || pageScreen == screen || offscreenPagesReady) {
                    content(pageScreen)
                }
            }
        }
    }
}

/**
 * Records child scroll consumed during a pointer gesture. Launcher gestures
 * claim only when the child has not consumed movement on the winning axis at
 * gesture start.
 */
private class ScrollConsumptionTracker {
    var totalConsumed: Offset = Offset.Zero
        private set

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            if (source == NestedScrollSource.UserInput) {
                totalConsumed += consumed
            }
            return Offset.Zero
        }
    }
}

private sealed interface CarouselTransitionState {
    data object Idle : CarouselTransitionState

    data class UserAnimating(
        val targetPage: Int,
        val targetScreen: LauncherScreen,
    ) : CarouselTransitionState

    data class ExternalAnimating(
        val targetPage: Int,
        val targetScreen: LauncherScreen,
    ) : CarouselTransitionState

    data class AwaitingAck(
        val settledPage: Int,
        val expectedScreen: LauncherScreen,
    ) : CarouselTransitionState
}

internal enum class LauncherGestureOwner {
    Undecided,
    ChildScrollable,
    HorizontalLauncher,
    VerticalLauncher,
}

internal fun resolveLauncherGestureOwner(
    rawDragX: Float,
    rawDragY: Float,
    consumedDragX: Float,
    consumedDragY: Float,
    touchSlopPx: Float,
): LauncherGestureOwner {
    val absX = abs(rawDragX)
    val absY = abs(rawDragY)
    // Child scrollables get a smaller consumption threshold than the launcher's
    // claim threshold: once a child has demonstrably started scrolling, keep
    // the whole gesture with that child instead of stealing it later.
    val childClaimSlop = touchSlopPx / 2f
    return when {
        absX <= touchSlopPx && absY <= touchSlopPx -> LauncherGestureOwner.Undecided
        absX > absY -> {
            if (abs(consumedDragX) > childClaimSlop) {
                LauncherGestureOwner.ChildScrollable
            } else {
                LauncherGestureOwner.HorizontalLauncher
            }
        }
        absY > absX -> {
            if (abs(consumedDragY) > childClaimSlop) {
                LauncherGestureOwner.ChildScrollable
            } else {
                LauncherGestureOwner.VerticalLauncher
            }
        }
        else -> LauncherGestureOwner.Undecided
    }
}
