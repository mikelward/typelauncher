package app.typelauncher

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.doOnPreDraw

/** How long the wake activity waits for its window to be laid out before it goes anyway. */
internal const val KIOSK_WAKE_FINISH_CAP_MS = 1_000L

/**
 * Turns the screen back on after kiosk mode's own relock ([KioskLock]).
 * Locking turns the screen off, and an activity that shows over the lock
 * screen and turns the screen on as it comes up is the way Android recommends
 * to wake it: a wake lock's ACQUIRE_CAUSES_WAKEUP needs TURN_SCREEN_ON on
 * newer releases, which a launcher may not be granted (Codex on #814). It is
 * transparent and goes as soon as its window has been laid out, leaving the
 * display it was started over.
 */
class KioskWakeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val cap = Runnable {
        if (isFinishing) return@Runnable
        // Never drawn, so the screen may not have come back on.
        LauncherDebugLog.event("kiosk lock: wake activity never drew; finished after ${KIOSK_WAKE_FINISH_CAP_MS} ms")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    override fun onResume() {
        super.onResume()
        LauncherDebugLog.event("kiosk lock: wake activity up")
        // Not from onResume itself: Android never adds the window of an
        // activity that finished there, and turnScreenOn wakes the screen
        // only as the window manager lays that window out (Codex on #814).
        // So it goes once the window is laid out and about to draw, and at
        // the latest after the cap, so a transparent activity never sits
        // over the display taking its touches.
        window.decorView.doOnPreDraw { if (!isFinishing) finish() }
        handler.postDelayed(cap, KIOSK_WAKE_FINISH_CAP_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacks(cap)
        super.onDestroy()
    }
}
