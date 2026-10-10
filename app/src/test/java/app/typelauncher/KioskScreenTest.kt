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
import org.junit.Assert.assertEquals
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

    private fun setKiosk() {
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
        const val TAPPABLE_TAG = "tappable"
    }
}
