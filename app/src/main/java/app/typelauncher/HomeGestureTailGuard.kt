package app.typelauncher

import android.view.MotionEvent

/** No home entry has been seen yet, so nothing can be its tail. */
private const val NO_ANCHOR = Long.MIN_VALUE

/**
 * How long after the launcher's window takes focus a touch is still read as
 * the tail of the system's home gesture rather than as a deliberate tap.
 *
 * Zero would cover only a press the system delivers with its original
 * pre-focus timestamp. A press replayed *after* the window changes hands
 * carries a fresh one, and the reports this guard was written for put the
 * launch 21 ms and 33 ms after focus — so the window has to be wide enough to
 * cover a replay and narrow enough that a real tap cannot land inside it. Three
 * frames at 60 Hz is comfortably past the first, and comfortably short of a
 * person lifting a finger from the swipe and putting it back down.
 */
internal const val HOME_GESTURE_TAIL_GRACE_MILLIS = 50L

/**
 * Whether a press starting at [downUptimeMillis] landed close enough to the
 * moment the launcher's content arrived to be the swipe that brought it there,
 * rather than the user tapping what they can see.
 *
 * [graceAnchorUptimeMillis] is that moment: the home entry, or the focus gain,
 * whichever came later.
 */
internal fun isHomeGestureTail(
    downUptimeMillis: Long,
    graceAnchorUptimeMillis: Long,
    graceMillis: Long = HOME_GESTURE_TAIL_GRACE_MILLIS,
): Boolean = graceAnchorUptimeMillis != NO_ANCHOR &&
    downUptimeMillis < graceAnchorUptimeMillis + graceMillis

/**
 * Drops the touch that ends the system's swipe-up-to-home gesture, so the icon
 * it happens to land on does not launch.
 *
 * The finger lift that completes the gesture reaches the launcher as soon as
 * its window is touchable, and an app row or dock icon under it fires its
 * `onClick` like any other tap — which, when the icon is the app the user just
 * left, makes the swipe look as though it did nothing.
 *
 * The guard is **armed by the launcher entry intent** — what the system
 * delivers when the launcher is entered as home, however the activity is or is
 * not restarted around it — and disarmed by anything else, so only a real home
 * entry can produce a tail press.
 *
 * Nothing about the lifecycle says this on its own, in either direction: a
 * `singleTask` launcher re-entered from under a translucent activity never
 * restarts, so arming on a restart misses it, while one restarted because the
 * user pressed Back out of an ordinary activity was not entered as home at all,
 * so arming on every restart swallows their first deliberate tap. Focus is no
 * better — a launcher left visible in split-screen loses and regains focus
 * constantly, and the tap that hands focus back to it is deliberate.
 *
 * Armed, a press is the tail either because focus has not arrived yet, or
 * because it landed within a moment of focus arriving — the two orderings the
 * handoff can produce, and neither alone is enough (see [judgePress]).
 *
 * The judgment is made on the press, never on the release: a gesture rejected
 * halfway would leave a row that has seen a press and will never see its
 * release, which is how a pressed-looking row or a stuck drag survives the
 * gesture that produced it. So the whole gesture is swallowed once its press is
 * rejected, and each new press is judged afresh — a second, genuine tap after a
 * rejected one is not held against it.
 *
 * Main-thread confined, like the touch dispatch it hangs off.
 */
