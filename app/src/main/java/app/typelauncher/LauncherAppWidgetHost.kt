package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import java.util.WeakHashMap
import java.util.concurrent.Executor
import kotlin.math.abs

private const val WIDGET_SIZE_CACHE_PREFS = "widget_size_cache"
private const val WIDGET_SIZE_KEY_FORMAT = "size:%d"
// Persisted as "WIDTHxHEIGHT" so the format is human-readable in prefs
// dumps and trivial to parse without pulling in JSON.
private const val WIDGET_SIZE_VALUE_FORMAT = "%dx%d"

internal class LauncherAppWidgetHost(
    context: Context,
    hostId: Int,
    // Runs the one-time persisted size-cache read. Injectable so tests can
    // load synchronously or hold the load; production defaults to a
    // background thread because the read blocks on the prefs file parse and
    // this host is constructed in MainActivity.onCreate — the cold-start /
    // first-frame path must not wait on disk.
    cacheLoadExecutor: Executor = Executor { task -> Thread(task, "widget-size-cache-load").start() },
) : AppWidgetHost(context, hostId) {
    // Last size hint sent to each widget via updateAppWidgetSize, in dp.
    // Persisted across process restarts so the very first layout pass on a
    // cold start can short-circuit a redundant binder IPC + provider
    // `onAppWidgetOptionsChanged` wake when the size hasn't changed since
    // last session — typical case, since widget heights are persisted in
    // the WidgetStore and the page width is determined by the device.
    //
    // `getSharedPreferences` only enqueues the file load on a platform
    // background thread; the blocking read (`sizePrefs.all`) happens on
    // [cacheLoadExecutor] in `init`, and the result merges into
    // [cachedSizes] on the main thread. Until the merge lands, a missing
    // entry just degrades to the pre-cache behavior (one redundant size
    // IPC) — widget pages compose well after onCreate, so in practice the
    // merge wins the race.
    private val sizePrefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(WIDGET_SIZE_CACHE_PREFS, Context.MODE_PRIVATE)

    // Main-thread confined (apply / forget / the posted merge below).
    private val cachedSizes: MutableMap<Int, IntPairDp> = mutableMapOf()

    // Widget ids forgotten before the async disk load landed: their persisted
    // entry was already removed, so the merge must not resurrect them.
    private val forgottenBeforeLoad = mutableSetOf<Int>()
    private var diskSizesLoaded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        cacheLoadExecutor.execute {
            val loaded = loadCachedSizes()
            // A synchronous (test) executor is already on the main thread —
            // merge inline so construction-time state is deterministic.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mergeLoadedSizes(loaded)
            } else {
                mainHandler.post { mergeLoadedSizes(loaded) }
            }
        }
    }

    private fun mergeLoadedSizes(loaded: Map<Int, IntPairDp>) {
        for ((widgetId, pair) in loaded) {
            // In-memory entries are fresher: an apply that raced the load has
            // already persisted its newer value too.
            if (widgetId !in cachedSizes && widgetId !in forgottenBeforeLoad) {
                cachedSizes[widgetId] = pair
            }
        }
        forgottenBeforeLoad.clear()
        diskSizesLoaded = true
    }

    /**
     * Calls [AppWidgetHostView.updateAppWidgetSize] only when [widthDp]
     * and [heightDp] differ from the last value cached for [widgetId].
     * Caches and persists the new size on miss.
     *
     * The size hint is fired from [HostedWidgetCard]'s `update` lambda on
     * every layout pass, but the actual measured size rarely changes
     * between passes. Skipping the redundant IPC saves: (a) one binder
     * round-trip into AppWidgetService, (b) a wake of the provider
     * process via `onAppWidgetOptionsChanged`, and (c) any
     * provider-initiated work (re-layout decisions, sometimes data
     * fetches) that the spurious options-changed event would have
     * triggered.
     */
    fun applyAppWidgetSizeIfChanged(
        view: AppWidgetHostView,
        widgetId: Int,
        widthDp: Int,
        heightDp: Int,
    ) {
        val incoming = IntPairDp(widthDp, heightDp)
        val previous = cachedSizes[widgetId]
        if (previous == incoming) return
        view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        cachedSizes[widgetId] = incoming
        sizePrefs.edit().putString(sizeKey(widgetId), incoming.serialize()).apply()
    }

    /**
     * Drop the cache entry for a widget that's been removed. Avoids
     * unbounded growth of the prefs file as users add/remove widgets
     * over the device's lifetime. Before the async disk load lands the
     * in-memory map may not know about a persisted entry yet, so the
     * prefs removal is unconditional in that window (idempotent) and the
     * id is remembered so the merge can't resurrect it.
     */
    fun forgetWidgetSize(widgetId: Int) {
        if (!diskSizesLoaded) forgottenBeforeLoad += widgetId
        if (cachedSizes.remove(widgetId) != null || !diskSizesLoaded) {
            sizePrefs.edit().remove(sizeKey(widgetId)).apply()
        }
    }

    private fun loadCachedSizes(): MutableMap<Int, IntPairDp> {
        val out = mutableMapOf<Int, IntPairDp>()
        for ((rawKey, rawValue) in sizePrefs.all) {
            val widgetId = rawKey.removePrefix("size:").toIntOrNull() ?: continue
            val pair = (rawValue as? String)?.let(IntPairDp::parse) ?: continue
            out[widgetId] = pair
        }
        return out
    }

    @VisibleForTesting
    internal fun cachedSizeForTest(widgetId: Int): IntPairDp? = cachedSizes[widgetId]

    private fun sizeKey(widgetId: Int): String = WIDGET_SIZE_KEY_FORMAT.format(widgetId)

    // Tracks every host view bound to us so flipping `deferRemoteViewsApply`
    // can fan out the queued RemoteViews flush. WeakHashMap because a view
    // detached from the carousel (page removed, host destroyed) shouldn't
    // pin the parent activity. Only ever read/written on the main thread —
    // host view creation, RemoteViews delivery, and the carousel's gesture
    // callbacks all run on the Compose main thread.
    private val views = WeakHashMap<LauncherAppWidgetHostView, Unit>()

    /**
     * While true, every [LauncherAppWidgetHostView.updateAppWidget] call
     * (including the initial one and provider-pushed updates) parks the
     * incoming `RemoteViews` instead of inflating it. Carousel gesture code
     * sets this true at gesture-claim, so the system can wake the provider
     * (background data fetch) while the user drags without paying the
     * UI-thread `RemoteViews.apply()` cost on translate frames. Setting it
     * false flushes any queued RemoteViews on every tracked view, so the
     * fresh content paints in one pass right after settle.
     *
     * The setter only flushes — it does not start/stop listening. That
     * keeps this surface idempotent and decoupled from activity lifecycle:
     * a stuck `true` is benign (next gesture clears it; the worst-case
     * visible effect is one frame of stale content), and Compose recompose
     * dispatchers never run with the activity below STARTED so resume
     * races against `MainActivity`'s listener policy don't apply.
     */
    var deferRemoteViewsApply: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) flushDeferredRemoteViews()
        }

    /**
     * Invoked when a hosted widget's descendant view claims the scroll
     * gesture via [android.view.ViewParent.requestDisallowInterceptTouchEvent]
     * (true), or at any gesture boundary the host view observes from
     * `dispatchTouchEvent` (`ACTION_DOWN`/`ACTION_UP`/`ACTION_CANCEL`,
     * dispatched as false). The carousel registers a listener to mirror
     * the value into a Compose `MutableState` so its pointer-input
     * arbitrator suppresses page swipes and notification-shade pull
     * while the widget is consuming touches, and resets at gesture end.
     *
     * Mirrors the dock's `isDockDraggingState` signalling: a child view
     * inside an `AndroidView` consumes touches at the View layer, but the
     * carousel reads raw deltas via `positionChangeIgnoreConsumed` and
     * cannot see View-level consumption (Compose nested scroll only fires
     * for Compose scrollables). A dedicated channel is the same fix that
     * unblocked the dock long-press-drag-vs-carousel collision.
     */
    var onChildScrollChange: ((Boolean) -> Unit)? = null

    internal fun registerView(view: LauncherAppWidgetHostView) {
        views[view] = Unit
    }

    private fun flushDeferredRemoteViews() {
        // Snapshot keys so a flush that triggers a recompose registering a
        // new view doesn't mutate the iteration target.
        views.keys.toList().forEach { it.applyDeferredRemoteViews() }
    }

    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = LauncherAppWidgetHostView(context, host = this)
}

