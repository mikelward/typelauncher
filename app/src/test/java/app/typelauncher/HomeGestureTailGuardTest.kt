package app.typelauncher

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home-gesture tail guard, tested as the plain state machine it is.
 *
 * The bug it exists for cannot be reproduced here — it needs the system's own
 * gesture transition handing the window over mid-touch — so what is pinned is
 * the decision the guard makes given a press's timing and the lifecycle around
 * it, which is the whole of what it decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeGestureTailGuardTest {
    @Test
    fun aPressThatPredatesWindowFocusIsSwallowed() {
        val guard = returningGuard()

        assertTrue(
            "a press from before the launcher owned the screen cannot be a tap on it",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT - 80),
        )
    }

    @Test
    fun theRestOfASwallowedGestureGoesWithIt() {
        // Rejecting the press and then letting the release through is what
        // leaves a row pressed with nothing coming to release it.
        val guard = returningGuard()
        val down = FOCUS_AT - 80

        guard.shouldSwallow(MotionEvent.ACTION_DOWN, down)

        assertTrue("the move", guard.shouldSwallow(MotionEvent.ACTION_MOVE, down))
        assertTrue("and the release", guard.shouldSwallow(MotionEvent.ACTION_UP, down))
    }

    @Test
    fun aPressInsideTheGraceWindowIsSwallowed() {
        // The replayed-press case: the gesture's tail arrives after the window
        // changes hands and carries a fresh timestamp.
        val guard = returningGuard()

        assertTrue(
            guard.shouldSwallow(
                MotionEvent.ACTION_DOWN,
                downUptimeMillis = FOCUS_AT + HOME_GESTURE_TAIL_GRACE_MILLIS - 1,
            ),
        )
    }

    @Test
    fun aPressOnceTheGraceWindowHasPassedIsADeliberateTap() {
        val guard = returningGuard()

        assertFalse(
            "the guard must not eat taps the user meant",
            guard.shouldSwallow(
                MotionEvent.ACTION_DOWN,
                downUptimeMillis = FOCUS_AT + HOME_GESTURE_TAIL_GRACE_MILLIS,
            ),
        )
    }

    @Test
    fun aPressThatBeatsTheFocusCallbackIsSwallowed() {
        // The ordering that makes the timing test alone useless: touch is not
        // gated on window focus, so the press can be dispatched before the
        // focus-change message runs — and the recorded instant is then the
        // previous visit's, arbitrarily old.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(FOCUS_AT - 100)
        guard.onWindowFocusGained(FOCUS_AT)
        guard.onWindowFocusLost()
        guard.onEnteredAsHome(FOCUS_AT + 59_000)

        assertTrue(
            "an unfocused window mid-return is in no position to be launching",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 60_000),
        )
    }

    @Test
    fun aGestureThatBeatTheCallbackStaysSwallowedWhenFocusArrivesMidGesture() {
        // The loss -> return -> down -> gain ordering in full: the press is
        // rejected while unfocused, and the release must go with it even though
        // focus has arrived in between.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(FOCUS_AT - 100)
        guard.onWindowFocusGained(FOCUS_AT)
        guard.onWindowFocusLost()
        guard.onEnteredAsHome(FOCUS_AT + 59_000)
        val down = FOCUS_AT + 60_000
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, down)

        guard.onWindowFocusGained(down + 5)

        assertTrue("the release goes with its press", guard.shouldSwallow(MotionEvent.ACTION_UP, down))
        assertFalse(
            "and an ordinary tap once focus has settled gets through",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = down + 5 + 400),
        )
    }

    @Test
    fun aHomeEntryThatNeverRestartsTheLauncherIsStillArmed() {
        // A `singleTask` launcher started but not in front — under a
        // translucent activity — is re-entered through onNewIntent with no
        // restart, so nothing about the lifecycle would arm the guard; the
        // entry intent is what does.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT + 400)
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT + 400)
        guard.onWindowFocusLost()

        // The entry intent arrives, and only then the press and the focus gain.
        guard.onEnteredAsHome(FOCUS_AT + 4_900)

        assertTrue(
            "the entry intent arms what no restart could",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 5_000),
        )
    }

    @Test
    fun aTapThatHandsFocusBackToAStillVisibleLauncherGetsThrough() {
        // Split-screen, freeform, desktop: the launcher never left the screen,
        // so the tap that takes focus back is a deliberate one. No entry
        // intent, no handoff — and making the user tap every icon twice would
        // be a worse bug than the one being fixed.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT + 400)
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT + 400)
        guard.onWindowFocusLost()

        // Both orderings of the focus-transferring tap, and neither is a tail.
        assertFalse(
            "the press that arrives before focus follows it",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 5_000),
        )
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT + 5_000)
        guard.onWindowFocusGained(FOCUS_AT + 5_010)
        assertFalse(
            "and the press that arrives just after it",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 5_010),
        )
    }

    @Test
    fun theGuardStandsDownOnceARealPressHasBeenLetThrough() {
        // The return is over at the first press the user actually made, so a
        // later focus wobble cannot resurrect it.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT + 400)

        assertFalse("the guard is no longer armed", guard.isArmed)
    }

    @Test
    fun anEntryThatArrivesWhileAlreadyFocusedReAnchorsTheWindow() {
        // Swiping home from Widgets, Agenda or settings re-enters the same
        // activity: the launcher never lost focus, so nothing re-times the
        // grace window and it would still be measured from a focus gain
        // minutes old. Home appears under the lifting finger all the same.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT + 400)
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT + 400)
        val muchLater = FOCUS_AT + 600_000

        guard.onEnteredAsHome(muchLater)

        assertTrue(
            "the window is measured from the entry, not from a stale focus gain",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = muchLater + 10),
        )
    }

    @Test
    fun aHandoffInProgressSurvivesTheActivityBeingRecreated() {
        // A rotation while returning from a landscape app throws the instance
        // away between the entry and the gesture's touch. The entry will not be
        // redelivered, so the replacement has to pick the handoff back up or
        // the tail goes straight through.
        val entered = HomeGestureTailGuard()
        entered.onEnteredAsHome(FOCUS_AT)
        val carried = entered.entryInProgressAnchor(nowUptimeMillis = FOCUS_AT + 5)

        val recreated = HomeGestureTailGuard()
        recreated.restoreEntryInProgress(requireNotNull(carried))

        assertTrue(
            "the replacement is armed",
            recreated.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 10),
        )
    }

    @Test
    fun aSettledEntryIsNotCarriedAcrossARecreation() {
        // Armed is not the same as mid-handoff: the guard stays armed after
        // focus arrives until a press goes through, so a user who swipes home
        // and then just looks at the screen leaves it armed indefinitely. A
        // rotation an hour later must not read as a handoff and cost them their
        // first deliberate tap.
        val guard = returningGuard()

        assertNull(
            "nothing to carry once the window has run out",
            guard.entryInProgressAnchor(nowUptimeMillis = FOCUS_AT + 3_600_000),
        )
    }

    @Test
    fun anEntryStillWaitingOnItsContentIsAlwaysCarried() {
        // Focus has not arrived, so however long the recreation takes the
        // handoff is still ahead of it.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(FOCUS_AT)

        assertEquals(
            FOCUS_AT,
            guard.entryInProgressAnchor(nowUptimeMillis = FOCUS_AT + 3_600_000),
        )
    }

    @Test
    fun anUnarmedGuardCarriesNothing() {
        assertNull(HomeGestureTailGuard().entryInProgressAnchor(nowUptimeMillis = FOCUS_AT))
    }

    @Test
    fun aReturnThatIsNotAHomeEntryLeavesTheGuardAlone() {
        // Back out of an ordinary activity restarts the launcher without
        // entering it as home. Arming on the restart would swallow the user's
        // first deliberate tap, on exactly the ordering this guard handles.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT + 400)
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT + 400)
        guard.onWindowFocusLost()

        // The launcher is visible again with no entry intent behind it, and
        // the tap beats the focus callback.
        assertFalse(
            "no home entry, no tail",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 5_000),
        )
    }

    @Test
    fun aColdStartPressThatBeatsTheFirstFocusCallbackIsSwallowed() {
        // A swipe home after process death is a cold start, so the launcher has
        // never held focus and there is no earlier instant to compare against —
        // but the race is exactly the same one.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(0L)

        assertTrue(guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = 10L))
    }

    @Test
    fun onlyOneGestureIsSwallowedWhileFocusNeverArrives() {
        // The tail is one gesture. A second press with focus still absent says
        // the callback is not coming, and a launcher that refuses every touch
        // would be far worse than the bug it is guarding against.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(0L)
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, 10L)
        guard.shouldSwallow(MotionEvent.ACTION_UP, 10L)

        assertFalse(
            "the user gets their launcher back",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = 20L),
        )
    }

    @Test
    fun theOneUnfocusedGestureIsGivenBackByEachEntry() {
        // Spending it must not leave a later, genuine entry unguarded.
        val guard = HomeGestureTailGuard()
        guard.onEnteredAsHome(0L)
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, 10L)
        guard.shouldSwallow(MotionEvent.ACTION_UP, 10L)
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, 20L)
        guard.shouldSwallow(MotionEvent.ACTION_UP, 20L)

        guard.onEnteredAsHome(25L)

        assertTrue(guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = 30L))
    }

    @Test
    fun aSecondPressIsJudgedOnItsOwnTiming() {
        // A swallowed gesture must not leave the guard swallowing: the tap that
        // follows it is a fresh decision.
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT - 80)
        guard.shouldSwallow(MotionEvent.ACTION_UP, FOCUS_AT - 80)

        assertFalse(
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 400),
        )
    }

    @Test
    fun aCanceledGestureReleasesTheGuardTheSameWay() {
        val guard = returningGuard()
        guard.shouldSwallow(MotionEvent.ACTION_DOWN, FOCUS_AT - 80)

        assertTrue(
            "the cancel itself goes with the gesture",
            guard.shouldSwallow(MotionEvent.ACTION_CANCEL, FOCUS_AT - 80),
        )
        assertFalse(
            "and the next press starts clean",
            guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = FOCUS_AT + 400),
        )
    }

    @Test
    fun eachReturnMovesTheWindowWithIt() {
        // A press that was a deliberate tap under the old focus is the gesture
        // tail under a newer one — the launcher is returned to repeatedly.
        val guard = returningGuard()
        val laterFocus = FOCUS_AT + 10_000

        assertFalse(guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = laterFocus - 20))
        guard.shouldSwallow(MotionEvent.ACTION_UP, downUptimeMillis = laterFocus - 20)
        guard.onWindowFocusLost()
        guard.onEnteredAsHome(laterFocus - 50)
        guard.onWindowFocusGained(laterFocus)

        assertTrue(guard.shouldSwallow(MotionEvent.ACTION_DOWN, downUptimeMillis = laterFocus - 20))
    }

    /** A launcher entered as home and focused, as after a swipe home. */
    private fun returningGuard() = HomeGestureTailGuard().apply {
        onEnteredAsHome(FOCUS_AT - 100)
        onWindowFocusGained(FOCUS_AT)
    }

    private companion object {
        /** An arbitrary uptime; only the deltas around it matter. */
        const val FOCUS_AT = 500_000L
    }
}
