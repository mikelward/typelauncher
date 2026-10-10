package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' "Blank at night" rows under Kiosk mode. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rGB")
class KioskBlankSettingsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun aSavedTimeReadsAsItselfInAnyTimeZone() {
        // 02:30 is skipped on a spring-forward night; read as a time on
        // today's date it would show as 03:30, or a time zone away from it.
        val saved = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            val time = formatKioskBlankTime(composeRule.activity, 2 * 60 + 30)
            assertTrue(time, time.matches(Regex("""0?2:30.*""")))
        } finally {
            java.util.TimeZone.setDefault(saved)
        }
    }

    @Test
    fun theTimesShowOnlyWhileBlankAtNightIsOn() {
        var blankAtNight by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        composeRule.setContent {
            TypeLauncherTheme {
                HomeWidgetsSettingsRows(
                    isHomeWidgetsShown = true,
                    onHomeWidgetsShownChanged = {},
                    onEditHomeWidgets = {},
                    isKioskMode = true,
                    isKioskBlankAtNight = blankAtNight,
                    onKioskBlankAtNightChanged = { changes += it },
                    kioskBlankStartMinutes = 22 * 60 + 30,
                    kioskBlankEndMinutes = 6 * 60,
                )
            }
        }
        composeRule.onNodeWithTag(KIOSK_BLANK_START_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(KIOSK_BLANK_AT_NIGHT_SWITCH_TAG).performClick()
        assertEquals(listOf(true), changes)

        blankAtNight = true
        composeRule.waitForIdle()
        // In the device's own 12- or 24-hour format.
        composeRule.onNodeWithTag(KIOSK_BLANK_START_TAG).assertTextContains(deviceTime(22, 30))
        // A screen reader hears which end each time is, not a bare time.
        composeRule.onNodeWithTag(KIOSK_BLANK_START_TAG)
            .assertContentDescriptionEquals("From, ${deviceTime(22, 30)}")
        composeRule.onNodeWithTag(KIOSK_BLANK_END_TAG)
            .assertContentDescriptionEquals("Until, ${deviceTime(6, 0)}")
        composeRule.onNodeWithTag(KIOSK_BLANK_END_TAG).assertTextContains(deviceTime(6, 0))
    }

    @Test
    fun theTimesFollowAClockFormatChangeMadeWhileAway() {
        val resolver = composeRule.activity.contentResolver
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "24")
        composeRule.setContent {
            TypeLauncherTheme {
                HomeWidgetsSettingsRows(
                    isHomeWidgetsShown = true,
                    onHomeWidgetsShownChanged = {},
                    onEditHomeWidgets = {},
                    isKioskMode = true,
                    isKioskBlankAtNight = true,
                    onKioskBlankAtNightChanged = {},
                    kioskBlankStartMinutes = 22 * 60 + 30,
                    kioskBlankEndMinutes = 6 * 60,
                )
            }
        }
        composeRule.onNodeWithTag(KIOSK_BLANK_START_TAG).assertTextContains("22:30")

        // The user switches to 12-hour in system Settings and comes back.
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        android.provider.Settings.System.putString(resolver, android.provider.Settings.System.TIME_12_24, "12")
        composeRule.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(KIOSK_BLANK_START_TAG).assertTextContains("10:30", substring = true)
    }

    private fun deviceTime(hour: Int, minute: Int): String =
        android.text.format.DateFormat.getTimeFormat(composeRule.activity).format(
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
            }.time,
        )

    @Test
    fun theSwitchOnlyShowsUnderKioskMode() {
        composeRule.setContent {
            TypeLauncherTheme {
                HomeWidgetsSettingsRows(
                    isHomeWidgetsShown = true,
                    onHomeWidgetsShownChanged = {},
                    onEditHomeWidgets = {},
                    isKioskMode = false,
                )
            }
        }
        composeRule.onNodeWithTag(KIOSK_BLANK_AT_NIGHT_SWITCH_TAG).assertDoesNotExist()
    }
}
