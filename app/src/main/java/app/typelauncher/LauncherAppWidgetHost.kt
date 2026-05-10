package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import java.util.WeakHashMap
import kotlin.math.abs

internal class LauncherAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
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
    // Holds the latest RemoteViews delivered while the host's defer flag was
    // set. Overwriting on every push is intentional — only the latest needs
    // to paint when the deferral lifts; intermediate updates are typically
    // the same content with newer timestamps.
    @VisibleForTesting
    internal var pendingRemoteViews: RemoteViews? = null
        private set
    private val checkLongPress = Runnable {
        if (parent != null && !hasPerformedLongPress) {
            hasPerformedLongPress = true
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
            // UI-thread `apply()` until the carousel has settled.
            pendingRemoteViews = remoteViews
            return
        }
        applyRemoteViewsImmediate(remoteViews)
    }

    fun applyDeferredRemoteViews() {
        val queued = pendingRemoteViews ?: return
        pendingRemoteViews = null
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
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) {
                    removeCallbacks(checkLongPress)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(checkLongPress)
            }
        }
        if (hasPerformedLongPress) {
            removeCallbacks(checkLongPress)
            return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun cancelLongPress() {
        super.cancelLongPress()
        hasPerformedLongPress = false
        removeCallbacks(checkLongPress)
    }
}
