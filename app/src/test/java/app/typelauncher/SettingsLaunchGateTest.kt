package app.typelauncher

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLaunchGateTest {
    private val gate = SettingsLaunchGate()

    @Test
    fun launchesWhenEditorActionHasNoEvent() {
        assertTrue(gate.shouldLaunch(action = null, keyCode = null, repeatCount = 0, downTime = null))
    }

    @Test
    fun launchesOnEnterDown() {
        assertTrue(
            gate.shouldLaunch(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                downTime = 100L,
            ),
        )
    }

    @Test
    fun suppressesMatchingEnterUpAfterHandledDown() {
        assertTrue(
            gate.shouldLaunch(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                downTime = 100L,
            ),
        )

        assertFalse(
            gate.shouldLaunch(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                downTime = 100L,
            ),
        )
    }

    @Test
    fun launchesOnEnterUpWhenNoMatchingDownHandled() {
        assertTrue(
            gate.shouldLaunch(
                action = KeyEvent.ACTION_UP,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 0,
                downTime = 200L,
            ),
        )
    }

    @Test
    fun suppressesRepeatedEnterDownEvents() {
        assertFalse(
            gate.shouldLaunch(
                action = KeyEvent.ACTION_DOWN,
                keyCode = KeyEvent.KEYCODE_ENTER,
                repeatCount = 1,
                downTime = 100L,
            ),
        )
    }
}
