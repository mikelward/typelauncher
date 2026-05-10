package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import kotlin.math.max
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

// Debounce window applied before adopting an `imeAnimationTarget` reading as
// the entry's typing geometry. Multi-stage IME opens (e.g. the suggestion
// strip animating in then collapsing) can transiently report a larger target
// than the keyboard ultimately settles at; persisting/locking that peak
// leaves Home's bottom padding too tall for the rest of the entry. Since
// each new value re-keys the LaunchedEffect and cancels the pending delay,
// only a target that has been stable for this long is treated as authoritative.
private const val IME_TARGET_DEBOUNCE_MS = 250L

// Debounce window applied before allowing a smaller settled IME reading to
// shrink the entry's cached keyboard reservation. Within an entry the cache
// is grow-biased to prevent secondary-tray toggles and IME open animations
// from reflowing Home; the shrink path is gated on a separate, longer window
// so a transient inset dip during, say, an IME layout swap cannot pull the
// reservation down before the keyboard settles back. Tuned looser than the
// growth debounce because growth is far more disruptive than a one-off
// missed shrink.
private const val IME_SHRINK_DEBOUNCE_MS = 600L

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
    onRequestCalendarPermission: () -> Unit,
    onRequestDefaultLauncher: () -> Unit,
    onSwipeDown: () -> Unit,
    onStartPlayUpdate: () -> Unit = {},
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
        onReorderDock = viewModel::reorderDockedApps,
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
        onShowDockedAppsInListChanged = viewModel::setShowDockedAppsInList,
        onDockVisibleIconCountChanged = viewModel::setDockVisibleIconCount,
        onAppListSortOrderChanged = viewModel::setAppListSortOrder,
        onKeyboardAutoShownChanged = viewModel::setKeyboardAutoShown,
        onAgendaEnabledChanged = viewModel::setAgendaEnabled,
        onThemeModeChanged = viewModel::setThemeMode,
        onShowAgenda = viewModel::showAgenda,
        onShowWidgets = viewModel::showWidgets,
        onShowHome = viewModel::showHome,
        onHomeReady = viewModel::onHomeReady,
        onSetRecentsOpen = viewModel::setRecentsOpen,
        onSetNotificationBarOpen = viewModel::setNotificationBarOpen,
        onRequestShowKeyboard = viewModel::requestShowKeyboard,
        onKeyboardReservationChanged = viewModel::setKeyboardReservation,
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
    onReorderDock: (String, Int, Int) -> Unit = { _, _, _ -> },
    onResetRank: (InstalledApp) -> Unit,
    onRenameApp: (InstalledApp, String) -> Unit,
    onSetAppIconOverride: (InstalledApp) -> Unit = {},
    onClearAppIconOverride: (InstalledApp) -> Unit = {},
    onSetAppBadge: (InstalledApp, String?) -> Unit = { _, _ -> },
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
    onShowDockedAppsInListChanged: (Boolean) -> Unit = {},
    onDockVisibleIconCountChanged: (Int) -> Unit,
    onAppListSortOrderChanged: (AppListSortOrder) -> Unit,
    onKeyboardAutoShownChanged: (Boolean) -> Unit = {},
    onAgendaEnabledChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeMode) -> Unit = {},
    onShowAgenda: () -> Unit,
    onShowWidgets: (Int) -> Unit,
    onShowHome: () -> Unit,
    onHomeReady: () -> Unit = {},
    onSetRecentsOpen: (Boolean) -> Unit = {},
    onSetNotificationBarOpen: (Boolean) -> Unit = {},
    onRequestShowKeyboard: () -> Unit = {},
    onKeyboardReservationChanged: (KeyboardReservation) -> Unit = {},
    keyboardShowRequests: SharedFlow<Unit> = MutableSharedFlow(),
    onRequestNotificationAccess: () -> Unit = {},
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    onAddWidget: (WidgetAddRequest) -> Unit,
    onDismissWidgetPicker: () -> Unit,
    onSelectWidget: (WidgetProvider) -> Unit,
    onRemoveWidget: (Int) -> Unit,
    onResizeWidget: (widgetId: Int, heightDp: Int) -> Unit = { _, _ -> },
    onRequestCalendarPermission: () -> Unit,
    onOpenAgendaEvent: (AgendaEvent) -> Unit,
    onSwipeDown: () -> Unit = {},
    searchPlaceholderSuffix: String = BuildConfig.SEARCH_PLACEHOLDER_SUFFIX,
) {
    LaunchedEffect(state.destination, state.isSettingsOpen, state.isAppListIconOnly) {
        LauncherDebugLog.event("TypeLauncherApp render target=${if (state.isSettingsOpen) "Settings" else state.destination}")
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
        // Debounce `imeAnimationTarget` before persisting it as the cached
        // reservation. A rapid carousel double-swipe (Home → Widgets → Home,
        // second swipe arriving before Widgets settles) drives a hide-then-
        // show keyboard sequence whose intermediate `imeAnimationTarget`
        // values can briefly land above the keyboard's settled height; the
        // growth-only adoption LaunchedEffect above would then lock that peak
        // into `entryKeyboardBottomPx` for the rest of the entry. Each new
        // value re-keys this LaunchedEffect and cancels the pending delay, so
        // only a target that has held still for the debounce window reaches
        // `state.keyboardReservation`.
        LaunchedEffect(imeTargetBottomPx, currentConfigFingerprint, imeVisible) {
            if (imeTargetBottomPx <= currentConfigFingerprint.navBottomPx) return@LaunchedEffect
            // Growth and first-write path: settle on a stable target.
            delay(IME_TARGET_DEBOUNCE_MS)
            val growthCandidate = KeyboardReservation(
                bottomPx = imeTargetBottomPx,
                configFingerprint = currentConfigFingerprint,
                source = if (imeVisible) {
                    KeyboardReservationSource.VisibleIme
                } else {
                    KeyboardReservationSource.AnimationTarget
                },
            )
            val current = currentReservation
            // Always overwrite on a configuration change so a stale
            // fingerprint cannot survive. Otherwise grow-only at this
            // shorter debounce — shrinks go through the longer
            // [IME_SHRINK_DEBOUNCE_MS] gate below.
            val configChanged = current.configFingerprint != currentConfigFingerprint
            val grew = imeTargetBottomPx > current.bottomPx
            val sourceUpgraded = imeTargetBottomPx == current.bottomPx &&
                growthCandidate.source == KeyboardReservationSource.VisibleIme &&
                current.source == KeyboardReservationSource.AnimationTarget
            if (configChanged || grew || sourceUpgraded) {
                onKeyboardReservationChanged(growthCandidate)
            }
        }
        // Shrink-path persistence: only when the IME is actually visible
        // (so the reading is ground-truthed, not just a settling target),
        // and only after a longer debounce window so a transient inset
        // dip during, say, an IME layout swap cannot pull the persisted
        // value down before the keyboard settles back.
        LaunchedEffect(imeBottomPx, imeVisible, currentConfigFingerprint) {
            if (!imeVisible) return@LaunchedEffect
            if (imeBottomPx <= currentConfigFingerprint.navBottomPx) return@LaunchedEffect
            val current = currentReservation
            val sameConfig = current.configFingerprint == currentConfigFingerprint
            if (sameConfig && imeBottomPx >= current.bottomPx) return@LaunchedEffect
            delay(IME_SHRINK_DEBOUNCE_MS)
            onKeyboardReservationChanged(
                KeyboardReservation(
                    bottomPx = imeBottomPx,
                    configFingerprint = currentConfigFingerprint,
                    source = KeyboardReservationSource.VisibleIme,
                ),
            )
        }
        val stableTypingGeometryAvailable = !state.isSettingsOpen &&
            state.isKeyboardAutoShown &&
            entryKeyboardBottomPx > navBottomPx
        val shouldUseTypingGeometry = stableTypingGeometryAvailable
        val keyboardReserveSource: String
        val keyboardBottomPx = when {
            shouldUseTypingGeometry -> {
                keyboardReserveSource = "typingCache"
                entryKeyboardBottomPx
            }
            imeTargetBottomPx > navBottomPx -> {
                keyboardReserveSource = "target"
                imeTargetBottomPx
            }
            imeVisible -> {
                keyboardReserveSource = "animatedIme"
                imeBottomPx
            }
            else -> {
                keyboardReserveSource = "none"
                0
            }
        }
        val keyboardReservationPx = max(keyboardBottomPx - navBottomPx, 0)
        val keyboardReservationDp = with(density) { keyboardReservationPx.toDp() }
        val routeSecondaryBarsToKeyboardTray = stableTypingGeometryAvailable && keyboardReservationPx > 0
        var hasSeenImeForHomeEntry by remember(state.destination, state.isSettingsOpen, state.isKeyboardAutoShown) {
            mutableStateOf(!state.isKeyboardAutoShown)
        }
        var autoKeyboardWaitElapsed by remember(state.destination, state.isSettingsOpen, state.isKeyboardAutoShown) {
            mutableStateOf(!state.isKeyboardAutoShown)
        }
        LaunchedEffect(routeSecondaryBarsToKeyboardTray, imeVisible) {
            if (routeSecondaryBarsToKeyboardTray && imeVisible) {
                hasSeenImeForHomeEntry = true
            }
        }
        LaunchedEffect(routeSecondaryBarsToKeyboardTray, hasSeenImeForHomeEntry, autoKeyboardWaitElapsed) {
            if (routeSecondaryBarsToKeyboardTray && !hasSeenImeForHomeEntry && !autoKeyboardWaitElapsed) {
                delay(HOME_READY_IME_TIMEOUT_MS)
                autoKeyboardWaitElapsed = true
            }
        }
        val waitingForAutoKeyboard = routeSecondaryBarsToKeyboardTray &&
            state.isKeyboardAutoShown &&
            !hasSeenImeForHomeEntry &&
            !autoKeyboardWaitElapsed
        val forceShowSecondaryBars = state.isNotificationBarOpen || state.isRecentsOpen
        // While the carousel is animating away from Home (or back into it), the
        // soft keyboard has already been asked to hide but `state.destination`
        // is still `Home` until the animation acks. Without this gate the tray
        // would render for the duration of the carousel animation, then vanish
        // once the page change dispatches — visible as a 220ms jank.
        var isCarouselTransitioning by remember { mutableStateOf(false) }
        val secondaryBarsVisible = routeSecondaryBarsToKeyboardTray &&
            state.destination is LauncherDestination.Home &&
            !imeVisible &&
            !isCarouselTransitioning &&
            (!waitingForAutoKeyboard || forceShowSecondaryBars)
        LaunchedEffect(
            state.destination,
            keyboardReserveSource,
            keyboardReservationPx,
            imeVisible,
            imeBottomPx,
            imeTargetBottomPx,
            entryKeyboardBottomPx,
            navBottomPx,
        ) {
            LauncherDebugLog.event(
                "KeyboardReservation destination=${state.destination} source=$keyboardReserveSource " +
                    "reservePx=$keyboardReservationPx imeVisible=$imeVisible imeBottomPx=$imeBottomPx " +
                    "imeTargetBottomPx=$imeTargetBottomPx entryKeyboardBottomPx=$entryKeyboardBottomPx navBottomPx=$navBottomPx",
            )
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
                        onAppListIconOnlyChanged = onAppListIconOnlyChanged,
                        onShowDockedAppsInListChanged = onShowDockedAppsInListChanged,
                        onDockVisibleIconCountChanged = onDockVisibleIconCountChanged,
                        onAppListSortOrderChanged = onAppListSortOrderChanged,
                        onKeyboardAutoShownChanged = onKeyboardAutoShownChanged,
                        onAgendaEnabledChanged = onAgendaEnabledChanged,
                        onThemeModeChanged = onThemeModeChanged,
                        onUnhideApp = onUnhideApp,
                        onOpenLauncherAppInfo = onOpenLauncherAppInfo,
                        onOpenPlayUpdate = onOpenPlayUpdate,
                        onDismissPlayUpdate = onDismissPlayUpdate,
                    )
                } else {
                    var homeAppListBoundsInRoot by remember { mutableStateOf<Rect?>(null) }
                    // The carousel's pointerInput has to see this flip within a
                    // single pointer event (Main pass writes from the dock,
                    // Final pass reads from the carousel), so the state is
                    // shared by reference instead of going through a Boolean
                    // prop + rememberUpdatedState — the wrapper there only
                    // updates during recomposition, which is one frame later.
                    val isDockDraggingState = remember { mutableStateOf(false) }
                    SwipeNavigationBox(
                        destination = state.destination,
                        widgetPageCount = state.widgetPages.size,
                        isAgendaEnabled = state.isAgendaEnabled,
                        isSecondaryTrayVisible = secondaryBarsVisible,
                        onShowAgenda = onShowAgenda,
                        onShowWidgets = onShowWidgets,
                        onShowHome = onShowHome,
                        onSetNotificationBarOpen = onSetNotificationBarOpen,
                        onSetRecentsOpen = onSetRecentsOpen,
                        onRequestShowKeyboard = onRequestShowKeyboard,
                        onSwipeDown = onSwipeDown,
                        onCarouselTransitioningChanged = { isCarouselTransitioning = it },
                        appListBoundsInRoot = homeAppListBoundsInRoot,
                        isDockDraggingState = isDockDraggingState,
                        secondaryTray = {
                            if (secondaryBarsVisible) {
                                HomeKeyboardTray(
                                    state = state,
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = innerPadding.calculateBottomPadding())
                                        .height(keyboardReservationDp)
                                        .fillMaxWidth()
                                        .clipToBounds(),
                                    onLaunchApp = onLaunchApp,
                                    onOpenAppInfo = onOpenAppInfo,
                                    onToggleDock = onToggleDock,
                                    onDismissRecent = onDismissRecent,
                                    onDismissNotifications = onDismissNotifications,
                                    onOpenNotificationSettings = onOpenNotificationSettings,
                                    onSetNotificationBarOpen = onSetNotificationBarOpen,
                                    onRequestNotificationAccess = onRequestNotificationAccess,
                                )
                            }
                        },
                    ) { page, isCurrentPage ->
                        when (page.screen) {
                            LauncherScreen.Home -> HomeScreen(
                                state = state,
                                innerPadding = innerPadding,
                                bodyReady = homeBodyReady,
                                primaryBottomPadding = keyboardReservationDp,
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
                                onRenameApp = onRenameApp,
                                onSetAppIconOverride = onSetAppIconOverride,
                                onClearAppIconOverride = onClearAppIconOverride,
                                onSetAppBadge = onSetAppBadge,
                                onHideApp = onHideApp,
                                onDismissRecent = onDismissRecent,
                                onDismissNotifications = onDismissNotifications,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                onOpenSettings = onOpenSettings,
                                onSetNotificationBarOpen = onSetNotificationBarOpen,
                                onRequestNotificationAccess = onRequestNotificationAccess,
                                onAppListBoundsChanged = { homeAppListBoundsInRoot = it },
                                onDockDragChanged = { isDockDraggingState.value = it },
                            )
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
    isSecondaryTrayVisible: Boolean,
    appListBoundsInRoot: Rect?,
    onShowAgenda: () -> Unit,
    onShowWidgets: (Int) -> Unit,
    onShowHome: () -> Unit,
    onSetNotificationBarOpen: (Boolean) -> Unit,
    onSetRecentsOpen: (Boolean) -> Unit,
    onRequestShowKeyboard: () -> Unit,
    onSwipeDown: () -> Unit,
    onCarouselTransitioningChanged: (Boolean) -> Unit = {},
    isDockDraggingState: State<Boolean> = mutableStateOf(false),
    secondaryTray: @Composable BoxScope.() -> Unit = {},
    content: @Composable (LauncherPage, Boolean) -> Unit,
) {
    // A pointer sequence locks once, shortly after touch slop, to either the
    // child scrollable that consumed movement at gesture start or to a
    // launcher-level action. Reaching a child edge mid-gesture does not hand
    // the same drag to the carousel/pull handlers; the next gesture can claim
    // from that already-at-edge state.
    val currentScreen by rememberUpdatedState(destination.screen)
    val currentLauncherPage by rememberUpdatedState(destination.toLauncherPage())
    val currentSecondaryTrayVisible by rememberUpdatedState(isSecondaryTrayVisible)
    val currentSetBarOpen by rememberUpdatedState(onSetNotificationBarOpen)
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
    val swipeDownDispatch = remember<() -> Unit> {
        {
            if (currentScreen == LauncherScreen.Home) {
                if (currentSecondaryTrayVisible) {
                    currentOnSwipeDown()
                } else {
                    currentKeyboard?.hide()
                    currentSetBarOpen(true)
                }
            } else {
                currentOnSwipeDown()
            }
        }
    }
    val swipeUpDispatch = remember<() -> Unit> {
        {
            // Pull-up only does anything on Home. If the notification bar is
            // visible in the keyboard tray, the gesture asks the search field
            // to grab focus and re-show the soft keyboard.
            if (currentScreen == LauncherScreen.Home) {
                currentRequestShowKeyboard()
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
    var carouselTransition by remember { mutableStateOf<CarouselTransitionState>(CarouselTransitionState.Idle) }
    var allowSwipeWithUnackedScreen by remember { mutableStateOf(false) }
    var queuedSettleSwipe by remember { mutableStateOf<QueuedSettleSwipe?>(null) }
    val currentOnCarouselTransitioningChanged by rememberUpdatedState(onCarouselTransitioningChanged)
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
        setCarouselTransition(CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage))
        hideKeyboardForCarouselPage(targetLauncherPage.screen)
        carouselAnimationJob = coroutineScope.launch {
            val targetOffsetPx = if (targetPage > settledPage) -pageWidthPx else pageWidthPx
            animateCarouselOffsetTo(targetOffsetPx)
            if (carouselTransition == CarouselTransitionState.UserAnimating(targetPage, targetLauncherPage)) {
                currentPage = targetPage
                carouselOffsetPx = 0f
                awaitPageAck(targetPage, targetLauncherPage)
            } else {
                carouselOffsetPx = 0f
            }
        }
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
                    var carouselClaimed = false
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
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPointerInputChange(downChange)
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
                            if (!carouselClaimed &&
                                owner == LauncherGestureOwner.HorizontalLauncher &&
                                !dockDraggedDuringGesture
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
                                if (canStartCarouselGesture) {
                                    carouselClaimed = true
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
                            if (carouselClaimed) {
                                val effectiveDragX = rawDragX - anchorRawDragX
                                val nextDisplayedDragX = effectiveDragX.coerceIn(-pageWidthPx, pageWidthPx)
                                carouselOffsetPx = nextDisplayedDragX
                                displayedDragX = nextDisplayedDragX
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (!carouselClaimed) {
                        val settleTargetPage = when (val transition = carouselTransition) {
                            is CarouselTransitionState.UserAnimating -> transition.targetPage
                            is CarouselTransitionState.ExternalAnimating -> transition.targetPage
                            is CarouselTransitionState.AwaitingAck -> transition.settledPage
                            CarouselTransitionState.Idle -> null
                        }
                        if (owner == LauncherGestureOwner.HorizontalLauncher &&
                            !dockDraggedDuringGesture &&
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
                        (distanceCommits || flingCommits)

                    LauncherDebugLog.event(
                        "SwipeNavigationBox horizontal release effectiveDragX=$effectiveDragX rawDragY=$rawDragY " +
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
                    carouselAnimationJob = coroutineScope.launch {
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
                        dockDraggedDuringGesture
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
        LaunchedEffect(destination, pageWidthPx, isAgendaEnabled, widgetPageCount) {
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
                carouselAnimationJob = coroutineScope.launch {
                    val targetOffsetPx = if (targetPage > startPage) -pageWidthPx else pageWidthPx
                    animateCarouselOffsetTo(targetOffsetPx)
                    currentPage = targetPage
                    carouselOffsetPx = 0f
                    setCarouselTransition(CarouselTransitionState.Idle)
                    playQueuedSettleSwipe(targetPage, pageWidthPx)
                }
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
        listOf(currentPage - 1, currentPage, currentPage + 1).forEach { page ->
            val launcherPage = LauncherScreen.fromCarouselPage(page, widgetPageCount, isAgendaEnabled)
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
                if (page == currentPage || launcherPage == statePage || offscreenPagesReady) {
                    content(launcherPage, page == currentPage)
                }
            }
        }
        // The keyboard tray sits inside the carousel's pointerInput surface
        // (rather than as a sibling overlay) so the existing vertical pull-up
        // detector receives gestures that start on the tray. The tray draws
        // last so it stays on top of the page Boxes, and it does not get the
        // pages' graphicsLayer translationX, so it stays put during a
        // horizontal carousel transition.
        secondaryTray()
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