internal open class LauncherAppWidgetHostView(
    context: Context,
    private val host: LauncherAppWidgetHost,
) : AppWidgetHostView(context) {
    init {
        host.registerView(this)
    }

    private var onWidgetLongPress: (() -> Unit)? = null
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var hasPerformedLongPress = false
    // Diagnostic-only, no behavioral role: tracks whether the long-press
    // timer is currently armed so its cancellation is logged exactly once
    // per gesture instead of on every ACTION_MOVE past slop (a long drag
    // fires many of those, which would spam the ring buffer and evict the
    // armed/gesture-start lines this logging exists to preserve).
    private var longPressTimerArmedForLogging = false
    // Holds the latest RemoteViews delivered while the host's defer flag was
    // set. Overwriting on every push is intentional — only the latest needs
    // to paint when the deferral lifts; intermediate updates are typically
    // the same content with newer timestamps. The companion `hasPendingUpdate`
    // disambiguates "queued a null update" (provider sent null to switch the
    // view back to its default/error content — a meaningful operation that
    // must still flush) from "no update queued."
    @VisibleForTesting
    internal var pendingRemoteViews: RemoteViews? = null
        private set
    @VisibleForTesting
    internal var hasPendingUpdate: Boolean = false
        private set
    private val checkLongPress = Runnable {
        if (parent != null && !hasPerformedLongPress) {
            hasPerformedLongPress = true
            longPressTimerArmedForLogging = false
            // Diagnostic for #513's follow-up: pins down whether the menu is
            // opened by a genuinely held finger or by this timer surviving a
            // swipe it should have been cancelled by. Cheap (once per fired
            // long-press, not per frame) and captured by the in-app bug
            // report's log ring buffer, so a report from a device without
            // logcat access still carries the timing.
            LauncherDebugLog.event("LauncherAppWidgetHostView checkLongPress fired widgetId=$appWidgetId")
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onWidgetLongPress?.invoke()
        }
    }

    fun setOnWidgetLongPressListener(listener: (() -> Unit)?) {
        onWidgetLongPress = listener
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }

    final override fun updateAppWidget(remoteViews: RemoteViews?) {
        if (host.deferRemoteViewsApply) {
            // Park the latest RemoteViews. The provider's background fetch
            // already ran in its own process; we just postpone the
            // UI-thread `apply()` until the carousel has settled. Tracked
            // via `hasPendingUpdate` because `null` is itself a meaningful
            // value — it tells the host view to render the default/error
            // content — and must flush like any other update.
            pendingRemoteViews = remoteViews
            hasPendingUpdate = true
            return
        }
        applyRemoteViewsImmediate(remoteViews)
    }

    fun applyDeferredRemoteViews() {
        if (!hasPendingUpdate) return
        val queued = pendingRemoteViews
        pendingRemoteViews = null
        hasPendingUpdate = false
        applyRemoteViewsImmediate(queued)
    }

    /**
     * Hits the base [AppWidgetHostView] inflate path. Open so tests can
     * verify call ordering without driving real provider RemoteViews
     * through the platform's inflate machinery.
     */
    @VisibleForTesting
    protected open fun applyRemoteViewsImmediate(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                hasPerformedLongPress = false
                removeCallbacks(checkLongPress)
                postDelayed(checkLongPress, longPressTimeoutMs)
                longPressTimerArmedForLogging = true
                // See checkLongPress's doc: diagnostic for #513's follow-up.
                LauncherDebugLog.event("LauncherAppWidgetHostView longPressTimer armed widgetId=$appWidgetId")
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    removeCallbacks(checkLongPress)
                    // Gated on longPressTimerArmedForLogging: this branch stays
                    // true for every remaining ACTION_MOVE in the gesture once
                    // slop is first crossed, so an ungated log call would fire
                    // once per move sample for the rest of a drag.
                    if (longPressTimerArmedForLogging) {
                        longPressTimerArmedForLogging = false
                        LauncherDebugLog.event(
                            "LauncherAppWidgetHostView longPressTimer cancelled(slop) widgetId=$appWidgetId",
                        )
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(checkLongPress)
                if (longPressTimerArmedForLogging) {
                    longPressTimerArmedForLogging = false
                    LauncherDebugLog.event(
                        "LauncherAppWidgetHostView longPressTimer cancelled(intercept " +
                            "action=${ev.actionMasked}) widgetId=$appWidgetId",
                    )
                }
            }
        }
        if (hasPerformedLongPress) {
            removeCallbacks(checkLongPress)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    /**
     * Reset the widget-scroll latch at every gesture boundary. We do this
     * from `dispatchTouchEvent` rather than `onInterceptTouchEvent`
     * because once a scrollable descendant has called
     * `requestDisallowInterceptTouchEvent(true)`, the framework stops
     * calling `onInterceptTouchEvent` for the rest of that gesture —
     * including the eventual `ACTION_UP`/`ACTION_CANCEL`. Without a
     * `dispatchTouchEvent` hook the latch would stick true past gesture
     * end and suppress the very next, unrelated gesture (which starts
     * outside the widget and therefore never re-enters this host view).
     * `ACTION_DOWN` is included so a gesture that starts inside another
     * widget — or anywhere the framework happens to clear the disallow
     * flag and route DOWN here — clears any residual state too.
     *
     * The long-press timer armed in [onInterceptTouchEvent] must be
     * canceled at gesture boundaries here too, for the same reason: with
     * intercept skipped for the rest of the gesture, the UP/CANCEL branch
     * there never runs. The primary disarm lives in
     * [requestDisallowInterceptTouchEvent] (the moment a child takes the
     * gesture); this one is the backstop so no timer ever survives past
     * gesture end.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> host.onChildScrollChange?.invoke(false)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                host.onChildScrollChange?.invoke(false)
                removeCallbacks(checkLongPress)
                // This is the path a Compose ancestor (the carousel) that has
                // consumed the gesture elsewhere is expected to reach via a
                // synthetic ACTION_CANCEL, distinct from a real finger lift —
                // see checkLongPress's doc for why this line exists. Gated on
                // longPressTimerArmedForLogging (see the onInterceptTouchEvent
                // MOVE branch's doc) so this only logs when it actually
                // cancelled a still-armed timer.
                if (ev.actionMasked == MotionEvent.ACTION_CANCEL && longPressTimerArmedForLogging) {
                    longPressTimerArmedForLogging = false
                    LauncherDebugLog.event(
                        "LauncherAppWidgetHostView longPressTimer cancelled(dispatchCancel) widgetId=$appWidgetId",
                    )
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Scrollable views inside the hosted widget — RemoteViews-backed
     * `ListView`, `StackView`, `GridView`, etc. — call this on their
     * parent chain once they've crossed slop to tell ancestors to stop
     * trying to intercept the gesture. We forward the signal to the host
     * so the launcher's pointer-input arbitrator can suppress its own
     * horizontal page swipe and vertical pull-down-shade dispatch, then
     * delegate to `super` so the framework's intercept chain still works.
     * The release of the latch is owned by `dispatchTouchEvent` —
     * `requestDisallowInterceptTouchEvent(false)` is a routine call any
     * ancestor can make and is not a reliable "gesture is ending" signal.
     */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept) {
            host.onChildScrollChange?.invoke(true)
            // The descendant owns the gesture from here on and the framework
            // skips onInterceptTouchEvent — including its MOVE-past-slop and
            // UP/CANCEL cancellation branches — for the rest of it. A timer
            // armed at DOWN would fire while the finger is still down on the
            // child (catch a fling, then hold or drag: AbsListView claims the
            // gesture at DOWN) or after a quick lift, popping the widget
            // actions menu with no long press on the widget itself. Disarm it
            // the moment the child takes over. Gated on
            // longPressTimerArmedForLogging (see the onInterceptTouchEvent
            // MOVE branch's doc): a scrollable descendant is free to call
            // this repeatedly during one gesture, and only the first call
            // that actually cancels a still-armed timer is worth a log line.
            removeCallbacks(checkLongPress)
            if (longPressTimerArmedForLogging) {
                longPressTimerArmedForLogging = false
                LauncherDebugLog.event(
                    "LauncherAppWidgetHostView longPressTimer cancelled(disallowIntercept) widgetId=$appWidgetId",
                )
            }
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        hasPerformedLongPress = false
        removeCallbacks(checkLongPress)
    }
}

