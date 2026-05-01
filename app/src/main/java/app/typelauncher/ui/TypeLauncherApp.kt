package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val CAROUSEL_SWIPE_THRESHOLD_DP = 48
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
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionDrivenUi()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    TypeLauncherApp(
        state = state,
        onQueryChanged = viewModel::setQuery,
        onClearQuery = { viewModel.setQuery("") },
        onLaunchActiveApp = viewModel::launchActiveApp,
        onLaunchApp = viewModel::launchApp,
        onOpenAppInfo = viewModel::openAppInfo,
        onToggleDock = viewModel::toggleDock,
        onResetRank = viewModel::resetRank,
        onOpenSettings = viewModel::openSettings,
        onCloseSettings = viewModel::closeSettings,
        onDockEnabledChanged = viewModel::setDockEnabled,
        onDockIconSizeChanged = viewModel::setDockIconSizeDp,
        onShowAgenda = viewModel::showAgenda,
        onShowWidgets = viewModel::showWidgets,
        onShowHome = viewModel::showHome,
        appWidgetHost = appWidgetHost,
        appWidgetManager = appWidgetManager,
        onAddWidget = onAddWidget,
        onDismissWidgetPicker = onDismissWidgetPicker,
        onSelectWidget = onSelectWidget,
        onRemoveWidget = onRemoveWidget,
        onRequestCalendarPermission = onRequestCalendarPermission,
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
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onDockEnabledChanged: (Boolean) -> Unit,
    onDockIconSizeChanged: (Int) -> Unit,
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onAddWidget: () -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRequestCalendarPermission: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars).union(WindowInsets.ime),
    ) { innerPadding ->
        if (state.isSettingsOpen) {
            SettingsScreen(
                state = state,
                innerPadding = innerPadding,
                onCloseSettings = onCloseSettings,
                onDockEnabledChanged = onDockEnabledChanged,
                onDockIconSizeChanged = onDockIconSizeChanged,
                onLaunchApp = onLaunchApp,
                onOpenAppInfo = onOpenAppInfo,
                onToggleDock = onToggleDock,
                onResetRank = onResetRank,
            )
        } else {
            SwipeNavigationBox(
                screen = state.screen,
                onShowAgenda = onShowAgenda,
                onShowWidgets = onShowWidgets,
                onShowHome = onShowHome,
            ) { pageScreen ->
                when (pageScreen) {
                    LauncherScreen.Home -> HomeScreen(
                        state = state,
                        innerPadding = innerPadding,
                        onQueryChanged = onQueryChanged,
                        onClearQuery = onClearQuery,
                        onLaunchActiveApp = onLaunchActiveApp,
                        onLaunchApp = onLaunchApp,
                        onOpenAppInfo = onOpenAppInfo,
                        onToggleDock = onToggleDock,
                        onResetRank = onResetRank,
                        onOpenSettings = onOpenSettings,
                    )
                    LauncherScreen.Widgets -> WidgetsScreen(
                        widgetIds = state.widgetIds,
                        availableWidgets = state.availableWidgets,
                        isAddingWidget = state.isAddingWidget,
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
                    )
                }
            }
        }
    }
}

@Composable
private fun SwipeNavigationBox(
    screen: LauncherScreen,
    onShowAgenda: () -> Unit,
    onShowWidgets: () -> Unit,
    onShowHome: () -> Unit,
    content: @Composable (LauncherScreen) -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = LauncherScreen.initialCarouselPage(screen),
        pageCount = { LauncherScreen.carouselPageCount },
    )
    val settledPage = pagerState.settledPage
    val swipeThresholdPx = with(LocalDensity.current) { CAROUSEL_SWIPE_THRESHOLD_DP.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(screen) {
        val targetPage = LauncherScreen.closestCarouselPage(
            currentPage = pagerState.currentPage,
            screen = screen,
        )
        if (pagerState.currentPage != targetPage) {
            pagerState.animateScrollToPage(targetPage)
        }
    }
    LaunchedEffect(settledPage) {
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
            .pointerInput(pagerState, swipeThresholdPx) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val dragStartPage = pagerState.settledPage
                    var dragAmountPx = 0f
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { change ->
                            dragAmountPx += change.positionChange().x
                            if (abs(dragAmountPx) >= swipeThresholdPx) {
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (abs(dragAmountPx) >= swipeThresholdPx) {
                        val direction = if (dragAmountPx < 0f) 1 else -1
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(dragStartPage + direction)
                        }
                    }
                }
            },
    ) { page ->
        content(LauncherScreen.fromCarouselPage(page))
    }
}