internal class HomeGestureTailGuard(
    private val graceMillis: Long = HOME_GESTURE_TAIL_GRACE_MILLIS,
) {
    /**
     * Where the grace window is measured from: the later of the home entry and
     * the focus gain, since either can be the moment the launcher's content
     * arrives under a finger that is already down.
     */
    var graceAnchorUptimeMillis: Long = NO_ANCHOR
        private set

    /** Whether the window holds focus as far as its callbacks have said. */
    var hasWindowFocus: Boolean = false
        private set

    /**
     * Whether the launcher is inside a home entry, which is the only thing
     * that can produce a gesture tail.
     */
    var isArmed: Boolean = false
        private set

    private var swallowingGesture = false

    /**
     * Whether a gesture has already been swallowed for arriving before focus
     * did. The tail is one gesture, so a second press with focus still absent
     * means the callback is not coming — and a launcher that refuses every
     * touch would be far worse than the bug this guards against.
     */
    private var hasSwallowedUnfocusedGesture = false

    /**
     * The launcher has been entered as home — from the entry intent, wherever
     * it is delivered: `Activity.onNewIntent` for a task already alive,
     * `Activity.onCreate`'s own intent for one that is not.
     */
    fun onEnteredAsHome(uptimeMillis: Long) {
        isArmed = true
        hasSwallowedUnfocusedGesture = false
        // Re-anchor, because an entry does not have to bring a focus change
        // with it: swiping home from a screen this same activity hosts —
        // Widgets, Agenda, settings — re-enters a launcher that never lost
        // focus, so without this the grace window would be measured from a
        // focus gain minutes old and the tail would sail straight through.
        graceAnchorUptimeMillis = uptimeMillis
    }

    /**
     * The anchor to carry across a recreation, or null when there is no
     * handoff to carry.
     *
     * Being armed is not the same as being mid-handoff: the guard stays armed
     * after focus arrives until a press goes through or focus is lost, so a
     * user who swipes home and then just looks at the screen leaves it armed
     * indefinitely. Saving on that would make an unrelated rotation an hour
     * later look like a handoff and cost the first deliberate tap. An entry is
     * still in progress only while its content has yet to arrive — focus not
     * seen — or while the window it opened is still running.
     */
    fun entryInProgressAnchor(nowUptimeMillis: Long): Long? = when {
        !isArmed -> null
        !hasWindowFocus -> graceAnchorUptimeMillis
        isHomeGestureTail(nowUptimeMillis, graceAnchorUptimeMillis, graceMillis) ->
            graceAnchorUptimeMillis
        else -> null
    }

    /**
     * Restore a handoff that was in progress when the activity was recreated.
     *
     * The armed state lives on this instance, and a configuration change — a
     * rotation while returning from a landscape app, say — throws the instance
     * away between the entry and the touch. The replacement would start
     * unarmed and let the tail through, so the two values that matter are
     * carried across in the saved state. The one-gesture budget is not: it
     * belongs to the instance, and a fresh one is the same allowance any entry
     * gets.
     */
    fun restoreEntryInProgress(graceAnchorUptimeMillis: Long) {
        isArmed = true
        this.graceAnchorUptimeMillis = graceAnchorUptimeMillis
        hasSwallowedUnfocusedGesture = false
    }

    fun onWindowFocusGained(uptimeMillis: Long) {
        graceAnchorUptimeMillis = uptimeMillis
        hasWindowFocus = true
        hasSwallowedUnfocusedGesture = false
    }

    /**
     * Focus went elsewhere — a dialog, a split-screen sibling, a freeform
     * window, or an app the user just launched. None of that is a home entry,
     * so the guard stands down until one actually arrives.
     */
    fun onWindowFocusLost() {
        hasWindowFocus = false
        isArmed = false
    }

    /**
     * True when this event belongs to a gesture that began as the tail of the
     * home gesture and must not reach the view hierarchy.
     *
     * @param actionMasked the event's `MotionEvent.getActionMasked()`.
     * @param downUptimeMillis the event's `MotionEvent.getDownTime()` — the
     *   start of the gesture, not of this event, so every event in a gesture
     *   is judged by the same instant.
     */
    fun shouldSwallow(actionMasked: Int, downUptimeMillis: Long): Boolean = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            val isTail = isArmed && judgePress(downUptimeMillis)
            // A press let through is the handoff over: what follows is the
            // user interacting with a launcher that is theirs again.
            if (!isTail) isArmed = false
            swallowingGesture = isTail
            isTail
        }
        // The gesture is over either way, so release the flag as it goes past
        // — a rejected gesture must not outlive itself and swallow the next.
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
            swallowingGesture.also { swallowingGesture = false }
        }
        // Moves and extra pointers ride on the decision the press made.
        else -> swallowingGesture
    }

    /**
     * Both orderings the handoff can produce, given that it *is* a handoff.
     *
     * Touch is not gated on window focus, and the focus change arrives as its
     * own main-thread message, so a press can be dispatched *before* that
     * message runs — on a cold start, before the launcher has ever been
     * focused, and on a return, while the recorded instant is still the
     * previous visit's and arbitrarily old. Either way the timing test below
     * would wave it through, so a press arriving before focus does is a tail
     * press on its own, whatever its timestamp says.
     *
     * That half swallows **one** gesture. The tail is one gesture, so a second
     * press with focus still absent says the callback is not coming, and a
     * launcher that refuses every touch on the strength of a callback that
     * never arrives would be far worse than the bug. The count resets on the
     * next focus gain or the next entry.
     *
     * Not a pure predicate: judging a press is what spends that one.
     */
    private fun judgePress(downUptimeMillis: Long): Boolean {
        if (!hasWindowFocus) {
            if (hasSwallowedUnfocusedGesture) return false
            hasSwallowedUnfocusedGesture = true
            return true
        }
        return isHomeGestureTail(downUptimeMillis, graceAnchorUptimeMillis, graceMillis)
    }
}
