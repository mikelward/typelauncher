package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** How long a still finger must stay down before kiosk mode opens Settings. */
internal const val KIOSK_EXIT_HOLD_MS = 2_000L

/** How often the display re-reads the clock for "Blank at night". */
internal const val KIOSK_BLANK_CHECK_INTERVAL_MS = 30_000L

/**
 * Kiosk mode's Home: Home's widget set and nothing else — no search box, dock,
 * app list, recents or carousel — meant to be glanced at.
 *
 * The widgets render through [HomeWidgets] in its normal (non-editing) mode, so
 * they are the same hosted views Home shows, laid out on the same grid, and
 * they respond to touch as they do on Home. The way out is a still
 * [KIOSK_EXIT_HOLD_MS] hold anywhere, widgets included, which opens Settings;
 * a widget's own long-press is switched off here, haptic included, so the
 * half-second mark of that hold feels like nothing happened. The system bars are hidden
 * while the display shows (a swipe from the edge brings them back briefly).
 *
 * The display shows over the lock screen, so it stays up after a lock; the
 * exit hold then asks for an unlock first, and Settings opens only once it
 * succeeds.
 *
 * "Blank at night" turns the display black, at the window's lowest
 * brightness, inside its nightly window once a quiet minute passes; "Blank
 * when idle" does the same at any hour. The
 * screen stays on rather than off, so the camera (when "Wake up using camera"
 * is on) keeps watching: motion, or a touch, brings the widgets back until the
 * next quiet minute. The touch that ends a blank only does that, so a widget
 * nobody could see is never tapped by accident.
 */
@Composable
internal fun KioskScreen(
    widgetIds: List<Int>,
    isHomeReady: Boolean,
    appWidgetHost: AppWidgetHost?,
    appWidgetManager: AppWidgetManager?,
    widgetHeights: Map<Int, Int>,
    widgetSpans: Map<Int, Int>,
    widgetProviderLabels: Map<Int, String>,
    strandedWidgetIds: Set<Int>,
    workProfileWidgetRefreshToken: Int,
    onExitHold: () -> Unit,
    // "Wake up using camera": dim after a quiet minute, brighten on camera
    // motion or a touch. The caller only passes true with the camera granted.
    dimWhenIdle: Boolean = false,
    // "Blank at night" and its window, in minutes after local midnight.
    blankAtNight: Boolean = false,
    // "Blank when idle": the same blank after any quiet minute, day or night.
    blankWhenIdle: Boolean = false,
    blankStartMinutes: Int = KIOSK_BLANK_DEFAULT_START_MINUTES,
    blankEndMinutes: Int = KIOSK_BLANK_DEFAULT_END_MINUTES,
    modifier: Modifier = Modifier,
    // Test seam: the local time, as minutes after midnight.
    minuteOfDay: () -> Int = { LocalTime.now().let { it.hour * 60 + it.minute } },
    // Test seam: what watches for motion. Production uses the front camera;
    // Robolectric has none, so a test drives onMotion itself.
    motionWatcher: @Composable (onMotion: () -> Unit) -> Unit = { onMotion -> KioskMotionCamera(onMotion) },
    // Test seam: how the exit hold gets past the lock screen. Production asks
    // the system to unlock first when the device is locked.
    unlockThen: (action: () -> Unit) -> Unit = rememberKioskUnlockThen(),
    // Test seams, forwarded to HomeWidgets.
    providerInfoOverride: ((Int) -> AppWidgetProviderInfo?)? = null,
    createWidgetView: ((Context, Int) -> AppWidgetHostView)? = null,
) {
    KioskImmersiveBars()
    KioskOverLockScreen()
    // Arriving from Settings can leave the keyboard up; the display has
    // nothing to type into.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    // The idle timer: dims after a quiet minute, brightens on motion or touch.
    val scope = rememberCoroutineScope()
    val dimmer = remember(scope) { KioskIdleDimmer(scope) }
    val dimmed by dimmer.dimmed.collectAsState()
    // Night blanking needs the same quiet-minute timer as dimming, camera or no.
    if (dimWhenIdle || blankAtNight || blankWhenIdle) {
        DisposableEffect(dimmer) {
            dimmer.start()
            onDispose { dimmer.stop() }
        }
    }
    if (dimWhenIdle) motionWatcher { dimmer.onActivity() }
    val currentMinuteOfDay by rememberUpdatedState(minuteOfDay)
    var inBlankWindow by remember { mutableStateOf(false) }
    LaunchedEffect(blankAtNight, blankStartMinutes, blankEndMinutes) {
        // Re-read the clock every half minute rather than sleeping until the
        // window's edge, so a clock or time-zone change is caught too. The
        // display is on anyway, so the wakeup costs nothing that matters.
        while (true) {
            inBlankWindow = blankAtNight &&
                isInKioskBlankWindow(currentMinuteOfDay(), blankStartMinutes, blankEndMinutes)
            if (!blankAtNight) break
            delay(KIOSK_BLANK_CHECK_INTERVAL_MS)
        }
    }
    val blanked = (blankWhenIdle || inBlankWindow) && dimmed
    KioskWindowBrightness(
        brightness = when {
            blanked -> KIOSK_BLANK_BRIGHTNESS
            dimWhenIdle && dimmed -> KIOSK_DIM_BRIGHTNESS
            else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        },
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(KIOSK_SCREEN_TAG)
            // On the parent, so it sees every touch on its way to the widgets.
            .pointerInput(onExitHold, unlockThen) { detectKioskExitHold { unlockThen(onExitHold) } }
            // Any touch counts as someone being there. Observed, not consumed.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    dimmer.onActivity()
                }
            },
    ) {
        // With no widgets the display is simply empty: HomeWidgets' own empty
        // state is an Add button, and adding belongs to the normal Home.
        if (isHomeReady && widgetIds.isNotEmpty()) {
            HomeWidgets(
                widgetIds = widgetIds,
                isEditing = false,
                isAddingWidget = false,
                isLoadingAvailableWidgets = false,
                availableWidgets = emptyList(),
                appWidgetHost = appWidgetHost,
                appWidgetManager = appWidgetManager,
                widgetHeights = widgetHeights,
                widgetSpans = widgetSpans,
                widgetProviderLabels = widgetProviderLabels,
                strandedWidgetIds = strandedWidgetIds,
                workProfileWidgetRefreshToken = workProfileWidgetRefreshToken,
                isCurrentPage = true,
                onBoundsChanged = {},
                onStartEditing = {},
                onStopEditing = {},
                onAddWidget = {},
                onDismissWidgetPicker = {},
                onSelectWidget = {},
                onRemoveWidget = {},
                onRestoreWidget = {},
                onResizeWidget = { _, _ -> },
                onMoveWidget = { _, _ -> },
                onResizeWidgetSpan = { _, _ -> },
                providerInfoOverride = providerInfoOverride,
                createWidgetView = createWidgetView,
                widgetLongPressEnabled = false,
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp)
                    // A few dp at a time, within that 16 dp margin, so no
                    // pixel shows the same thing all day (OLED burn-in).
                    .kioskPixelShift()
                    // While blanked, nothing but a touch may reach a widget
                    // nobody can see: no screen reader, Switch Access or
                    // keyboard focus. The overlay below takes the touch.
                    .then(if (blanked) Modifier.kioskInertWhileBlank() else Modifier),
            )
        }
        if (blanked) {
            KioskBlankOverlay(onWake = dimmer::onActivity)
        }
    }
}