/**
 * Two dp dimensions packed into a single value object, used for the
 * widget size cache. Not [androidx.compose.ui.unit.IntSize] because
 * that's a Compose-layer type and `LauncherAppWidgetHost` lives below
 * the UI layer; not Android's [android.util.Size] because we want a
 * lightweight serialisable record without the platform's framework
 * surface.
 */
internal data class IntPairDp(val widthDp: Int, val heightDp: Int) {
    fun serialize(): String = WIDGET_SIZE_VALUE_FORMAT.format(widthDp, heightDp)

    companion object {
        fun parse(serialized: String): IntPairDp? {
            val parts = serialized.split('x', limit = 2)
            if (parts.size != 2) return null
            val w = parts[0].toIntOrNull() ?: return null
            val h = parts[1].toIntOrNull() ?: return null
            return IntPairDp(w, h)
        }
    }
}

// TODO(widget UX, follow-ups to PR #306): two further improvements were
// scoped during the defer-apply discussion but deferred to keep this
// change minimal.
//   (a) Background pre-warm at idle. After `state.isHomeReady` fires
//       (and a small additional delay so the IME and apps list have
//       settled), trigger `widgetsWarmed = true` programmatically without
//       the defer-apply window so the very first session swipe shows
//       fully-loaded widgets. Costs: every cold start now wakes every
//       widget provider regardless of whether the user navigates to
//       widgets, plus a few MB of host-view memory held for the session.
//       Worth throttling to one or two adjacent widget pages so the cost
//       is proportional to "things the user is likely to swipe to first."
//   (b) Persisted screenshot snapshot. Capture each
//       `AppWidgetHostView`'s rendered bitmap on `RemoteViews` apply,
//       save to internal storage keyed by widget ID, and paint it as a
//       placeholder behind the live host view on next mount until the
//       first real `RemoteViews` lands. Eliminates the "blank cards then
//       fresh" moment on cold-start swipes entirely. Privacy-sensitive
//       (snapshots may contain calendar entries, weather location,
//       message previews) so warrants explicit on-device-only treatment
//       and a staleness/invalidation policy before shipping.
