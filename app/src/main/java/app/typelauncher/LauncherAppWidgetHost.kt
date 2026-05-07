package app.typelauncher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

internal class LauncherAppWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(
        context: Context,
        appWidgetId: Int,
        appWidget: AppWidgetProviderInfo?,
    ): AppWidgetHostView = LauncherAppWidgetHostView(context)
}

internal class LauncherAppWidgetHostView(context: Context) : AppWidgetHostView(context) {
    private var onWidgetLongPress: (() -> Unit)? = null
    private val longPressTimeoutMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var hasPerformedLongPress = false
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
