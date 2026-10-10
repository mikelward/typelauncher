package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.withTimeoutOrNull

/** How long a still finger must stay down before kiosk mode opens Settings. */
internal const val KIOSK_EXIT_HOLD_MS = 2_000L

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
    modifier: Modifier = Modifier,
    // Test seams, forwarded to HomeWidgets.
    providerInfoOverride: ((Int) -> AppWidgetProviderInfo?)? = null,
    createWidgetView: ((Context, Int) -> AppWidgetHostView)? = null,
) {
    KioskImmersiveBars()
    // Arriving from Settings can leave the keyboard up; the display has
    // nothing to type into.
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { keyboard?.hide() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag(KIOSK_SCREEN_TAG)
            // On the parent, so it sees every touch on its way to the widgets.
            .pointerInput(onExitHold) { detectKioskExitHold(onExitHold) },
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
                    .padding(16.dp),
            )
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
