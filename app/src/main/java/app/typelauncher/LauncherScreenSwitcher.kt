package app.typelauncher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewAnimator
import kotlin.math.abs

class LauncherScreenSwitcher @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewAnimator(context, attrs) {
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isSwiping = false
    private var gestureEnabled = true
    var swipeListener: SwipeListener? = null
        private set

    fun setGestureEnabled(enabled: Boolean) {
        gestureEnabled = enabled
    }

    fun setSwipeNavigateListener(listener: SwipeListener?) {
        swipeListener = listener
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (!gestureEnabled) {
            return super.onInterceptTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                isSwiping = false
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - touchStartX
                val deltaY = event.y - touchStartY
                if (!isSwiping &&
                    abs(deltaX) >= SWIPE_THRESHOLD_PX &&
                    abs(deltaX) > abs(deltaY) * SWIPE_HORIZONTAL_RATIO
                ) {
                    isSwiping = true
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                isSwiping = false
            }
        }
        return super.onInterceptTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!gestureEnabled) {
            return super.onTouchEvent(event)
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (isSwiping) {
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                val handled = swipeListener?.onSwipe(event.x - touchStartX, event.y - touchStartY) == true
                isSwiping = false
                return handled || super.onTouchEvent(event)
            }
            MotionEvent.ACTION_CANCEL -> {
                isSwiping = false
            }
        }
        return if (isSwiping) true else super.onTouchEvent(event)
    }

    fun interface SwipeListener {
        fun onSwipe(deltaX: Float, deltaY: Float): Boolean
    }

    private companion object {
        const val SWIPE_THRESHOLD_PX = 120f
        const val SWIPE_HORIZONTAL_RATIO = 1.2f
    }
}
