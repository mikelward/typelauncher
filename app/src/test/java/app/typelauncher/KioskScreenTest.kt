package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import android.app.KeyguardManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kiosk display: a still two-second hold anywhere opens Settings without
 * keeping touches from the widgets, and Home's search box, dock and app list
 * are not on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class KioskScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var exitHolds = 0

    private fun setKiosk(
        dimWhenIdle: Boolean = false,
        unlockThen: (action: () -> Unit) -> Unit = { it() },
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                KioskScreen(
                    widgetIds = listOf(WIDGET_ID),
                    isHomeReady = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    widgetHeights = emptyMap(),
                    widgetSpans = emptyMap(),
                    widgetProviderLabels = emptyMap(),
                    strandedWidgetIds = emptySet(),
                    workProfileWidgetRefreshToken = 0,
                    onExitHold = { exitHolds += 1 },
                    dimWhenIdle = dimWhenIdle,
                    motionWatcher = {},
                    unlockThen = unlockThen,
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun stillHoldOpensSettingsOnce() {
        setKiosk()
        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).performTouchInput {
            down(center)
            advanceEventTime(KIOSK_EXIT_HOLD_MS + 100)
            // Staying down past the threshold must not fire again.
            advanceEventTime(KIOSK_EXIT_HOLD_MS)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, exitHolds)
    }

    @Test
    fun tapAndShortPressDoNothing() {
        setKiosk()
        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).performTouchInput {
            click(center)
            down(center)
            advanceEventTime(KIOSK_EXIT_HOLD_MS - 500)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, exitHolds)
    }

    @Test
    fun movingTheFingerCancelsTheHold() {
        setKiosk()
        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).performTouchInput {
            down(center)
            advanceEventTime(500)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 200f))
            advanceEventTime(KIOSK_EXIT_HOLD_MS)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(0, exitHolds)
    }

    @Test
    fun widgetsShow() {
        setKiosk()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$WIDGET_ID").assertExists()
    }

    @Test
    fun touchesStillReachWhatIsUnderneath() {
        var taps = 0
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectKioskExitHold { exitHolds += 1 } },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TAPPABLE_TAG)
                            .clickable { taps += 1 },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(TAPPABLE_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(1, taps)
        assertEquals(0, exitHolds)
    }

    @Test
    fun theHoldThatOpensSettingsDoesNotAlsoClick() {
        var taps = 0
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectKioskExitHold { exitHolds += 1 } },
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag(TAPPABLE_TAG)
                            .clickable { taps += 1 },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(TAPPABLE_TAG).performTouchInput {
            down(center)
            advanceEventTime(KIOSK_EXIT_HOLD_MS + 100)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(1, exitHolds)
        assertEquals(0, taps)
    }

    @Test
    fun dimWhenIdleDimsTheWindowAfterAQuietMinute() {
        composeRule.mainClock.autoAdvance = false
        setKiosk(dimWhenIdle = true)
        assertEquals(BRIGHTNESS_NONE, brightness(), 0f)

        // The idle timer runs on the composition's clock.
        composeRule.mainClock.advanceTimeBy(KIOSK_DIM_AFTER_MS + 1_000)
        composeRule.waitForIdle()

        assertEquals(KIOSK_DIM_BRIGHTNESS, brightness(), 0f)
    }

    @Test
    fun withoutDimWhenIdleTheDisplayNeverDims() {
        composeRule.mainClock.autoAdvance = false
        setKiosk()
        composeRule.mainClock.advanceTimeBy(KIOSK_DIM_AFTER_MS * 3)

        assertEquals(BRIGHTNESS_NONE, brightness(), 0f)
    }

    @Test
    fun theDisplayShowsOverTheLockScreenOnlyWhileItIsUp() {
        var showing by mutableStateOf(true)
        composeRule.setContent {
            TypeLauncherTheme {
                if (showing) {
                    KioskScreen(
                        widgetIds = emptyList(),
                        isHomeReady = true,
                        appWidgetHost = null,
                        appWidgetManager = null,
                        widgetHeights = emptyMap(),
                        widgetSpans = emptyMap(),
                        widgetProviderLabels = emptyMap(),
                        strandedWidgetIds = emptySet(),
                        workProfileWidgetRefreshToken = 0,
                        onExitHold = {},
                        motionWatcher = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue(shadowOf(composeRule.activity).showWhenLocked)

        showing = false
        composeRule.waitForIdle()
        assertFalse(shadowOf(composeRule.activity).showWhenLocked)
    }

    @Test
    fun theHoldOpensSettingsOnlyOnceTheUnlockSucceeds() {
        var pendingUnlock: (() -> Unit)? = null
        setKiosk(unlockThen = { pendingUnlock = it })
        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).performTouchInput {
            down(center)
            advanceEventTime(KIOSK_EXIT_HOLD_MS + 100)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("Settings waits for the unlock", 0, exitHolds)

        pendingUnlock!!.invoke()
        assertEquals(1, exitHolds)
    }

    @Test
    fun unlockThenRunsAtOnceWhenTheDeviceIsUnlocked() {
        val keyguard = composeRule.activity.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(false)
        var ran = 0

        unlockThen(composeRule.activity) { ran += 1 }

        assertEquals(1, ran)
    }

    @Test
    fun unlockThenWaitsWhenTheDeviceIsLocked() {
        val keyguard = composeRule.activity.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setKeyguardLocked(true)
        var ran = 0

        unlockThen(composeRule.activity) { ran += 1 }
        composeRule.waitForIdle()

        assertEquals("nothing runs before the system reports the unlock", 0, ran)
    }

    private fun brightness(): Float = composeRule.activity.window.attributes.screenBrightness

    @Test
    fun launcherShowsTheDisplayInsteadOfHome() {
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(
                        isLoadingApps = false,
                        isHomeWidgetsShown = true,
                        isKioskMode = true,
                        isHomeReady = true,
                        homeWidgetIds = listOf(WIDGET_ID),
                        widgetIds = listOf(WIDGET_ID),
                    ),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUninstallApp = {},
                    onUnhideApp = {},
                    onOpenSettings = { exitHolds += 1 },
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetRecentsOpen = {},
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(KIOSK_SCREEN_TAG).performTouchInput {
            down(center)
            advanceEventTime(KIOSK_EXIT_HOLD_MS + 100)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(1, exitHolds)
    }

    private companion object {
        const val WIDGET_ID = 5
        const val BRIGHTNESS_NONE = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        const val TAPPABLE_TAG = "tappable"
    }
}
