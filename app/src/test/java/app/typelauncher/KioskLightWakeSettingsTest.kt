package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' "Wake up using light level" rows under Kiosk mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskLightWakeSettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var wakeOnLight by mutableStateOf(false)
    private val switchChanges = mutableListOf<Boolean>()
    private val levelChanges = mutableListOf<Float>()

    private fun setRows(reading: KioskLightReading = KioskLightReading.Lux(42f)) {
        composeRule.setContent {
            TypeLauncherTheme {
                // A column, as Settings has, so the rows don't sit on top of
                // each other and a tap lands on the switch it aims at.
                Column {
                    HomeWidgetsSettingsRows(
                        isHomeWidgetsShown = true,
                        onHomeWidgetsShownChanged = {},
                        onEditHomeWidgets = {},
                        isKioskMode = true,
                        isKioskWakeOnLight = wakeOnLight,
                        onKioskWakeOnLightChanged = { switchChanges += it },
                        kioskLightWakeLux = 20f,
                        onKioskLightWakeLuxChanged = { levelChanges += it },
                        kioskLightReading = { reading },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun theLevelShowsOnlyWhileLightWakeIsOn() {
        setRows()
        composeRule.onNodeWithTag(KIOSK_LIGHT_LEVEL_SLIDER_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(KIOSK_WAKE_ON_LIGHT_SWITCH_TAG).performClick()
        assertEquals(listOf(true), switchChanges)

        wakeOnLight = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("20 lux").assertExists()
        // What the room reads now, to set the level between dark and lit.
        composeRule.onNodeWithTag(KIOSK_LIGHT_READING_TAG).assertTextContains("Now 42 lux")
        // A screen reader hears the level, not a percentage of the slider.
        composeRule.onNodeWithTag(KIOSK_LIGHT_LEVEL_SLIDER_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "20 lux"))
    }

    @Test
    fun aDeviceWithNoLightSensorSaysSo() {
        wakeOnLight = true
        setRows(reading = KioskLightReading.NoSensor)

        composeRule.onNodeWithTag(KIOSK_LIGHT_READING_TAG).assertTextContains("No light sensor")
    }

    @Test
    fun draggingTheSliderSavesTheLevelOnceItEnds() {
        wakeOnLight = true
        setRows()

        composeRule.onNodeWithTag(KIOSK_LIGHT_LEVEL_SLIDER_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        // One write when the drag ends, not one per frame of it, and a level
        // brighter than where it started.
        assertEquals(1, levelChanges.size)
        assertTrue(levelChanges.single() in 21f..KIOSK_LIGHT_WAKE_MAX_LUX)
    }
}