/**
 * Calls [onHold] once a finger has stayed down, within touch slop, for
 * [KIOSK_EXIT_HOLD_MS]. Watches in the Initial pass without consuming, so the
 * widgets underneath still get every touch; only the tail of a gesture that
 * did open Settings is consumed, so its lift can't also click a widget.
 */
internal suspend fun PointerInputScope.detectKioskExitHold(onHold: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val endedEarly = withTimeoutOrNull(KIOSK_EXIT_HOLD_MS) {
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes
                    .firstOrNull { it.id == down.id } ?: break
                val drift = (change.position - down.position).getDistance()
                if (!change.pressed || drift > viewConfiguration.touchSlop) break
            }
            true
        }
        if (endedEarly != null) return@awaitEachGesture
        onHold()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            if (event.changes.none { it.pressed }) break
        }
    }
}

/**
 * Holds the window at [brightness] — [KIOSK_DIM_BRIGHTNESS] while dimmed,
 * [KIOSK_BLANK_BRIGHTNESS] while blanked — and hands brightness back to the
 * system (the user's own level) on BRIGHTNESS_OVERRIDE_NONE and on leaving
 * the display. A window-only override: the system brightness setting
 * is never changed.
 */
@Composable
private fun KioskWindowBrightness(brightness: Float) {
    val context = LocalContext.current
    DisposableEffect(context, brightness) {
        val window = context.findActivity()?.window
        fun apply(value: Float) {
            window?.attributes = window?.attributes?.also { it.screenBrightness = value }
        }
        apply(brightness)
        LauncherDebugLog.event("kioskBrightness=%s", brightness)
        onDispose { apply(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) }
    }
}

/**
 * The black screen over the widgets while blanked, and the only thing any
 * input can reach: it takes the touch that ends the blank (the parent has
 * already seen it, in the Initial pass, and woken the display), takes keyboard
 * focus from whatever held it so a key wakes rather than activating a widget
 * nobody can see, and is the one target a screen reader or Switch Access can
 * select, whose click wakes.
 */
@Composable
private fun KioskBlankOverlay(onWake: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val wakeLabel = stringResource(R.string.kiosk_blank_wake_action)
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .semantics {
                onClick(label = wakeLabel) {
                    onWake()
                    true
                }
            }
            .onPreviewKeyEvent {
                // Wake on the key going down; swallow the whole press either way.
                if (it.type == KeyEventType.KeyDown) onWake()
                true
            }
            .focusRequester(focusRequester)
            .focusable()
            .testTag(KIOSK_BLANK_TAG),
    )
}

/**
 * Takes the widgets out of the accessibility tree (hosted views included, as
 * they hang off it) and refuses keyboard / D-pad focus into them.
 */
private fun Modifier.kioskInertWhileBlank(): Modifier = this
    .clearAndSetSemantics {}
    .focusProperties { onEnter = { cancelFocusChange() } }
    .focusGroup()

/** Hides the status and navigation bars while composed, restoring them after. */
@Composable
private fun KioskImmersiveBars() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
