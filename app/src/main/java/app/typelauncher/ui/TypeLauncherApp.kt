package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

// Drag must clear this many pixels before the launcher decides whether a child
// scrollable or a launcher-level gesture owns the pointer sequence.
private const val CAROUSEL_TOUCH_SLOP_DP = 8

// Vertical pull-up/down gestures must travel this far before committing, so they
// feel deliberate and cannot chain more than one action. Horizontal carousel
// swipes use a different bar — half the page width (pageWidthPx / 2) — because a
// page turn should track the page, not a fixed dp distance.
private const val LAUNCHER_SWIPE_COMMIT_DISTANCE_DP = 96

// Release velocity (in dp/s) above which a fling commits even if the raw drag
// distance is below the commit distance. Lets a quick flick still advance a page.
// 500 matches AOSP Launcher3's FLING_THRESHOLD_VELOCITY and sits between
// ViewPager2's 400 and the looser end of the platform fling-detection range.
private const val CAROUSEL_FLING_COMMIT_VELOCITY_DP_PER_SEC = 500f

// If at release the velocity is in the opposite direction of the net drag and
// faster than this, treat the gesture as cancelled — the user pulled and then
// pulled back, so they don't want to commit.
//
// TODO: re-evaluate this 200 dp/s threshold now that the fling-commit bar is
// 500 dp/s (Launcher3-aligned). The cancel rule is unique to us — Launcher3
// follows the most recent input direction instead — and at the new fling bar a
// fast-pull-then-twitch-back at 500+ dp/s reverse can block a page that the
// same gesture would have committed under the old 800 dp/s fling bar. If
// users report "I flicked but it stayed put," widening the cancel band (say
// 350 dp/s) or scaling it relative to the fling bar are both reasonable.
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

// Debounce window applied before persisting a grown keyboard height as the
// cached reservation. The persist path keys on the *visible* IME bottom inset
// (`WindowInsets.ime`), so a multi-stage IME open (e.g. the suggestion strip
// animating in then collapsing) that momentarily reports a taller height never
// reaches the persist call: the visible inset passes through the peak for only
// a frame or two before resting, and each new value re-keys the LaunchedEffect
// and cancels the pending delay. Only a height the keyboard holds still at for
// this long is treated as authoritative.
private const val IME_GROWTH_DEBOUNCE_MS = 250L

// Debounce window applied before allowing a smaller settled IME reading to
// shrink the entry's cached keyboard reservation. Within an entry the cache
// is grow-biased to prevent secondary-tray toggles and IME open animations
// from reflowing Home; the shrink path is gated on a separate, longer window
// so a transient inset dip during, say, an IME layout swap cannot pull the
// reservation down before the keyboard settles back. Tuned looser than the
// growth debounce because growth is far more disruptive than a one-off
// missed shrink.
private const val IME_SHRINK_DEBOUNCE_MS = 600L

// How long the keyboard-space pin outlives a search-time dock reveal: after
// the reveal ends the reservation stays applied until the restored keyboard
// starts animating back in (a seamless hand-off) or the query clears (a dock
// drop landed, so the blank-query layout takes over immediately) — otherwise
// the gesture's end would collapse the reservation for the few frames before
// the keyboard's insets report, reflowing the layout twice back to back. The
// timeout covers the no-IME case (hardware keyboard, IME disabled), where
// neither signal ever arrives.
private const val SEARCH_DOCK_REVEAL_RESERVATION_GRACE_MS = 500L

private val CarouselPageAnimationSpec = tween<Float>(
    durationMillis = 220,
    easing = FastOutSlowInEasing,
)

// MIME types offered to `ActivityResultContracts.OpenDocument` when the user
// chooses an icon override. Limited to the formats `AppIconLoader` can decode:
// raster (PNG / JPEG / WEBP) via `BitmapFactory` and SVG via AndroidSVG.
// `image/svg+xml` is enumerated explicitly because Android's media providers
// don't always include SVG when only `image/*` is requested.
private val ICON_PICKER_MIME_TYPES = arrayOf(
    "image/svg+xml",
    "image/png",
    "image/jpeg",
    "image/webp",
)

private var SemanticsPropertyReceiver.carouselVirtualPage by CarouselVirtualPageKey

