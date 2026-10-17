package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Kiosk mode's switches in Settings. Turning Home widgets off with kiosk mode
 * on turns kiosk mode off too, its lock with it, and says so: its switches go
 * with the widgets, so it would otherwise stay on where nothing can reach it
 * (maintainer, 2026-10-17). "Lock screen automatically" shows only with kiosk
 * mode on, and leaves turning itself on to the host, which asks for the grant.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class KioskSettingsSwitchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()

    private fun show(isKioskMode: Boolean) {
        composeRule.setContent {
            TypeLauncherTheme {
                // A column, as Settings has, so a tap lands on the switch it aims at.
                Column {
                    HomeWidgetsSettingsRows(
                        isHomeWidgetsShown = true,
                        onHomeWidgetsShownChanged = { calls += "widgets $it" },
                        onEditHomeWidgets = {},
                        isKioskMode = isKioskMode,
                        onKioskModeChanged = { calls += "kiosk $it" },
                        onKioskLockChanged = { calls += "lock $it" },
                    )
                }
            }
        }
    }

    @Test
    fun turningWidgetsOffTurnsKioskModeOffAndSaysSo() {
        show(isKioskMode = true)

        composeRule.onNodeWithTag(HOME_WIDGETS_SHOWN_SWITCH_TAG).performClick()

        // Kiosk mode first, so its lock is given back before the widgets go.
        assertEquals(listOf("kiosk false", "widgets false"), calls)
        assertEquals("Kiosk mode off", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun turningWidgetsOffWithKioskModeOffSaysNothing() {
        show(isKioskMode = false)

        composeRule.onNodeWithTag(HOME_WIDGETS_SHOWN_SWITCH_TAG).performClick()

        assertEquals(listOf("widgets false"), calls)
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun theLockSwitchShowsOnlyWithKioskModeOn() {
        show(isKioskMode = false)

        composeRule.onNodeWithTag(KIOSK_LOCK_SWITCH_TAG).assertDoesNotExist()
    }

    @Test
    fun theLockSwitchStartsOffAndAsksTheHostToTurnItOn() {
        show(isKioskMode = true)

        composeRule.onNodeWithTag(KIOSK_LOCK_SWITCH_TAG).assertIsOff().performClick()

        assertEquals(listOf("lock true"), calls)
        composeRule.onNodeWithTag(KIOSK_LOCK_SWITCH_TAG).assertIsOff()
    }
}