@Composable
internal fun TypeLauncherApp(
    viewModel: LauncherViewModel,
    appWidgetHost: AppWidgetHost,
    appWidgetManager: AppWidgetManager,
    onAddWidget: (WidgetAddRequest) -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRestoreWidget: (Int) -> Unit,
    onRequestCalendarPermission: () -> Unit,
    onRequestDefaultLauncher: () -> Unit,
    onSwipeDown: () -> Unit,
    onStartPlayUpdate: () -> Unit = {},
    onCompletePlayUpdate: () -> Unit = {},
    // The Settings "Search contacts" / "Search calendar events" toggles route
    // through the host so enabling can request the runtime permission first;
    // the defaults skip that gate for hosts without a permission launcher
    // (previews, tests).
    onContactSearchEnabledChanged: (Boolean) -> Unit = viewModel::setContactSearchEnabled,
    onCalendarSearchEnabledChanged: (Boolean) -> Unit = viewModel::setCalendarSearchEnabled,
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(
        state.destination,
        state.isSettingsOpen,
        state.isLoadingApps,
        state.isFreshAppLoadComplete,
        state.filteredApps.size,
        state.dockedApps.size,
        state.isAgendaEnabled,
    ) {
        LauncherDebugLog.event("TypeLauncherApp state ${state.debugSummary()}")
    }
    // ON_RESUME refresh is handled by MainActivity.onResume; we don't add a Compose
    // observer for the same event because it would refresh permission-driven UI twice
    // per resume.

    val context = LocalContext.current
    // The system file picker runs out-of-process and the launcher activity
    // can be recreated — or, in extreme cases, reclaimed for a full process
    // death — while the picker is foreground (configuration change, system
    // memory pressure). `rememberSaveable` round-trips the pending app id
    // through the saved-instance bundle so when `OpenDocument` redelivers
    // the URI on the rebuilt `Activity`, we can still route it back to the
    // right `InstalledApp` instead of silently dropping the user's pick.
    // The id (a `String`) is saveable; resolving it against the live
    // installed-app list at delivery time also keeps a stale `InstalledApp`
    // instance from pinning a since-uninstalled package.
    var pendingIconPickAppId by rememberSaveable { mutableStateOf<String?>(null) }
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val targetId = pendingIconPickAppId
        pendingIconPickAppId = null
        if (uri != null && targetId != null) {
            viewModel.setAppIconOverride(targetId, uri)
        }
    }

    TypeLauncherApp(
        state = state,
        onQueryChanged = viewModel::setQuery,
        onClearQuery = { viewModel.setQuery("") },
        onLaunchActiveApp = viewModel::launchActiveApp,
        onLaunchApp = viewModel::launchApp,
        onOpenAppInfo = viewModel::openAppInfo,
        onToggleDock = viewModel::toggleDock,
        onToggleWorkDock = viewModel::toggleWorkDock,
        onReorderDock = viewModel::reorderDockedApps,
        onReorderWorkDock = viewModel::reorderWorkDockedApps,
        onMergeDock = viewModel::mergeDockItems,
        onMergeWorkDock = viewModel::mergeWorkDockItems,
        onRemoveFromDockFolder = viewModel::removeAppFromDockFolder,
        onRemoveFromWorkDockFolder = viewModel::removeAppFromWorkDockFolder,
        onUndockFromDockFolder = viewModel::undockAppFromDockFolder,
        onUndockFromWorkDockFolder = viewModel::undockAppFromWorkDockFolder,
        onReorderDockFolderMember = viewModel::reorderDockFolderMember,
        onMoveDockFolderMemberToDock = viewModel::moveDockFolderMemberToDock,
        onMergeDockFolderMemberInto = viewModel::mergeDockFolderMemberInto,
        onDockAppAtPosition = viewModel::dockAppAtPosition,
        onDockAppIntoOccupant = viewModel::dockAppIntoDockOccupant,
        onDockAppAtWorkDockPosition = viewModel::dockAppAtWorkDockPosition,
        onDockAppIntoWorkDockOccupant = viewModel::dockAppIntoWorkDockOccupant,
        onExplodeDockFolder = viewModel::explodeDockFolder,
        onResetRank = viewModel::resetRank,
        onRenameApp = viewModel::renameApp,
        onSetAppIconOverride = { app ->
            pendingIconPickAppId = app.id
            try {
                iconPickerLauncher.launch(ICON_PICKER_MIME_TYPES)
            } catch (_: ActivityNotFoundException) {
                pendingIconPickAppId = null
                Toast.makeText(
                    context,
                    R.string.edit_app_dialog_pick_icon_unavailable,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
        onClearAppIconOverride = viewModel::clearAppIconOverride,
        onSetAppBadge = viewModel::setAppBadge,
        onHideApp = viewModel::hideApp,
        onUnhideApp = viewModel::unhideApp,
        onDismissRecent = viewModel::removeRecent,
        onOpenSettings = viewModel::openSettings,
        onCloseSettings = viewModel::closeSettings,
        onOpenLauncherAppInfo = viewModel::openLauncherAppInfo,
        onOpenPlayUpdate = onStartPlayUpdate,
        onCompletePlayUpdate = onCompletePlayUpdate,
        onDismissPlayUpdate = viewModel::dismissPlayUpdate,
        onRequestDefaultLauncher = onRequestDefaultLauncher,
        onDockEnabledChanged = viewModel::setDockEnabled,
        onAppListLayoutChanged = viewModel::setAppListLayout,
        onDockLayoutChanged = viewModel::setDockLayout,
        onDockVisibleIconCountChanged = viewModel::setDockVisibleIconCount,
        onWorkDockEnabledChanged = viewModel::setWorkDockEnabled,
        onAppListSortOrderChanged = viewModel::setAppListSortOrder,
        onKeyboardAutoShownChanged = viewModel::setKeyboardAutoShown,
        onWallpaperShownChanged = viewModel::setWallpaperShown,
        onCardOpacityChanged = viewModel::setCardOpacity,
        onAgendaEnabledChanged = viewModel::setAgendaEnabled,
        onContactSearchEnabledChanged = onContactSearchEnabledChanged,
        onCalendarSearchEnabledChanged = onCalendarSearchEnabledChanged,
        onThemeModeChanged = viewModel::setThemeMode,
        onIconShapeChanged = viewModel::setIconShape,
        onIconThemeChanged = viewModel::setIconTheme,
        onOpenContact = viewModel::openContactResult,
        onToggleStarred = viewModel::toggleContactStarred,
        onContactLongPress = viewModel::dismissContactActions,
        onContactRowSelected = viewModel::onContactRowSelected,
        onContactActionsBack = { viewModel.onContactActionsBack() },
        onSetNumberDefault = viewModel::setNumberDefault,
        onOpenEvent = viewModel::openEventResult,
        onShowAgenda = viewModel::showAgenda,
        onShowWidgets = viewModel::showWidgets,
        onShowHome = viewModel::showHome,
        onHomeReady = viewModel::onHomeReady,
        onSetRecentsOpen = viewModel::setRecentsOpen,
        onRequestShowKeyboard = viewModel::requestShowKeyboard,
        onKeyboardReservationChanged = viewModel::setKeyboardReservation,
        onHomeLandscapeTierChanged = viewModel::setHomeLandscapeTier,
        onDockSuppressedByKeyboardChanged = viewModel::setDockSuppressedByKeyboard,
        keyboardShowRequests = viewModel.keyboardShowRequests,
        appWidgetHost = appWidgetHost,
        appWidgetManager = appWidgetManager,
        onAddWidget = onAddWidget,
        onDismissWidgetPicker = onDismissWidgetPicker,
        onSelectWidget = onSelectWidget,
        onRemoveWidget = onRemoveWidget,
        onRestoreWidget = onRestoreWidget,
        onResizeWidget = viewModel::resizeWidget,
        onMoveWidget = viewModel::moveWidget,
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
    onToggleWorkDock: (InstalledApp, Int) -> Unit = onToggleDock,
    onReorderDock: (String, Int, Int) -> Unit = { _, _, _ -> },
    onReorderWorkDock: (String, Int, Int) -> Unit = { _, _, _ -> },
    onMergeDock: (String, String) -> Unit = { _, _ -> },
    onMergeWorkDock: (String, String) -> Unit = { _, _ -> },
    onRemoveFromDockFolder: (String, String) -> Unit = { _, _ -> },
    onRemoveFromWorkDockFolder: (String, String) -> Unit = { _, _ -> },
    onUndockFromDockFolder: (String, String) -> Unit = { _, _ -> },
    onUndockFromWorkDockFolder: (String, String) -> Unit = { _, _ -> },
    onReorderDockFolderMember: (String, String, String) -> Unit = { _, _, _ -> },
    onMoveDockFolderMemberToDock: (String, String, Int, Int) -> Unit = { _, _, _, _ -> },
    onMergeDockFolderMemberInto: (String, String, String) -> Unit = { _, _, _ -> },
    onDockAppAtPosition: (String, Int, Int) -> Unit = { _, _, _ -> },
    onDockAppIntoOccupant: (String, String) -> Unit = { _, _ -> },
    onDockAppAtWorkDockPosition: (String, Int, Int) -> Unit = { _, _, _ -> },
    onDockAppIntoWorkDockOccupant: (String, String) -> Unit = { _, _ -> },
    onExplodeDockFolder: (String) -> Unit = {},
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
    onHideApp: (InstalledApp) -> Unit,
    onUnhideApp: (InstalledApp) -> Unit,
    onDismissRecent: (InstalledApp) -> Unit = {},
    onOpenSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenLauncherAppInfo: () -> Unit = {},
    onOpenPlayUpdate: () -> Unit = {},
    onCompletePlayUpdate: () -> Unit = {},
    onDismissPlayUpdate: () -> Unit = {},
    onRequestDefaultLauncher: () -> Unit,
    onDockEnabledChanged: (Boolean) -> Unit,
    onAppListLayoutChanged: (AppListLayout) -> Unit,
    onDockLayoutChanged: (DockLayout) -> Unit = {},
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onWorkDockEnabledChanged: (Boolean) -> Unit = {},
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onKeyboardAutoShownChanged: (Boolean) -> Unit = {},
    onWallpaperShownChanged: (Boolean) -> Unit = {},
    onCardOpacityChanged: (Float) -> Unit = {},
    onAgendaEnabledChanged: (Boolean) -> Unit = {},
    onContactSearchEnabledChanged: (Boolean) -> Unit = {},
    onCalendarSearchEnabledChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onIconShapeChanged: (IconShape) -> Unit = {},
    onIconThemeChanged: (IconTheme) -> Unit = {},
    onOpenContact: (ContactResult) -> Unit = {},
    onToggleStarred: (ContactResult) -> Unit = {},
    onContactLongPress: () -> Unit = {},
    onContactRowSelected: (ContactActionRow) -> Unit = {},
    onContactActionsBack: () -> Unit = {},
    onSetNumberDefault: (dataId: Long, makeDefault: Boolean) -> Unit = { _, _ -> },
    onOpenEvent: (AgendaEvent) -> Unit = {},
    onShowAgenda: () -> Unit,
    onShowWidgets: (Int) -> Unit,
    onShowHome: () -> Unit,
    onHomeReady: () -> Unit = {},
    onSetRecentsOpen: (Boolean) -> Unit = {},
    onRequestShowKeyboard: () -> Unit = {},
    onKeyboardReservationChanged: (KeyboardReservation) -> Unit = {},
    onHomeLandscapeTierChanged: (HomeLandscapeTier) -> Unit = {},
    onDockSuppressedByKeyboardChanged: (Boolean) -> Unit = {},
    keyboardShowRequests: SharedFlow<Unit> = MutableSharedFlow(),
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onAddWidget: (WidgetAddRequest) -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onRestoreWidget: (Int) -> Unit = {},
    onResizeWidget: (widgetId: Int, heightDp: Int) -> Unit = { _, _ -> },
    onMoveWidget: (widgetId: Int, direction: WidgetMoveDirection) -> Unit = { _, _ -> },
    onRequestCalendarPermission: () -> Unit,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
    onSwipeDown: () -> Unit = {},
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
) {
    LaunchedEffect(state.destination, state.isSettingsOpen, state.appListLayout) {
        LauncherDebugLog.event("TypeLauncherApp render target=${if (state.isSettingsOpen) "Settings" else state.destination}")
    }
    // Back closes the topmost launcher surface — the settings page (which
    // overlays the whole carousel when open), then the widget picker, then
    // an open recents tray — before falling through to the system. Without
    // an in-app handler the system default for back is Activity.finish():
    // as the default home the system immediately relaunches the finished
    // activity, so "back on settings" *looked* like it worked while
    // actually paying a full activity + ViewModel teardown and rebuild; as
    // a non-default app it simply exited the launcher. The `enabled` gate
    // keeps the system default on a bare Home screen, where back should do
    // nothing. Dialogs and dropdown menus are absent from the chain on
    // purpose — their popup windows consume back natively.
    // The contact-actions mode renders only in Home's app-list slot, so its Back
    // handling is gated to the Home page — otherwise swiping the carousel to
    // Widgets/Agenda (which leaves the mode set but off-screen) would let this
    // handler swallow Back on those pages instead of their own back behavior.
    val contactActionsOnHome = state.contactActions != null &&
        state.destination is LauncherDestination.Home
    BackHandler(
        enabled = state.isSettingsOpen || state.isAddingWidget || state.isRecentsOpen ||
            contactActionsOnHome,
    ) {
        when {
            state.isSettingsOpen -> onCloseSettings()
            state.isAddingWidget -> onDismissWidgetPicker()
            // Pop the in-list contact-actions mode: step two → step one → out.
            contactActionsOnHome -> onContactActionsBack()
            else -> onSetRecentsOpen(false)
        }
    }
    // Resolve how much of Home the current viewport can fit (keyboard, search
    // box, both, or — in cramped landscape — neither). Computed here, where the
    // configuration and the persisted keyboard reservation are both in scope,
    // and pushed back into state so MainActivity (window soft-input mode, resume
    // re-show) reads the same decision. Passed directly to HomeScreen below to
    // avoid the one-frame round-trip through state.
    val homeLandscape = rememberHomeLandscapeUi(state)
    val homeLandscapeTier = homeLandscape.tier
    LaunchedEffect(homeLandscapeTier) {
        onHomeLandscapeTierChanged(homeLandscapeTier)
    }
    val autoShowKeyboardFits = homeLandscapeTier == HomeLandscapeTier.Full
    HomeReadySignal(
        // Gate on the fresh load, not the spinner: on a warm start with cached
        // metadata, `isLoadingApps` is `false` while `installed_apps_load` is
        // still running on IO. Firing here would race the fresh app load —
        // exactly what this signal exists to prevent.
        appsReady = state.isFreshAppLoadComplete,
        // In a landscape viewport too short to fit the keyboard we suppress the
        // auto-show, so there is no IME to wait for — gating on the raw setting
        // would stall home-ready until the 1500ms timeout.
        waitForIme = state.isKeyboardAutoShown && autoShowKeyboardFits,
        onHomeReady = onHomeReady,
    )
    // Cold-start one-frame holdback for the home body (apps grid, dock,
    // recents). Hoisted here so it survives HomeScreen unmount /
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
        // Transparent so Home's "Show wallpaper" hole can reach the window
        // wallpaper: every screen paints its own opaque background full-bleed,
        // so the Scaffold's own container fill was redundant, and dropping it
        // removes one opaque layer between Home's punched-through app-list slot
        // and the `FLAG_SHOW_WALLPAPER` wallpaper behind the window. Off the
        // wallpaper path the window background (an opaque color) backs it, so
        // there is no visible change.
        containerColor = Color.Transparent,
        // `MainActivity` uses adjustResize, so the window is already resized
        // as the IME animates. Applying WindowInsets.ime here as well would
        // change Home's height twice during the same keyboard transition.
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.navigationBars),
    ) { innerPadding ->
        val density = LocalDensity.current
        val configuration = LocalConfiguration.current
        val imeVisible = WindowInsets.isImeVisible
        val imeBottomPx = WindowInsets.ime.getBottom(density)
        val imeTargetBottomPx = WindowInsets.imeAnimationTarget.getBottom(density)
        val navBottomPx = WindowInsets.navigationBars.getBottom(density)
        // Fingerprint of the configuration the IME geometry would have been
        // measured under right now. The persisted reservation is only safe
        // to apply when its fingerprint matches: rotation, fold/unfold,
        // density change, or a navigation-mode switch (gesture ↔ 3-button)
        // all change the keyboard's pixel height, and any of them can leave
        // a stale too-large reservation that the original grow-only cache
        // would never shake off.
        val currentConfigFingerprint = remember(
            configuration.orientation,
            configuration.screenWidthDp,
            configuration.screenHeightDp,
            configuration.densityDpi,
            navBottomPx,
        ) {
            KeyboardReservationConfig(
                orientation = configuration.orientation,
                screenWidthDp = configuration.screenWidthDp,
                screenHeightDp = configuration.screenHeightDp,
                densityDpi = configuration.densityDpi,
                navBottomPx = navBottomPx,
            )
        }
        val applicableSeedReservationPx = if (state.keyboardReservation.appliesUnder(currentConfigFingerprint)) {
            state.keyboardReservation.bottomPx
        } else {
            0
        }
        // Freeze the keyboard-height geometry for this Home entry. IME target
        // insets can jitter by a row fraction while the keyboard/tray toggles;
        // feeding each update back into Home padding visibly reflows the list.
        // Re-keyed on the configuration fingerprint so a rotation / nav-mode
        // switch starts fresh rather than carrying forward a now-incorrect
        // pixel height.
        var entryKeyboardBottomPx by remember(
            state.destination,
            state.isSettingsOpen,
            state.isKeyboardAutoShown,
            currentConfigFingerprint,
        ) {
            mutableStateOf(applicableSeedReservationPx)
        }
        // Whether a real visible IME has been observed during this entry.
        // Until then, the entry cache may have been seeded from an
        // animation-target-only persisted value that has never been
        // ground-truthed; we don't let it shrink off that seed.
        //
        // Seeded from the current `imeVisible` rather than `false` so a
        // configuration change that keeps the IME on screen (e.g. rotate
        // while typing) re-enters with the visible-IME confirmation
        // intact. The LaunchedEffect below is also keyed on the entry
        // reset keys for defense-in-depth: if `imeVisible` stays `true`
        // across a re-key, the effect would otherwise not run again and
        // the just-reset `hasSeenVisibleImeThisEntry = false` would block
        // the shrink branch for the rest of the entry.
        var hasSeenVisibleImeThisEntry by remember(
            state.destination,
            state.isSettingsOpen,
            state.isKeyboardAutoShown,
            currentConfigFingerprint,
        ) { mutableStateOf(imeVisible) }
        LaunchedEffect(
            imeVisible,
            state.destination,
            state.isSettingsOpen,
            state.isKeyboardAutoShown,
            currentConfigFingerprint,
        ) {
            if (imeVisible) hasSeenVisibleImeThisEntry = true
        }
        // Held by `rememberUpdatedState` so the LaunchedEffects below — whose
        // keys deliberately exclude `state.keyboardReservation` to avoid
        // re-launching every time the persistence callback fires back into
        // state — still read the freshest value when their `delay` resumes.
        val currentReservation by rememberUpdatedState(state.keyboardReservation)
        LaunchedEffect(state.keyboardReservation, currentConfigFingerprint, hasSeenVisibleImeThisEntry) {
            val reservation = state.keyboardReservation
            if (!reservation.appliesUnder(currentConfigFingerprint)) return@LaunchedEffect
            val candidate = reservation.bottomPx
            if (candidate <= currentConfigFingerprint.navBottomPx) return@LaunchedEffect
            if (candidate > entryKeyboardBottomPx) {
                // Grow-biased: any larger applicable reservation is adopted
                // immediately so secondary tray toggles and IME re-opens
                // can rest against keyboard-height geometry.
                entryKeyboardBottomPx = candidate
            } else if (candidate < entryKeyboardBottomPx &&
                hasSeenVisibleImeThisEntry &&
                reservation.source == KeyboardReservationSource.VisibleIme
            ) {
                // Shrink path: a visible-IME-confirmed smaller value lands
                // only after the user has actually seen the IME this entry.
                // Animation-target-only readings cannot pull the cache
                // down — multi-stage IME opens would dip below the settled
                // height during the transition.
                entryKeyboardBottomPx = candidate
            }
        }
        // Persist the keyboard's settled height, keyed on the *visible* IME
        // bottom inset (`WindowInsets.ime`) rather than its animation target.
        // A multi-stage open — the suggestion strip animating in then
        // collapsing — makes `imeAnimationTarget` momentarily aim above the
        // height the keyboard ultimately rests at. The previous target-keyed
        // growth path debounced that target, but a target that holds steady
        // above the settled height for the whole open animation (longer than
        // the debounce) was persisted anyway, then adopted grow-biased into
        // `entryKeyboardBottomPx` — floating the dock ~30dp above the keyboard
        // for the rest of the entry on a quick carousel swipe-back. The
        // visible inset only passes through that peak for a frame or two before
        // settling, so each climbing/peaking value re-keys this effect and
        // cancels the pending delay; only a height the keyboard actually holds
        // still at survives the debounce. `imeVisible` is required so the
        // reading is ground-truthed, not a still-settling target. Shrinks wait
        // [IME_SHRINK_DEBOUNCE_MS] longer than growths so a transient inset dip
        // during an IME layout swap cannot pull the reservation down before the
        // keyboard settles back.
        LaunchedEffect(imeBottomPx, imeVisible, currentConfigFingerprint) {
            if (!imeVisible) return@LaunchedEffect
            if (imeBottomPx <= currentConfigFingerprint.navBottomPx) return@LaunchedEffect
            val sameConfig = currentReservation.configFingerprint == currentConfigFingerprint
            val shrinking = sameConfig && imeBottomPx < currentReservation.bottomPx
            delay(if (shrinking) IME_SHRINK_DEBOUNCE_MS else IME_GROWTH_DEBOUNCE_MS)
            resolveKeyboardReservation(
                settledImeBottomPx = imeBottomPx,
                config = currentConfigFingerprint,
                current = currentReservation,
            )?.let(onKeyboardReservationChanged)
        }
        // In the cramped-landscape [HomeLandscapeTier.Compact] state the search box
        // is hidden by default; a pull-up (routed to `keyboardShowRequests`)
        // reveals it on demand. In [HomeLandscapeTier.DockNoKeyboard] the box is
        // already visible but the keyboard is down, so the same pull-up raises
        // the keyboard instead (see `autoShowKeyboard` in `HomeScreen`). Reset
        // on leaving Home and on resume (below) so a returning user starts from
        // the keyboard-down state again rather than a lingering reveal.
        //
        // Gated on the typing-headroom check: where the box + the raised
        // keyboard + one result row can't fit, the box is never shown, so a
        // pull-up must not reveal it (or raise a keyboard over a box that
        // isn't there) — search is a portrait affordance on such a window.
        var searchRevealedInTightLandscape by remember { mutableStateOf(false) }
        if (homeLandscapeTier != HomeLandscapeTier.Full && homeLandscape.searchBoxFitsWithKeyboard) {
            LaunchedEffect(keyboardShowRequests) {
                keyboardShowRequests.collect { searchRevealedInTightLandscape = true }
            }
        }
        // The keyboard is only expected up — and so its height only worth
        // pre-reserving — when the layout actually auto-shows it (Full with the
        // setting on) or the user has revealed it in the Compact state.
        // Without this gate a persisted reservation matching the current
        // landscape size would reserve the suppressed keyboard's height anyway,
        // re-squeezing the very app list the Compact state exists to free. A
        // pull-up reveal in Compact still reserves dynamically via the imeTarget /
        // imeVisible paths below as the IME animates in.
        val expectKeyboardThisEntry = (state.isKeyboardAutoShown && autoShowKeyboardFits) ||
            searchRevealedInTightLandscape
        val stableTypingGeometryAvailable = !state.isSettingsOpen &&
            expectKeyboardThisEntry &&
            entryKeyboardBottomPx > navBottomPx
        val shouldUseTypingGeometry = stableTypingGeometryAvailable
        val keyboardBottomPx = when {
            shouldUseTypingGeometry -> entryKeyboardBottomPx
            imeTargetBottomPx > navBottomPx -> imeTargetBottomPx
            imeVisible -> imeBottomPx
            else -> 0
        }
        val keyboardReservationPx = max(keyboardBottomPx - navBottomPx, 0)
        val keyboardReservationDp = with(density) { keyboardReservationPx.toDp() }
        // The keyboard's reserved bottom space exists only so the home layout
        // does not reflow when the auto-shown keyboard animates in on view open
        // (cold start / resume / carousel return). Once the user has actually
        // seen the keyboard this Home presence and then dismissed it, the
        // reservation is released so the apps list fills to the bottom — and a
        // pull-up / pull-down bar lands flush at the bottom edge rather than
        // floating above an empty reserved slot. `keyboardSeenThisHomePresence`
        // tracks whether the soft IME has been visible since the current Home
        // presence began; combined with "not currently showing / animating in"
        // it means "dismissed", which is when we collapse the reservation.
        //
        // The reset that prevents a launch flicker is driven by the *activity
        // lifecycle*, not ViewModel state: a `StateFlow` update made in onResume
        // reaches Compose a frame or two late, so the first resumed frame would
        // still see a stale "keyboard already seen" value and collapse the
        // reservation a frame early. The ON_RESUME observer writes the Compose
        // flag synchronously during the lifecycle dispatch, before that first
        // frame is drawn.
        var keyboardSeenThisHomePresence by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (imeVisible) keyboardSeenThisHomePresence = true
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                // Reset on ON_STOP (fully backgrounded) so the retained
                // composition's first resumed frame is already hidden, and again
                // on ON_RESUME so the reset lands synchronously before that frame
                // even when the activity wasn't fully stopped.
                if (event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_RESUME) {
                    keyboardSeenThisHomePresence = false
                    searchRevealedInTightLandscape = false
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        // Leaving Home (carousel to Widgets/Agenda, opening settings) ends the
        // presence, so a return to page zero starts hidden again.
        LaunchedEffect(state.destination, state.isSettingsOpen) {
            if (state.destination !is LauncherDestination.Home || state.isSettingsOpen) {
                keyboardSeenThisHomePresence = false
                searchRevealedInTightLandscape = false
            }
        }
        // Gate on the animation target, not just `imeVisible`: while the
        // keyboard grows the visibility flag can lag a frame or two behind the
        // animation (worst under gesture nav), and collapsing the reservation
        // for those frames would reflow the layout as the launcher opens. See
        // `isKeyboardShowingOrAnimatingIn`.
        val keyboardShowingOrAnimatingIn = isKeyboardShowingOrAnimatingIn(
            imeVisible = imeVisible,
            imeTargetBottomPx = imeTargetBottomPx,
            navBottomPx = navBottomPx,
        )
        // The DockNoKeyboard tier shows the dock with the keyboard down; once
        // the keyboard rises there is no room for both, so the dock yields.
        // Keyed on the showing-or-animating signal (not raw `imeVisible`, which
        // lags the animation target) so the dock yields on the same frame the
        // keyboard reservation starts applying, not a frame or two later. Only
        // ever true in that tier (Full/portrait keep the dock with the IME up).
        // Pushed to the ViewModel so the app-list dedupe surfaces the docked
        // apps while the dock is gone, and passed straight to HomeScreen so it
        // yields promptly.
        val dockSuppressedByKeyboard = keyboardShowingOrAnimatingIn &&
            homeLandscapeTier == HomeLandscapeTier.DockNoKeyboard
        LaunchedEffect(dockSuppressedByKeyboard) {
            onDockSuppressedByKeyboardChanged(dockSuppressedByKeyboard)
        }
        // The first landscape typing session measures the real IME height, which
        // can flip the typing-headroom gate false mid-presence (the reveal that
        // raised this keyboard was granted against the pre-measurement
        // estimate). Clear the lingering reveal once that keyboard is dismissed
        // — not mid-typing, which would yank the field from under the user — so
        // the box hides and stays hidden instead of the stale reveal outliving
        // the gate until the next Home presence reset.
        LaunchedEffect(homeLandscape.searchBoxFitsWithKeyboard, keyboardShowingOrAnimatingIn) {
            if (!homeLandscape.searchBoxFitsWithKeyboard && !keyboardShowingOrAnimatingIn) {
                searchRevealedInTightLandscape = false
            }
        }
        // The same mid-session flip needs the *tap* path latched too: a tap on
        // the optimistically-shown box carries no reveal flag, so the gate
        // flipping false while that keyboard is up would unmount the focused
        // field under the user. Hold the card through any in-flight keyboard
        // session; it hides when the keyboard dismisses (together with the
        // reveal reset above).
        val searchBoxFitsOrMidKeyboardSession =
            homeLandscape.searchBoxFitsWithKeyboard || keyboardShowingOrAnimatingIn
        // Collapse the reserved keyboard space once the keyboard has been seen
        // and dismissed this Home presence (see `keyboardSeenThisHomePresence`).
        // Until then it stays reserved so the auto-shown keyboard does not cause
        // a reflow on view open. The bottom bar takes its own real space inside
        // `HomeScreen`'s layout, so when the keyboard is up the bar stacks above
        // it (reservation present) and when the keyboard is gone the bar sits
        // flush at the bottom (reservation collapsed).
        val keyboardReservationCollapsed = keyboardSeenThisHomePresence && !keyboardShowingOrAnimatingIn
        val unpinnedKeyboardReservationDp = if (keyboardReservationCollapsed) 0.dp else keyboardReservationDp
        // --- Search-time dock reveal: pin the keyboard reservation. ---
        // While a long-press-with-query drag has hidden the keyboard to reveal
        // the dock (see the reveal block in HomeScreen), the reservation must
        // not move: collapsing it mid-drag would reflow the app list under the
        // user's finger — and the drag gesture lives in the pressed list
        // item's modifier node, so a reflow that scrolled the item out of the
        // lazy list's composition would kill the drag outright. The pin
        // freezes the reservation at its value when the reveal started and
        // releases once the restored keyboard starts animating back in, the
        // query clears (a dock drop landed), or a grace timeout passes (no
        // IME ever came) — see [SEARCH_DOCK_REVEAL_RESERVATION_GRACE_MS].
        var searchDockRevealActive by remember { mutableStateOf(false) }
        var searchDockRevealReservationPinned by remember { mutableStateOf(false) }
        var searchDockRevealPinnedReservationDp by remember { mutableStateOf(0.dp) }
        val currentUnpinnedReservationDp by rememberUpdatedState(unpinnedKeyboardReservationDp)
        val currentKeyboardShowingOrAnimatingIn by rememberUpdatedState(keyboardShowingOrAnimatingIn)
        val currentQueryIsBlank by rememberUpdatedState(state.query.isBlank())
        LaunchedEffect(searchDockRevealActive) {
            if (searchDockRevealActive || !searchDockRevealReservationPinned) return@LaunchedEffect
            withTimeoutOrNull(SEARCH_DOCK_REVEAL_RESERVATION_GRACE_MS) {
                snapshotFlow { currentKeyboardShowingOrAnimatingIn || currentQueryIsBlank }
                    .first { it }
            }
            searchDockRevealReservationPinned = false
        }
        val effectiveKeyboardReservationDp = if (searchDockRevealReservationPinned) {
            searchDockRevealPinnedReservationDp
        } else {
            unpinnedKeyboardReservationDp
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize(),
            ) {
                if (state.isSettingsOpen) {
                    SettingsScreen(
                        state = state,
                        innerPadding = innerPadding,
                        onCloseSettings = onCloseSettings,
                        onRequestDefaultLauncher = onRequestDefaultLauncher,
                        onDockEnabledChanged = onDockEnabledChanged,
                        onAppListLayoutChanged = onAppListLayoutChanged,
                        onDockLayoutChanged = onDockLayoutChanged,
                        onDockVisibleIconCountChanged = onDockVisibleIconCountChanged,
                        onWorkDockEnabledChanged = onWorkDockEnabledChanged,
                        onAppListSortOrderChanged = onAppListSortOrderChanged,
                        onKeyboardAutoShownChanged = onKeyboardAutoShownChanged,
                        onWallpaperShownChanged = onWallpaperShownChanged,
                        onCardOpacityChanged = onCardOpacityChanged,
                        onAgendaEnabledChanged = onAgendaEnabledChanged,
                        onContactSearchEnabledChanged = onContactSearchEnabledChanged,
                        onCalendarSearchEnabledChanged = onCalendarSearchEnabledChanged,
                        onThemeModeChanged = onThemeModeChanged,
                        onIconShapeChanged = onIconShapeChanged,
                        onIconThemeChanged = onIconThemeChanged,
                        onUnhideApp = onUnhideApp,
                        onOpenLauncherAppInfo = onOpenLauncherAppInfo,
                        onOpenPlayUpdate = onOpenPlayUpdate,
                        onCompletePlayUpdate = onCompletePlayUpdate,
                        onDismissPlayUpdate = onDismissPlayUpdate,
                    )
                } else {
                    var homeAppListBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
                    // The member icon currently being dragged out of an open dock
                    // folder, with its center in root coordinates — rendered as a
                    // top-level overlay (below) so it floats over the app list,
                    // unclipped by the dock card. Null when no drag-out is active.
                    var folderMemberDragFloat by remember { mutableStateOf<FolderMemberDragFloat?>(null) }
                    // Held by reference (not a plain value param) so the open
                    // bar's per-frame bounds updates during its expand/collapse
                    // animation flow to the carousel's pointer loop via `.value`
                    // without recomposing SwipeNavigationBox — same pattern as
                    // isDockDraggingState / isWidgetScrollingState.
                    val recentsScrollRegionState = remember { mutableStateOf<BarScrollRegion?>(null) }
                    // The carousel's pointerInput has to see this flip within a
                    // single pointer event (Main pass writes from the dock,
                    // Final pass reads from the carousel), so the state is
                    // shared by reference instead of going through a Boolean
                    // prop + rememberUpdatedState — the wrapper there only
                    // updates during recomposition, which is one frame later.
                    val isDockDraggingState = remember { mutableStateOf(false) }
                    // Same trick for hosted widgets: scrollable RemoteViews
                    // descendants call `requestDisallowInterceptTouchEvent(true)`
                    // when they start consuming touches, and the host forwards
                    // that signal through `LauncherAppWidgetHost.onChildScrollChange`.
                    // The carousel's pointer-input arbitrator otherwise reads
                    // raw deltas via `positionChangeIgnoreConsumed` and would
                    // claim a vertical drag inside a widget as a page swipe or
                    // pull-to-notification-shade gesture.
                    val isWidgetScrollingState = remember { mutableStateOf(false) }
                    DisposableEffect(appWidgetHost) {
                        val host = appWidgetHost as? LauncherAppWidgetHost
                        if (host == null) {
                            onDispose {}
                        } else {
                            host.onChildScrollChange = { isWidgetScrollingState.value = it }
                            onDispose {
                                host.onChildScrollChange = null
                                isWidgetScrollingState.value = false
                            }
                        }
                    }
                    SwipeNavigationBox(
                        destination = state.destination,
                        widgetPageCount = state.widgetPages.size,
                        isAgendaEnabled = state.isAgendaEnabled,
                        isRecentsOpen = state.isRecentsOpen,
                        onShowAgenda = onShowAgenda,
                        onShowWidgets = onShowWidgets,
                        onShowHome = onShowHome,
                        onSetRecentsOpen = onSetRecentsOpen,
                        onRequestShowKeyboard = onRequestShowKeyboard,
                        onSwipeDown = onSwipeDown,
                        // Park UI-thread RemoteViews.apply() while the carousel
                        // is in motion. The host stays listening, so the
                        // provider's background data fetch (location lookup,
                        // calendar query, network) runs during the drag and
                        // its result lands in the host's pending slot. On
                        // gesture end the host flushes parked RemoteViews so
                        // the fresh content paints in one pass after settle.
                        onCarouselGestureClaimed = {
                            (appWidgetHost as? LauncherAppWidgetHost)?.deferRemoteViewsApply = true
                        },
                        onCarouselGestureEnded = {
                            (appWidgetHost as? LauncherAppWidgetHost)?.deferRemoteViewsApply = false
                        },
                        appListBoundsInRoot = homeAppListBoundsInRoot,
                        recentsScrollRegionState = recentsScrollRegionState,
                        isDockDraggingState = isDockDraggingState,
                        isWidgetScrollingState = isWidgetScrollingState,
                    ) { page, isCurrentPage, isCurrentOrIncoming ->
                        when (page.screen) {
                            LauncherScreen.Home -> Box(modifier = Modifier.fillMaxSize()) {
                            HomeScreen(
                                state = state,
                                innerPadding = innerPadding,
                                bodyReady = homeBodyReady,
                                isVisibleHomePage = isCurrentOrIncoming,
                                landscapeTier = homeLandscapeTier,
                                searchBoxFitsWithKeyboard = searchBoxFitsOrMidKeyboardSession,
                                searchRevealed = searchRevealedInTightLandscape,
                                primaryBottomPadding = effectiveKeyboardReservationDp,
                                dockSuppressedByKeyboard = dockSuppressedByKeyboard,
                                searchPlaceholderSuffix = searchPlaceholderSuffix,
                                keyboardShowRequests = keyboardShowRequests,
                                onQueryChanged = onQueryChanged,
                                onClearQuery = onClearQuery,
                                onLaunchActiveApp = onLaunchActiveApp,
                                onLaunchApp = onLaunchApp,
                                onOpenAppInfo = onOpenAppInfo,
                                onToggleDock = onToggleDock,
                                onToggleWorkDock = onToggleWorkDock,
                                onReorderDock = onReorderDock,
                                onReorderWorkDock = onReorderWorkDock,
                                onMergeDock = onMergeDock,
                                onMergeWorkDock = onMergeWorkDock,
                                onRemoveFromDockFolder = onRemoveFromDockFolder,
                                onRemoveFromWorkDockFolder = onRemoveFromWorkDockFolder,
                                onUndockFromDockFolder = onUndockFromDockFolder,
                                onUndockFromWorkDockFolder = onUndockFromWorkDockFolder,
                                onReorderDockFolderMember = onReorderDockFolderMember,
                                onMoveDockFolderMemberToDock = onMoveDockFolderMemberToDock,
                                onMergeDockFolderMemberInto = onMergeDockFolderMemberInto,
                                onFolderMemberDragFloat = { app, center ->
                                    folderMemberDragFloat =
                                        app?.let { FolderMemberDragFloat(it, center) }
                                },
                                onDockAppAtPosition = onDockAppAtPosition,
                                onDockAppIntoOccupant = onDockAppIntoOccupant,
                                onDockAppAtWorkDockPosition = onDockAppAtWorkDockPosition,
                                onDockAppIntoWorkDockOccupant = onDockAppIntoWorkDockOccupant,
                                onExplodeDockFolder = onExplodeDockFolder,
                                onResetRank = onResetRank,
                                onRenameApp = onRenameApp,
                                onSetAppIconOverride = onSetAppIconOverride,
                                onClearAppIconOverride = onClearAppIconOverride,
                                onSetAppBadge = onSetAppBadge,
                                onHideApp = onHideApp,
                                onDismissRecent = onDismissRecent,
                                onOpenSettings = onOpenSettings,
                                onOpenContact = onOpenContact,
                                onToggleStarred = onToggleStarred,
                                onContactLongPress = onContactLongPress,
                                onContactRowSelected = onContactRowSelected,
                                onSetNumberDefault = onSetNumberDefault,
                                onContactActionsBack = onContactActionsBack,
                                onOpenEvent = onOpenEvent,
                                onAppListBoundsChanged = { homeAppListBoundsInRoot = it },
                                onBarScrollRegionChanged = { region ->
                                    recentsScrollRegionState.value = region
                                },
                                onDockDragChanged = { isDockDraggingState.value = it },
                                onSearchDockRevealChanged = { active ->
                                    if (active) {
                                        // Pin before flagging active so the same
                                        // frame's layout already reads the pinned
                                        // value.
                                        searchDockRevealPinnedReservationDp =
                                            currentUnpinnedReservationDp
                                        searchDockRevealReservationPinned = true
                                    }
                                    searchDockRevealActive = active
                                },
                                onRequestShowKeyboard = onRequestShowKeyboard,
                            )
                            // Floats the dragged-out folder member above everything
                            // (incl. the app list), positioned in root coordinates.
                            // Only on the live Home page, never on carousel copies.
                            folderMemberDragFloat?.let { float ->
                                if (isCurrentPage) {
                                    FolderMemberDragOverlay(
                                        float = float,
                                        iconSizeDp = dockIconSizing(
                                            minOf(
                                                configuration.screenWidthDp,
                                                configuration.screenHeightDp,
                                            ),
                                            state.dockIconSizeDp,
                                        ).iconSizeDp,
                                    )
                                }
                            }
                            }
                            LauncherScreen.Widgets -> WidgetsScreen(
                            widgetIds = state.widgetPages.getOrElse(
                                page.widgetPageIndex.coerceIn(0, state.widgetPages.lastIndex.coerceAtLeast(0)),
                            ) { emptyList() },
                            availableWidgets = state.availableWidgets,
                            isAddingWidget = state.isAddingWidget &&
                                (state.destination as? LauncherDestination.Widgets)?.pageIndex == page.widgetPageIndex,
                            isLoadingAvailableWidgets = state.isLoadingAvailableWidgets,
                            appWidgetHost = appWidgetHost,
                            appWidgetManager = appWidgetManager,
                            innerPadding = innerPadding,
                            widgetHeights = state.widgetHeights,
                            widgetProviderLabels = state.widgetProviderLabels,
                            strandedWidgetIds = state.strandedWidgetIds,
                            isCurrentPage = isCurrentPage,
                            onAddWidget = { isCurrentPageScrollable ->
                                onAddWidget(
                                    WidgetAddRequest(
                                        pageIndex = page.widgetPageIndex,
                                        isCurrentPageScrollable = isCurrentPageScrollable,
                                    ),
                                )
                            },
                            onDismissWidgetPicker = onDismissWidgetPicker,
                            onSelectWidget = onSelectWidget,
                            onRemoveWidget = onRemoveWidget,
                            onRestoreWidget = onRestoreWidget,
                            onResizeWidget = onResizeWidget,
                            onMoveWidget = onMoveWidget,
                            workProfileWidgetRefreshToken = state.workProfileWidgetRefreshToken,
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
    }
    // The contact quick-actions render inline in the app-list slot (see
    // HomeScreen's `state.contactActions` branch), not as a floating dialog.
}

/**
 * The folder member currently being dragged out of an open dock folder:
 * [app] is the dragged app and [centerInRoot] is its visual center in window
 * (root) coordinates, updated each pointer event.
 */
private data class FolderMemberDragFloat(val app: InstalledApp, val centerInRoot: Offset)

/**
 * Renders the dragged-out folder member as a free-floating icon above the whole
 * Home page — unclipped by the dock card (which a `Card` shape clips), so it can
 * track the finger up over the app list. The overlay reports its own origin in
 * root coordinates and offsets the icon by `centerInRoot - origin`, so the icon
 * lands under the finger regardless of system-bar insets. It is purely visual
 * (no pointer modifiers), so it never intercepts the in-flight drag gesture,
 * which the folder member tile still owns.
 */
@Composable
private fun FolderMemberDragOverlay(
    float: FolderMemberDragFloat,
    iconSizeDp: Int,
    modifier: Modifier = Modifier,
) {
    var originInRoot by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coords -> originInRoot = coords.positionInRoot() },
    ) {
        val iconPx = with(LocalDensity.current) { iconSizeDp.dp.toPx() }
        val local = float.centerInRoot - originInRoot
        Box(
            modifier = Modifier
                .testTag(DRAG_OVERLAY_TAG)
                .offset {
                    IntOffset(
                        (local.x - iconPx / 2f).roundToInt(),
                        (local.y - iconPx / 2f).roundToInt(),
                    )
                }
                .size(iconSizeDp.dp)
                .graphicsLayer {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    alpha = 0.85f
                },
        ) {
            AppIcon(app = float.app, size = iconSizeDp.dp)
        }
    }
}

/**
 * Resolves the [HomeLandscapeUi] — the [HomeLandscapeTier] plus whether the
 * search box has typing headroom — for the current configuration. Reads only
 * the live configuration, navigation-bar inset, dock settings, and the
 * persisted keyboard reservation — never the live IME insets — so the value is
 * stable through a keyboard animation and recomputes only on a real
 * configuration / setting / reservation change.
 */
@Composable
private fun rememberHomeLandscapeUi(state: LauncherUiState): HomeLandscapeUi {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val navBottomPx = WindowInsets.navigationBars.getBottom(density)
    // On Android 15+ (with this app's targetSdk) Configuration.screenHeightDp
    // includes the system bars, but the Scaffold consumes the status- and
    // navigation-bar insets before Home gets any height — so the landscape
    // fit estimates must subtract them. Older platforms report the
    // Configuration already net of the bars, so there is nothing to subtract.
    val verticalSystemBarsPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        WindowInsets.statusBars.getTop(density) + navBottomPx
    } else {
        0
    }
    val isWorkDockVisible = state.isWorkDockEnabled && state.isWorkProfileActive
    // Folders are occupants too, so count them when sizing the work dock for the
    // landscape-tier fit (a work folder can occupy a second row).
    val workDockedAppIds = if (isWorkDockVisible) {
        state.workDockedApps.map { it.id } + state.workDockFolders.map { it.id }
    } else {
        emptyList()
    }
    // The personal dock's occupants gate the single-row DockNoKeyboard fit the
    // same way the work dock's do.
    val personalDockOccupantIds = if (state.isDockEnabled) {
        state.dockedApps.map { it.id } + state.dockFolders.map { it.id }
    } else {
        emptyList()
    }
    return remember(
        configuration.orientation,
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.densityDpi,
        navBottomPx,
        verticalSystemBarsPx,
        state.dockIconSizeDp,
        state.isDockEnabled,
        personalDockOccupantIds,
        isWorkDockVisible,
        workDockedAppIds,
        state.workDockPositions,
        state.keyboardReservation,
        state.dockLayout,
        state.appListLayout,
        density.fontScale,
    ) {
        val fingerprint = KeyboardReservationConfig(
            orientation = configuration.orientation,
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            densityDpi = configuration.densityDpi,
            navBottomPx = navBottomPx,
        )
        val metrics = homeLandscapeMetrics(
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            densityDpi = configuration.densityDpi,
            targetDockIconSizeDp = state.dockIconSizeDp,
            isPersonalDockEnabled = state.isDockEnabled,
            personalDockOccupantIds = personalDockOccupantIds,
            isWorkDockVisible = isWorkDockVisible,
            workDockedAppIds = workDockedAppIds,
            workDockPositions = state.workDockPositions,
            keyboardReservation = state.keyboardReservation,
            reservationFingerprint = fingerprint,
            dockLayout = state.dockLayout,
            appListLayout = state.appListLayout,
            fontScale = density.fontScale,
            verticalSystemBarsPx = verticalSystemBarsPx,
        )
        HomeLandscapeUi(
            tier = resolveHomeLandscapeTier(metrics),
            searchBoxFitsWithKeyboard = metrics.searchBoxFitsWithKeyboard,
        )
    }
}

/**
 * Fires `onHomeReady` exactly once after the fresh `LauncherApps` query has
 * returned (`appsReady`). When Home is configured to auto-show the keyboard,
 * this also waits until the soft keyboard is visible or
 * [HOME_READY_IME_TIMEOUT_MS] has elapsed since the apps loaded. The downstream
 * signal releases deferred startup work, including the initial agenda load when
 * Agenda is enabled.
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
    destination: LauncherDestination,
    widgetPageCount: Int,
    isAgendaEnabled: Boolean,
    isRecentsOpen: Boolean,
    appListBoundsInRoot: Rect?,
    recentsScrollRegionState: State<BarScrollRegion?> = mutableStateOf<BarScrollRegion?>(null),
    onShowAgenda: () -> Unit,
    onShowWidgets: (Int) -> Unit,
    onShowHome: () -> Unit,
    onSetRecentsOpen: (Boolean) -> Unit,
    onRequestShowKeyboard: () -> Unit,
    onSwipeDown: () -> Unit,
    onCarouselTransitioningChanged: (Boolean) -> Unit = {},
    onCarouselGestureClaimed: () -> Unit = {},
    onCarouselGestureEnded: () -> Unit = {},
    isDockDraggingState: State<Boolean> = mutableStateOf(false),
    isWidgetScrollingState: State<Boolean> = mutableStateOf(false),
    // (page, isCurrentPage, isCurrentOrIncoming): `isCurrentOrIncoming` is true
    // for the settled page and for the page a transition is animating toward, so
    // a page can present its "visible" state as it slides in rather than waiting
    // for the destination to commit at the end of the settle.
    content: @Composable (LauncherPage, Boolean, Boolean) -> Unit,
) {
    // A pointer sequence locks once, shortly after touch slop, to either the
    // child scrollable that consumed movement at gesture start or to a
    // launcher-level action. Reaching a child edge mid-gesture does not hand
    // the same drag to the carousel/pull handlers; the next gesture can claim
    // from that already-at-edge state.
    val currentScreen by rememberUpdatedState(destination.screen)
    val currentLauncherPage by rememberUpdatedState(destination.toLauncherPage())
    val currentRecentsOpen by rememberUpdatedState(isRecentsOpen)
    val currentSetRecentsOpen by rememberUpdatedState(onSetRecentsOpen)
    val currentRequestShowKeyboard by rememberUpdatedState(onRequestShowKeyboard)
    val currentOnSwipeDown by rememberUpdatedState(onSwipeDown)
    val currentAppListBoundsInRoot by rememberUpdatedState(appListBoundsInRoot)
    // Dock drag-to-reorder fights the carousel for the same horizontal motion:
    // the dock's pointerInput consumes pointer changes, but the carousel reads
    // raw deltas via positionChangeIgnoreConsumed and can't see that
    // consumption (consume() does not dispatch nested scroll). The dock writes
    // to isDockDraggingState during the Main pass; the carousel's pointerInput
    // reads .value directly during the Final pass of the same event, so this
    // must be the same MutableState object — not a Boolean prop wrapped in
    // rememberUpdatedState, which would only refresh after recomposition.
    val keyboard = LocalSoftwareKeyboardController.current
    val currentKeyboard by rememberUpdatedState(keyboard)
    val focusManager = LocalFocusManager.current
    val currentFocusManager by rememberUpdatedState(focusManager)
    // The bottom-bar state machine for the recents bar:
    //
    //            pull up                         pull down
    //   None  -> open Recents            None  -> system shade
    //   Recents -> re-show keyboard      Recents -> close (opposite gesture)
    //
    // i.e. pull-down opens the real system notification shade unless the
    // recents bar is open (the opposite gesture hides it first), and a second
    // pull-up on recents re-shows the search keyboard.
    val swipeDownDispatch = remember<() -> Unit> {
        {
            if (currentScreen == LauncherScreen.Home && currentRecentsOpen) {
                currentSetRecentsOpen(false)
            } else {
                currentOnSwipeDown()
            }
        }
    }
    val swipeUpDispatch = remember<() -> Unit> {
        {
            // Pull-up only does anything on Home.
            if (currentScreen == LauncherScreen.Home) {
                if (currentRecentsOpen) {
                    currentRequestShowKeyboard()
                } else {
                    currentSetRecentsOpen(true)
                }
            }
        }
    }
    var currentPage by remember {
        mutableStateOf(
            LauncherScreen.initialCarouselPage(
                page = destination.toLauncherPage(),
                widgetPageCount = widgetPageCount,
                isAgendaEnabled = isAgendaEnabled,
            ),
        )
    }
    var carouselPageConfig by remember {
        mutableStateOf(CarouselPageConfig(widgetPageCount = widgetPageCount, isAgendaEnabled = isAgendaEnabled))
    }
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
    // Bumped whenever a carousel animation job finishes (see
    // [trackCarouselAnimationJob]) so the destination-sync effect below
    // re-evaluates once its bail conditions (job active / offset mid-flight /
    // transition not Idle) have cleared. Without a re-run key, a destination
    // change that lands *during* a snap-back or external settle is dropped
    // forever: the effect bails, nothing re-triggers it (its other keys are
    // unchanged), and the carousel is left desynced from `state.destination`
    // — at which point the claim gate's `currentLauncherPage ==
    // candidateLauncherPage` check refuses every subsequent swipe until an
    // unrelated destination event or a rotation resyncs it.
    var carouselResyncTick by remember { mutableStateOf(0) }
    fun trackCarouselAnimationJob(job: Job) {
        carouselAnimationJob = job
        job.invokeOnCompletion { carouselResyncTick++ }
    }
    var carouselTransition by remember { mutableStateOf<CarouselTransitionState>(CarouselTransitionState.Idle) }
    var allowSwipeWithUnackedScreen by remember { mutableStateOf(false) }
    var queuedSettleSwipe by remember { mutableStateOf<QueuedSettleSwipe?>(null) }
    // Session-sticky flag that unlocks composition of the offscreen widget
    // slots in the carousel's [-1, 0, +1] window. Flipped only when a
    // gesture has been *claimed* by the carousel toward a Widgets target —
    // both in the direct claim path and in the queued-settle replay path.
    // A cancelled drag (pull-back before the carousel claims the gesture)
    // never flips it, so accidental gestures do not trigger AppWidgetHost
    // bindings or provider side effects. Declared here so
    // `playQueuedSettleSwipe` below can also read/write it.
    var widgetsWarmed by remember { mutableStateOf(false) }
    val currentOnCarouselTransitioningChanged by rememberUpdatedState(onCarouselTransitioningChanged)
    val currentOnCarouselGestureClaimed by rememberUpdatedState(onCarouselGestureClaimed)
    val currentOnCarouselGestureEnded by rememberUpdatedState(onCarouselGestureEnded)
    fun setCarouselTransition(next: CarouselTransitionState) {
        // Drop the queue if the carousel retargets to a different settled page
        // than the one the queued direction was recorded against — replaying
        // against an unrelated landing point would commit to a page the user
        // never set up.
        val nextTargetPage = when (next) {
            is CarouselTransitionState.UserAnimating -> next.targetPage
            is CarouselTransitionState.ExternalAnimating -> next.targetPage
            is CarouselTransitionState.AwaitingAck -> next.settledPage
            CarouselTransitionState.Idle -> null
        }
        val queuedTarget = queuedSettleSwipe?.settleTargetPage
        if (queuedTarget != null && nextTargetPage != null && queuedTarget != nextTargetPage) {
            queuedSettleSwipe = null
        }
        val wasTransitioning = carouselTransition != CarouselTransitionState.Idle
        carouselTransition = next
        val nowTransitioning = next != CarouselTransitionState.Idle
        if (wasTransitioning != nowTransitioning) {
            currentOnCarouselTransitioningChanged(nowTransitioning)
        }
    }
    fun dispatchSettledPage(settledPage: LauncherPage) {
        when (settledPage.screen) {
            LauncherScreen.Agenda -> onShowAgenda()
            LauncherScreen.Widgets -> onShowWidgets(settledPage.widgetPageIndex)
            LauncherScreen.Home -> onShowHome()
        }
    }
    fun hideKeyboardForCarouselPage(targetScreen: LauncherScreen) {
        if (targetScreen != LauncherScreen.Home) {
            currentFocusManager.clearFocus(force = true)
            currentKeyboard?.hide()
        }
    }
    fun awaitPageAck(targetPage: Int, targetLauncherPage: LauncherPage) {
        allowSwipeWithUnackedScreen = false
        setCarouselTransition(
            CarouselTransitionState.AwaitingAck(
                settledPage = targetPage,
                expectedPage = targetLauncherPage,
            ),
        )
        if (currentLauncherPage != targetLauncherPage) {
            dispatchSettledPage(targetLauncherPage)
        } else {
            setCarouselTransition(CarouselTransitionState.Idle)
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
    fun playQueuedSettleSwipe(settledPage: Int, pageWidthPx: Float) {
        val dragDirection = queuedSettleSwipe?.direction ?: return
        queuedSettleSwipe = null
        // Replay from the carousel's settled page, not from the page that was
        // active when the settling animation began, so one gesture still
        // advances at most one page from the visible settled start point.
        val targetPage = (settledPage + dragDirection)
            .coerceIn(0, LauncherScreen.carouselPageCount - 1)
        if (targetPage == settledPage) return
        val targetLauncherPage = LauncherScreen.fromCarouselPage(
            targetPage,
            widgetPageCount = widgetPageCount,
            isAgendaEnabled,
        )
        // A queued settle swipe is a committed page change replayed from
        // the prior settle; mirror the direct claim path so the same warm
        // and host defer-apply behaviour applies to it.
        if (targetLauncherPage.screen == LauncherScreen.Widgets) {
            widgetsWarmed = true
        }
        currentOnCarouselGestureClaimed()
        setCarouselTransition(CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage))
        hideKeyboardForCarouselPage(targetLauncherPage.screen)
        trackCarouselAnimationJob(coroutineScope.launch {
            try {
                val targetOffsetPx = if (targetPage > settledPage) -pageWidthPx else pageWidthPx
                animateCarouselOffsetTo(targetOffsetPx)
                if (carouselTransition == CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage)) {
                    currentPage = targetPage
                    carouselOffsetPx = 0f
                    awaitPageAck(targetPage, targetLauncherPage)
                } else {
                    carouselOffsetPx = 0f
                }
            } finally {
                currentOnCarouselGestureEnded()
            }
        })
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
                "SwipeNavigationBox ack timeout settled=${transition.settledPage} expected=${transition.expectedPage} " +
                    "page=$currentLauncherPage",
            )
            allowSwipeWithUnackedScreen = true
            dispatchSettledPage(transition.expectedPage)
            setCarouselTransition(CarouselTransitionState.Idle)
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
                flingCommitVelocityPxPerSec,
                backwardVelocityCancelPxPerSec,
                widgetPageCount,
                isAgendaEnabled,
            ) {
                awaitEachGesture {
                    val downChange = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    // Diagnostic for #513's follow-up (a widget's long-press
                    // menu popping up from what should be a clean swipe):
                    // captured here (a plain read, not a log call) so the
                    // claim/deferred log sites below can report the carousel's
                    // state at gesture *start* alongside its state at the
                    // decision point, without logging on every gesture. This
                    // `pointerInput` observes every tap and scroll that starts
                    // anywhere on the launcher, not just page swipes, so an
                    // unconditional log call here would flood the 300-line
                    // bug-report buffer with unrelated app-list/dock taps and
                    // evict the widget-timer lines this diagnostic exists to
                    // capture.
                    val gestureStartTransition = carouselTransition
                    val gestureStartPage = currentPage
                    val gestureStartOffsetPx = carouselOffsetPx
                    val startConsumed = scrollConsumptionTracker.totalConsumed
                    val pageWidthPx = size.width.toFloat().coerceAtLeast(1f)
                    var rawDragX = 0f
                    var rawDragY = 0f
                    var displayedDragX = 0f
                    var owner = LauncherGestureOwner.Undecided
                    // Latch: true once a dock reorder is observed at any event
                    // during this gesture. Reading isDockDraggingState live in
                    // the claim check is unsafe on `up` — the dock fires
                    // onDragEnd in Main pass before this Final-pass loop sees
                    // the up event, so the live state is false again, and
                    // rawDragX is still non-zero from the prior moveBy. The
                    // latch keeps the suppression in effect for the whole
                    // gesture, including the release event.
                    var dockDraggedDuringGesture = false
                    // Same latch shape for widgets: a hosted widget's
                    // scrollable descendant signals consumption via
                    // `requestDisallowInterceptTouchEvent(true)`, which the
                    // launcher's `LauncherAppWidgetHostView` forwards into
                    // `isWidgetScrollingState`. ACTION_UP/CANCEL flip the
                    // state back to false in the host view, so latching here
                    // keeps the suppression in effect through the release.
                    var widgetScrolledDuringGesture = false
                    var carouselClaimed = false
                    // Diagnostic-only latch: see the "SwipeNavigationBox claim
                    // deferred" log site below.
                    var loggedDeferredClaim = false
                    // Set true once the post-release animation has been
                    // launched — that coroutine's `finally` then owns
                    // `currentOnCarouselGestureEnded()`. If the pointer
                    // stream is cancelled (composition disposed, another
                    // window steals input) before the launch fires, the
                    // awaitEachGesture-level `finally` calls it instead so
                    // the host's defer-apply window doesn't stick true.
                    var releaseAnimationLaunched = false
                    // Captured at the moment the carousel claims this gesture
                    // (which can be later than first-down if the gesture began
                    // during a settle). Anchoring rawDragX and re-reading the
                    // pager's then-current page at the claim instant lets a
                    // swipe that started while the carousel was still settling
                    // pick up cleanly once it reaches Idle, without snapping
                    // to wherever the finger drifted before the claim and
                    // without committing the pre-claim drag against the old
                    // (pre-settle) start page.
                    var claimGestureStartPage = 0
                    var anchorRawDragX = 0f
                    // Latched once, the first time the gesture resolves as a
                    // horizontal launcher swipe: true when the drag began on an
                    // open bottom bar's scrollable strip and the strip can still
                    // scroll the way the finger is moving. While set, the
                    // carousel never claims, so the bar's own horizontalScroll
                    // keeps the gesture — the region check is deterministic, so
                    // it does not depend on the child winning a consumption race
                    // (which it loses in the gaps and chevron zones where there
                    // is no app-icon click handler to pass the drag through).
                    var barReservationDecided = false
                    var barReservedGesture = false
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPointerInputChange(downChange)
                    try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            val rawDragXBefore = rawDragX
                            val rawDelta = change.positionChangeIgnoreConsumed()
                            rawDragX += rawDelta.x
                            rawDragY += rawDelta.y
                            // Use addPointerInputChange so the tracker also sees
                            // change.historical — the intermediate samples Android's
                            // input dispatcher batches into one event when a frame
                            // stalls. Without these, a janky frame near release
                            // (notably AppWidgetHostView first-inflation during a
                            // swipe to a widget page) collapses real finger motion
                            // into a single low-rate slope and can either drop the
                            // release velocity below the fling bar or fit a noisy
                            // slope opposite the drag, spuriously firing the
                            // backward-velocity cancel.
                            velocityTracker.addPointerInputChange(change)
                            if (isDockDraggingState.value) {
                                dockDraggedDuringGesture = true
                            }
                            if (isWidgetScrollingState.value) {
                                widgetScrolledDuringGesture = true
                            }
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
                            if (!barReservationDecided &&
                                owner == LauncherGestureOwner.HorizontalLauncher
                            ) {
                                barReservationDecided = true
                                // Only read the region while the bar is open, so
                                // an exiting bar's stale region can never reserve
                                // a drag meant for the page.
                                barReservedGesture = shouldReserveGestureForBar(
                                    isBarOpen = currentRecentsOpen,
                                    region = if (currentRecentsOpen) recentsScrollRegionState.value else null,
                                    downPosition = downChange.position,
                                    rawDragX = rawDragX,
                                )
                            }
                            if (!carouselClaimed &&
                                owner == LauncherGestureOwner.HorizontalLauncher &&
                                !barReservedGesture &&
                                !dockDraggedDuringGesture &&
                                !widgetScrolledDuringGesture
                            ) {
                                val candidatePage = currentPage
                                val candidateLauncherPage = LauncherScreen.fromCarouselPage(
                                    candidatePage,
                                    widgetPageCount = widgetPageCount,
                                    isAgendaEnabled,
                                )
                                val canStartCarouselGesture =
                                    carouselTransition == CarouselTransitionState.Idle &&
                                        carouselAnimationJob?.isActive != true &&
                                        carouselOffsetPx == 0f &&
                                        (currentLauncherPage == candidateLauncherPage ||
                                            allowSwipeWithUnackedScreen)
                                if (!canStartCarouselGesture && !loggedDeferredClaim) {
                                    // Diagnostic for #513's follow-up: the
                                    // gesture has resolved as a horizontal
                                    // launcher swipe but the claim (and the
                                    // widget-cancel signal it triggers) is
                                    // deferred until the prior transition
                                    // reaches Idle — the window in which a
                                    // widget's own long-press timer, armed at
                                    // this gesture's down, is not yet backed
                                    // by a Compose-side cancel.
                                    loggedDeferredClaim = true
                                    LauncherDebugLog.event(
                                        "SwipeNavigationBox claim deferred startTransition=$gestureStartTransition " +
                                            "startPage=$gestureStartPage startOffsetPx=$gestureStartOffsetPx " +
                                            "transition=$carouselTransition " +
                                            "animationActive=${carouselAnimationJob?.isActive} " +
                                            "carouselOffsetPx=$carouselOffsetPx",
                                    )
                                }
                                if (canStartCarouselGesture) {
                                    carouselClaimed = true
                                    LauncherDebugLog.event(
                                        "SwipeNavigationBox claimed startTransition=$gestureStartTransition " +
                                            "startPage=$gestureStartPage startOffsetPx=$gestureStartOffsetPx " +
                                            "candidatePage=$candidatePage rawDragX=$rawDragX rawDragY=$rawDragY",
                                    )
                                    // Warm widgets and start the
                                    // UI-thread defer-apply window now,
                                    // both atomically with the claim.
                                    // Direction-toward-widgets check via
                                    // `candidateLauncherPage + drag sign`:
                                    // fromCarouselPage uses floorMod, so
                                    // when agenda is off the wraparound
                                    // from Home also lands on Widgets in
                                    // either direction. The provider's
                                    // background data fetch can run
                                    // during the drag while listening
                                    // stays on; only the UI-thread
                                    // RemoteViews.apply() is parked.
                                    val claimAdjacentPage = if (rawDragX < 0f) candidatePage + 1 else candidatePage - 1
                                    val claimDirectionTarget = LauncherScreen.fromCarouselPage(
                                        claimAdjacentPage,
                                        widgetPageCount = widgetPageCount,
                                        isAgendaEnabled = isAgendaEnabled,
                                    )
                                    if (claimDirectionTarget.screen == LauncherScreen.Widgets) {
                                        widgetsWarmed = true
                                    }
                                    currentOnCarouselGestureClaimed()
                                    claimGestureStartPage = candidatePage
                                    // Anchor at rawDragX *before* this event's delta
                                    // so the first claimed event still moves the
                                    // carousel by that delta, instead of being
                                    // absorbed into the anchor and looking dropped.
                                    anchorRawDragX = rawDragXBefore
                                    // Drop pre-claim velocity samples — they were
                                    // recorded against a different anchor and
                                    // would otherwise let a fast pre-settle swipe
                                    // satisfy flingCommits, or oppose-cancel a
                                    // valid post-claim drag, even though the
                                    // commit decision uses effectiveDragX.
                                    //
                                    // Intentionally use addPosition here, not
                                    // addPointerInputChange: the latter would
                                    // re-add change.historical, and on a claim
                                    // that fires inside a batched move event
                                    // those historical samples are by definition
                                    // pre-claim — exactly what resetTracking
                                    // just discarded. The next event's historical
                                    // samples are all post-claim, so subsequent
                                    // calls in the loop use addPointerInputChange
                                    // normally.
                                    velocityTracker.resetTracking()
                                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                                }
                            }
                            if (carouselClaimed && !widgetScrolledDuringGesture) {
                                val effectiveDragX = rawDragX - anchorRawDragX
                                val nextDisplayedDragX = effectiveDragX.coerceIn(-pageWidthPx, pageWidthPx)
                                carouselOffsetPx = nextDisplayedDragX
                                displayedDragX = nextDisplayedDragX
                                change.consume()
                            }
                            // Carousel claim → widget signal race: a horizontal
                            // drag can cross the launcher's 8 dp slop and arm
                            // `carouselClaimed = true` one event before a
                            // hosted-widget descendant calls
                            // `requestDisallowInterceptTouchEvent(true)`. Past
                            // this point the post-claim drag block would
                            // otherwise keep following the finger and the
                            // release path would commit a page change even
                            // though the gesture really belongs to the widget.
                            // Freezing the offset above (and the commit check
                            // at release below) is the targeted fix; the
                            // existing `animateCarouselOffsetTo(0f)` in the
                            // not-committed branch then snaps any pre-claim
                            // visual offset back to the source page.
                        }
                    } while (event.changes.any { it.pressed })

                    if (!carouselClaimed) {
                        val settleTargetPage = carouselSettleTargetPage(
                            transition = carouselTransition,
                            // Idle + an active animation job is the tail of an
                            // uncommitted snap-back — queue the flick against the
                            // page it's settling back to instead of dropping it.
                            isSettleAnimationActive = carouselAnimationJob?.isActive == true,
                            settledPage = currentPage,
                        )
                        if (owner == LauncherGestureOwner.HorizontalLauncher &&
                            !barReservedGesture &&
                            !dockDraggedDuringGesture &&
                            !widgetScrolledDuringGesture &&
                            settleTargetPage != null
                        ) {
                            val releaseVelocity = velocityTracker.calculateVelocity().x
                            val dragDirection = when {
                                rawDragX < 0f -> 1
                                rawDragX > 0f -> -1
                                else -> 0
                            }
                            val velocityOpposesDrag = dragDirection != 0 &&
                                abs(releaseVelocity) >= backwardVelocityCancelPxPerSec &&
                                sign(releaseVelocity) == -sign(rawDragX)
                            val distanceCommits = abs(rawDragX) >= pageWidthPx / 2f
                            val flingCommits = dragDirection != 0 &&
                                abs(releaseVelocity) >= flingCommitVelocityPxPerSec &&
                                sign(releaseVelocity) == sign(rawDragX)
                            if (dragDirection != 0 &&
                                !velocityOpposesDrag &&
                                (distanceCommits || flingCommits)
                            ) {
                                queuedSettleSwipe = QueuedSettleSwipe(
                                    direction = dragDirection,
                                    settleTargetPage = settleTargetPage,
                                )
                                LauncherDebugLog.event(
                                    "SwipeNavigationBox queued settle swipe direction=$dragDirection " +
                                        "targetPage=$settleTargetPage rawDragX=$rawDragX",
                                )
                            }
                        }
                        return@awaitEachGesture
                    }

                    val effectiveDragX = rawDragX - anchorRawDragX
                    val gestureStartPage = claimGestureStartPage
                    val releaseVelocity = velocityTracker.calculateVelocity().x
                    val dragDirection = when {
                        effectiveDragX < 0f -> 1
                        effectiveDragX > 0f -> -1
                        else -> 0
                    }
                    val velocityOpposesDrag = dragDirection != 0 &&
                        abs(releaseVelocity) >= backwardVelocityCancelPxPerSec &&
                        sign(releaseVelocity) == -sign(effectiveDragX)
                    val distanceCommits = abs(effectiveDragX) >= pageWidthPx / 2f
                    val flingCommits = dragDirection != 0 &&
                        abs(releaseVelocity) >= flingCommitVelocityPxPerSec &&
                        sign(releaseVelocity) == sign(effectiveDragX)
                    val committed = dragDirection != 0 &&
                        !velocityOpposesDrag &&
                        !widgetScrolledDuringGesture &&
                        (distanceCommits || flingCommits)

                    LauncherDebugLog.event(
                        "SwipeNavigationBox horizontal release effectiveDragX=$effectiveDragX rawDragY=$rawDragY " +
                            "velocityX=$releaseVelocity " +
                            "distanceCommits=$distanceCommits flingCommits=$flingCommits " +
                            "velocityOpposes=$velocityOpposesDrag " +
                            "widgetScrolled=$widgetScrolledDuringGesture committed=$committed",
                    )
                    val targetPage = if (committed) {
                        (gestureStartPage + dragDirection)
                            .coerceIn(0, LauncherScreen.carouselPageCount - 1)
                    } else {
                        gestureStartPage
                    }
                    val targetLauncherPage = LauncherScreen.fromCarouselPage(
                        targetPage,
                        widgetPageCount = widgetPageCount,
                        isAgendaEnabled,
                    )
                    val willChangePage = committed && targetPage != gestureStartPage
                    if (willChangePage) {
                        setCarouselTransition(
                            CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage),
                        )
                        hideKeyboardForCarouselPage(targetLauncherPage.screen)
                    }
                    trackCarouselAnimationJob(coroutineScope.launch {
                        try {
                            val targetOffsetPx = when {
                                targetPage > gestureStartPage -> -pageWidthPx
                                targetPage < gestureStartPage -> pageWidthPx
                                else -> 0f
                            }
                            animateCarouselOffsetTo(targetOffsetPx)
                            if (willChangePage &&
                                carouselTransition == CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage)
                            ) {
                                currentPage = targetPage
                                carouselOffsetPx = 0f
                                awaitPageAck(targetPage, targetLauncherPage)
                            } else {
                                carouselOffsetPx = 0f
                            }
                        } finally {
                            // Always lift the host's defer-apply window so a
                            // cancelled-mid-animation job (next gesture
                            // preempting this one) still flushes parked
                            // RemoteViews.
                            currentOnCarouselGestureEnded()
                        }
                        if (!willChangePage) {
                            // Uncommitted snap-back completed normally (a
                            // cancellation would have propagated past this
                            // point). The transition stayed Idle throughout, so
                            // neither the AwaitingAck resolve nor the external-
                            // animation completion replays a flick queued while
                            // the carousel was settling back — replay it here
                            // from the page we returned to. A no-queue snap-back
                            // no-ops. Runs after the finally above, so the fresh
                            // gesture's defer-apply window opens on a clean slate.
                            playQueuedSettleSwipe(gestureStartPage, pageWidthPx)
                        }
                    })
                    releaseAnimationLaunched = true
                    } finally {
                        // If the pointer stream was cancelled after claim
                        // but before the release-animation launch, the
                        // launch's finally never ran. Lift the defer-apply
                        // window here. The `!releaseAnimationLaunched`
                        // guard avoids double-firing on the normal path.
                        if (carouselClaimed && !releaseAnimationLaunched) {
                            currentOnCarouselGestureEnded()
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
                    // Latch: if a dock reorder was active at any event during
                    // this gesture, suppress the swipe-down/up dispatch on
                    // release. Reading the final state alone is unsafe — the
                    // dock fires onDragEnd during the up event's Main pass,
                    // so by the time this Final-pass post-loop check runs,
                    // isDockDraggingState.value has already flipped back to
                    // false. Matters for diagonal drags that resolve as
                    // VerticalLauncher.
                    var dockDraggedDuringGesture = false
                    // Same latch shape for hosted widgets: a scrollable widget
                    // descendant (RemoteViews ListView, StackView, etc.) calls
                    // `requestDisallowInterceptTouchEvent(true)` mid-gesture,
                    // and `LauncherAppWidgetHostView` flips
                    // `isWidgetScrollingState` accordingly. Without this latch a
                    // vertical drag inside a widget would fall through to
                    // `swipeDownDispatch` and expand the system notification
                    // shade.
                    var widgetScrolledDuringGesture = false
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPointerInputChange(downChange)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { change ->
                            val delta = change.positionChangeIgnoreConsumed()
                            rawDragX += delta.x
                            rawDragY += delta.y
                            velocityTracker.addPointerInputChange(change)
                            if (isDockDraggingState.value) {
                                dockDraggedDuringGesture = true
                            }
                            if (isWidgetScrollingState.value) {
                                widgetScrolledDuringGesture = true
                            }
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

                    if (owner != LauncherGestureOwner.VerticalLauncher ||
                        startedInHomeAppList ||
                        dockDraggedDuringGesture ||
                        widgetScrolledDuringGesture
                    ) {
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
        val statePage = destination.toLauncherPage()
        // `carouselResyncTick` re-runs this sync whenever an animation job
        // completes, closing the windows in which a destination change lands
        // while the effect's bail conditions below hold (snap-back in flight,
        // an external settle animating) and would otherwise be dropped
        // permanently — leaving the carousel showing a different page than
        // `state.destination` and the claim gate refusing every swipe.
        LaunchedEffect(destination, pageWidthPx, isAgendaEnabled, widgetPageCount, carouselResyncTick) {
            val newConfig = CarouselPageConfig(widgetPageCount = widgetPageCount, isAgendaEnabled = isAgendaEnabled)
            if (carouselPageConfig != newConfig) {
                currentPage = LauncherScreen.reanchoredCarouselPage(
                    currentPage = currentPage,
                    oldWidgetPageCount = carouselPageConfig.widgetPageCount,
                    newWidgetPageCount = newConfig.widgetPageCount,
                    oldIsAgendaEnabled = carouselPageConfig.isAgendaEnabled,
                    newIsAgendaEnabled = newConfig.isAgendaEnabled,
                )
                carouselPageConfig = newConfig
            }
            when (val transition = carouselTransition) {
                is CarouselTransitionState.AwaitingAck -> {
                    if (statePage == transition.expectedPage) {
                        allowSwipeWithUnackedScreen = false
                        setCarouselTransition(CarouselTransitionState.Idle)
                        playQueuedSettleSwipe(transition.settledPage, pageWidthPx)
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
            if (statePage == LauncherScreen.fromCarouselPage(currentPage, widgetPageCount, isAgendaEnabled)) {
                allowSwipeWithUnackedScreen = false
            }
            val targetPage = LauncherScreen.closestCarouselPage(
                currentPage = currentPage,
                page = statePage,
                widgetPageCount = widgetPageCount,
                isAgendaEnabled = isAgendaEnabled,
            )
            LauncherDebugLog.event(
                "SwipeNavigationBox external page=$statePage settledPage=$currentPage targetPage=$targetPage",
            )
            if (targetPage != currentPage) {
                val startPage = currentPage
                allowSwipeWithUnackedScreen = false
                setCarouselTransition(CarouselTransitionState.ExternalAnimating(targetPage, statePage))
                trackCarouselAnimationJob(coroutineScope.launch {
                    val targetOffsetPx = if (targetPage > startPage) -pageWidthPx else pageWidthPx
                    animateCarouselOffsetTo(targetOffsetPx)
                    currentPage = targetPage
                    carouselOffsetPx = 0f
                    setCarouselTransition(CarouselTransitionState.Idle)
                    playQueuedSettleSwipe(targetPage, pageWidthPx)
                })
            } else {
                setCarouselTransition(CarouselTransitionState.Idle)
            }
        }
        LaunchedEffect(currentPage) {
            LauncherDebugLog.event(
                "SwipeNavigationBox settledPage=$currentPage " +
                    "page=${LauncherScreen.fromCarouselPage(currentPage, widgetPageCount, isAgendaEnabled)}",
            )
        }
        // Sign of the carousel translation as -1 / 0 / +1, behind
        // derivedStateOf so the slot loop below only recomposes when the
        // direction of travel changes (gesture start, reversal, settle) —
        // never per dragged frame. Positive translation reveals the
        // previous (-1) page.
        val revealDirection by remember {
            derivedStateOf {
                when {
                    carouselOffsetPx > 0f -> 1
                    carouselOffsetPx < 0f -> -1
                    else -> 0
                }
            }
        }
        // Assign each destination to at most one slot. With only two visible
        // pages (agenda disabled + a single widget page) floorMod aliases the
        // -1 and +1 slots to the *same* destination; composing it in both
        // slots created duplicate page compositions — two AppWidgetHostViews
        // per widget, and the platform delivers RemoteViews updates only to
        // the most recently created one, so wrapping around in one direction
        // revealed a frozen copy (and a duplicated Home ran two keyboard
        // focus effects). The center slot always owns its destination;
        // between the two neighbors, the side being revealed by the current
        // drag direction wins so the copy the user is about to see is the
        // live one. At idle the +1 slot is the canonical owner; if a drag
        // starts the other way, the destination key below moves the subtree
        // across slots without recreating its views.
        val preferredNeighbor = if (revealDirection > 0) currentPage - 1 else currentPage + 1
        val otherNeighbor = if (revealDirection > 0) currentPage + 1 else currentPage - 1
        val slotAssignments = buildList<Pair<Int, LauncherPage>> {
            val taken = mutableSetOf<LauncherPage>()
            listOf(currentPage, preferredNeighbor, otherNeighbor).forEach { page ->
                val launcherPage = LauncherScreen.fromCarouselPage(page, widgetPageCount, isAgendaEnabled)
                if (taken.add(launcherPage)) add(page to launcherPage)
            }
        }
        // The virtual page a transition is animating toward (settled page while
        // awaiting its ack), or null when idle. A page matching this is sliding
        // into view, so it can present its visible state before the destination
        // commits at settle-end.
        val transitionTargetPage = when (val transition = carouselTransition) {
            is CarouselTransitionState.UserAnimating -> transition.targetPage
            is CarouselTransitionState.ExternalAnimating -> transition.targetPage
            is CarouselTransitionState.AwaitingAck -> transition.settledPage
            CarouselTransitionState.Idle -> null
        }
        slotAssignments.sortedBy { (page, _) -> page }.forEach { (page, launcherPage) ->
            // Keyed by destination, not virtual page index: the -1/+1 slots
            // can alias to one destination (deduplicated above), and a
            // destination key also keeps a page's subtree — host views,
            // scroll state — alive when it changes slots at settle or
            // survives a full wraparound loop.
            key(launcherPage.carouselContentKey()) {
                // Read `carouselOffsetPx` inside the graphicsLayer lambda so the
                // per-frame drag/settle updates run at the layer phase only — if
                // the read happened in the composable body, every frame of a
                // swipe would recompose all three page Boxes.
                val baseTranslationPx = (page - currentPage) * pageWidthPx
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { this.translationX = baseTranslationPx + carouselOffsetPx },
                ) {
                    val isWidgetPage = launcherPage.screen == LauncherScreen.Widgets
                    // Widget pages bypass offscreenPagesReady entirely — they
                    // gate on widgetsWarmed instead, so the AndroidView factory
                    // (and its provider side effects like a weather widget's
                    // location lookup) does not run on cold start for users who
                    // never swipe to widgets. Non-widget pages keep the original
                    // first-frame gate.
                    val offscreenComposeAllowed = if (isWidgetPage) widgetsWarmed else offscreenPagesReady
                    if (page == currentPage || launcherPage == statePage || offscreenComposeAllowed) {
                        // A page is "current or incoming" if it is the settled
                        // page or the one a transition is animating toward, so it
                        // can render its visible state as it slides in (Home's
                        // wallpaper) instead of at settle-end.
                        val isCurrentOrIncoming = page == currentPage || page == transitionTargetPage
                        content(launcherPage, page == currentPage, isCurrentOrIncoming)
                    }
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

internal sealed interface CarouselTransitionState {
    data object Idle : CarouselTransitionState

    data class UserAnimating(
        val targetPage: Int,
        val targetLauncherPage: LauncherPage,
    ) : CarouselTransitionState

    data class ExternalAnimating(
        val targetPage: Int,
        val targetLauncherPage: LauncherPage,
    ) : CarouselTransitionState

    data class AwaitingAck(
        val settledPage: Int,
        val expectedPage: LauncherPage,
    ) : CarouselTransitionState
}

/**
 * The carousel page a launcher-owned horizontal swipe should be queued against
 * when it is released while the carousel is still settling, or `null` when the
 * swipe should just be dropped.
 *
 * The three explicit transition states carry their own in-flight target. The
 * subtle case is [CarouselTransitionState.Idle] *with a settle animation still
 * running* ([isSettleAnimationActive]): that is the tail of an **uncommitted
 * snap-back** — a claimed drag released short of committing, animating back to
 * [settledPage] without ever setting a transition. A flick that lands in that
 * window can't claim (the claim gate rejects an active animation) and, without
 * this, would resolve its settle target to `null` and be silently dropped —
 * the one settle window with no queue-and-replay. Returning [settledPage] there
 * queues the flick so it replays once the snap-back finishes. A truly idle
 * carousel (no animation) still returns `null`: a swipe then would have claimed
 * outright rather than reaching this path.
 */
internal fun carouselSettleTargetPage(
    transition: CarouselTransitionState,
    isSettleAnimationActive: Boolean,
    settledPage: Int,
): Int? = when (transition) {
    is CarouselTransitionState.UserAnimating -> transition.targetPage
    is CarouselTransitionState.ExternalAnimating -> transition.targetPage
    is CarouselTransitionState.AwaitingAck -> transition.settledPage
    CarouselTransitionState.Idle -> if (isSettleAnimationActive) settledPage else null
}

private data class CarouselPageConfig(
    val widgetPageCount: Int,
    val isAgendaEnabled: Boolean,
)

private data class QueuedSettleSwipe(
    val direction: Int,
    val settleTargetPage: Int,
)

internal enum class LauncherGestureOwner {
    Undecided,
    ChildScrollable,
    HorizontalLauncher,
    VerticalLauncher,
}

/**
 * The on-screen icon strip of the open recents bar together with a live query
 * of whether it can still scroll in a given finger direction. The carousel
 * uses this to hand a horizontal drag that starts on the strip to the bar's
 * own scroll instead of paging — but only the strip itself, not the card
 * padding around it, so the padding stays page territory.
 *
 * [boundsInRoot] is the *viewport* (the visible strip), not the scrolled
 * content, so it stays put under the finger as the row scrolls.
 */
internal class BarScrollRegion(
    val boundsInRoot: Rect,
    /**
     * Whether the strip can still scroll given the raw horizontal finger delta
     * (negative = finger moving left). False once the strip is at its edge in
     * that direction, so a swipe past the edge falls through to paging.
     */
    val canScrollInDirection: (rawDragX: Float) -> Boolean,
)

/**
 * Whether a bottom bar's icon strip can still scroll in the finger's direction.
 * Applies the same `overflowSlopPx` the chevrons and pin-to-end use, so a row
 * that only overflows by a rounding pixel (`maxValue <= overflowSlopPx`) counts
 * as non-scrollable and pages instead of reserving a drag it can barely move.
 * In LTR a finger moving left (negative delta) scrolls toward the content's end;
 * `horizontalScroll` reverses that drag-to-scroll mapping under RTL, so the
 * finger sign flips. The two branches mirror showEndChevron / showStartChevron.
 */
internal fun barStripCanScrollInDirection(
    rawDragX: Float,
    scrollValue: Int,
    scrollMaxValue: Int,
    overflowSlopPx: Int,
    isRtl: Boolean,
): Boolean {
    if (scrollMaxValue <= overflowSlopPx) return false
    val scrollsTowardEnd = if (isRtl) rawDragX > 0f else rawDragX < 0f
    return if (scrollsTowardEnd) {
        scrollValue < scrollMaxValue - overflowSlopPx
    } else {
        scrollValue > overflowSlopPx
    }
}

/**
 * Decide whether a horizontal launcher drag belongs to an open bottom bar's
 * horizontal scroll instead of the carousel. Reserved only when an overflowing
 * bar is open, the drag began on its visible strip, and the strip can still
 * scroll the way the finger is moving — otherwise the carousel pages, including
 * when the strip is already at its edge in that direction.
 */
internal fun shouldReserveGestureForBar(
    isBarOpen: Boolean,
    region: BarScrollRegion?,
    downPosition: Offset,
    rawDragX: Float,
): Boolean =
    isBarOpen &&
        region != null &&
        region.boundsInRoot.contains(downPosition) &&
        region.canScrollInDirection(rawDragX)

internal fun resolveLauncherGestureOwner(
    rawDragX: Float,
    rawDragY: Float,
    consumedDragX: Float,
    consumedDragY: Float,
    touchSlopPx: Float,
): LauncherGestureOwner {
    val absX = abs(rawDragX)
    val absY = abs(rawDragY)
    // A nested scrollable (the recents bar's horizontalScroll, the
    // Home app list, a hosted widget) only reports consumption *after* it has
    // cleared its own touch slop, so its consumed delta trails the raw finger
    // movement by roughly one touch slop. If the launcher claimed the gesture
    // the instant raw movement crossed a single touch slop, it would win that
    // race every time and steal a drag that belongs to the child — the child
    // would scroll a few px before the steal while the carousel (or vertical
    // pull) also followed the finger, so both moved at once. That is the
    // "scrolls the bar slightly but swipes the page much more" bug.
    //
    // Two thresholds break the race:
    //  - childClaimSlop (half a touch slop): the moment a child shows it has
    //    consumed on the dominant axis, the whole gesture stays with the child,
    //    even if it later reaches its scroll edge mid-drag.
    //  - launcherClaimSlop (two touch slops): the launcher waits this far before
    //    it claims. That is past the ~1.5 touch slops a scrolling child needs to
    //    push its consumed delta beyond childClaimSlop, so a child that is going
    //    to scroll wins first. A drag over non-scrollable space (empty Home, a
    //    non-scrollable card, or a scrollable row already at its edge) never
    //    reports consumption, so the launcher still claims once raw movement
    //    reaches launcherClaimSlop.
    val childClaimSlop = touchSlopPx / 2f
    val launcherClaimSlop = touchSlopPx * 2f
    return when {
        absX > absY && abs(consumedDragX) > childClaimSlop -> LauncherGestureOwner.ChildScrollable
        absY > absX && abs(consumedDragY) > childClaimSlop -> LauncherGestureOwner.ChildScrollable
        absX <= launcherClaimSlop && absY <= launcherClaimSlop -> LauncherGestureOwner.Undecided
        absX > absY -> LauncherGestureOwner.HorizontalLauncher
        absY > absX -> LauncherGestureOwner.VerticalLauncher
        else -> LauncherGestureOwner.Undecided
    }
}
