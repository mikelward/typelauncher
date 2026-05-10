package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class CarouselKeyboardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialHomeAutoShow_requestsKeyboardOnce() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isKeyboardAutoShown = true))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals("initial Home should use the same one-shot search auto-show path", 1, keyboard.showCount)
        assertEquals("initial Home auto-show must not immediately hide the keyboard", 0, keyboard.hideCount)
    }

    @Test
    fun swipingFromHomeToWidgets_hidesKeyboard() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList()))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingFromHomeToAgenda_hidesKeyboard() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList()))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingRightFromHome_whenAgendaDisabledWrapsToWidgets() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isAgendaEnabled = false))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, state.screen)
        assertEquals(1, keyboard.hideCount)
    }

    @Test
    fun swipingBackToHome_reShowsKeyboardWhenAutoShowEnabled() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isKeyboardAutoShown = true))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.waitForIdle()
        assertEquals("cold-start auto-show precondition", 1, keyboard.showCount)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(LauncherScreen.Widgets, state.screen)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(2, keyboard.showCount)
        assertEquals("returning Home should not hide after re-showing the keyboard", 1, keyboard.hideCount)
    }

    @Test
    fun swipingBackToHome_waitsForScreenAckBeforeShowingKeyboard() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                filteredApps = emptyList(),
                isKeyboardAutoShown = true,
            ),
        )
        var showCountWhenHomeAcked = -1
        renderWithKeyboard(
            keyboard,
            stateProvider = { state },
            onStateChanged = {
                showCountWhenHomeAcked = keyboard.showCount
                state = it
            },
        )
        composeRule.waitForIdle()
        assertEquals("widgets precondition", LauncherScreen.Widgets, state.screen)
        assertEquals("keyboard must stay hidden while Widgets is settled", 0, keyboard.showCount)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals("keyboard.show should wait until Home is the acknowledged screen", 0, showCountWhenHomeAcked)
        assertEquals("Home ack should trigger one keyboard.show", 1, keyboard.showCount)
        assertEquals("Home ack must not hide the keyboard after showing", 0, keyboard.hideCount)
    }

    @Test
    fun homePageKeepsKeyboardReservedGeometryBeforeReturnAck() {
        val keyboard = CountingKeyboardController()
        val apps = (1..12).map { index -> fakeApp(name = "App%02d".format(index)) }
        val docked = listOf(fakeApp(name = "Docked").copy(isDocked = true))
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                filteredApps = apps,
                dockedApps = docked,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
                isKeyboardAutoShown = true,
            ),
        )
        composeRule.mainClock.autoAdvance = false
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.mainClock.advanceTimeByFrame()

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.mainClock.advanceTimeByFrame()
        val appsBeforeAck = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val dockBeforeAck = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()

        val appsAfterAck = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val dockAfterAck = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        assertTrue(
            "Home app list height should not change when Home screen ack arrives",
            kotlin.math.abs(((appsBeforeAck.bottom - appsBeforeAck.top) - (appsAfterAck.bottom - appsAfterAck.top)).value) <= 1f,
        )
        assertTrue(
            "Home app list bottom should not jump when Home screen ack arrives",
            kotlin.math.abs((appsBeforeAck.bottom - appsAfterAck.bottom).value) <= 1f,
        )
        assertTrue(
            "Dock height should not change when Home screen ack arrives",
            kotlin.math.abs(((dockBeforeAck.bottom - dockBeforeAck.top) - (dockAfterAck.bottom - dockAfterAck.top)).value) <= 1f,
        )
        assertTrue(
            "Dock bottom should not jump when Home screen ack arrives",
            kotlin.math.abs((dockBeforeAck.bottom - dockAfterAck.bottom).value) <= 1f,
        )
    }

    @Test
    fun homePageKeepsKeyboardReservedGeometryWhenReservationJittersWithinEntry() {
        val keyboard = CountingKeyboardController()
        val apps = (1..20).map { index -> fakeApp(name = "App%02d".format(index)) }
        val docked = listOf(fakeApp(name = "Docked").copy(isDocked = true))
        val recentApps = listOf(fakeApp(name = "Recent"))
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = apps,
                dockedApps = docked,
                recentApps = recentApps,
                keyboardReservation = KeyboardReservation(bottomPx = 900),
                isKeyboardAutoShown = true,
                isRecentsOpen = true,
            ),
        )
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })

        val listBaseline = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val dockBaseline = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        state = state.copy(isRecentsOpen = false)
        composeRule.waitForIdle()
        val listTrayClosed = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()

        state = state.copy(keyboardReservation = KeyboardReservation(bottomPx = 840))
        composeRule.waitForIdle()
        val listSmallerReservation = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()
        val dockSmallerReservation = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        state = state.copy(isRecentsOpen = true)
        composeRule.waitForIdle()
        val listTrayReopened = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()

        state = state.copy(keyboardReservation = KeyboardReservation(bottomPx = 900))
        composeRule.waitForIdle()
        val listRestoredReservation = composeRule.onNodeWithTag(APPS_LIST_TAG).getBoundsInRoot()

        assertTrue(
            "Home app list bottom should ignore tray close when reservation is unchanged",
            kotlin.math.abs((listBaseline.bottom - listTrayClosed.bottom).value) <= 1f,
        )
        assertTrue(
            "Home app list bottom should ignore reservation jitter within the same Home entry",
            kotlin.math.abs((listBaseline.bottom - listSmallerReservation.bottom).value) <= 1f,
        )
        assertTrue(
            "Home app list height should ignore reservation jitter within the same Home entry",
            kotlin.math.abs(
                ((listBaseline.bottom - listBaseline.top) -
                    (listSmallerReservation.bottom - listSmallerReservation.top)).value,
            ) <= 1f,
        )
        assertTrue(
            "Dock bottom should ignore reservation jitter within the same Home entry",
            kotlin.math.abs((dockBaseline.bottom - dockSmallerReservation.bottom).value) <= 1f,
        )
        assertTrue(
            "Home app list bottom should ignore tray reopen when reservation is unchanged",
            kotlin.math.abs((listSmallerReservation.bottom - listTrayReopened.bottom).value) <= 1f,
        )
        assertTrue(
            "Home app list bottom should remain stable when reservation returns to its entry value",
            kotlin.math.abs((listBaseline.bottom - listRestoredReservation.bottom).value) <= 1f,
        )
    }

    @Test
    fun homeIgnoresPersistedReservationWhenConfigFingerprintMismatches() {
        // Persisted reservation captured under a clearly different configuration
        // (here: orientation/screen swapped to landscape, plus a fake density)
        // must NOT seed the entry cache — otherwise a too-large value from the
        // last session's portrait keyboard would survive a rotation.
        val keyboard = CountingKeyboardController()
        val apps = (1..12).map { index -> fakeApp(name = "App%02d".format(index)) }
        val docked = listOf(fakeApp(name = "Docked").copy(isDocked = true))
        val staleFingerprint = KeyboardReservationConfig(
            orientation = 99,
            screenWidthDp = 1,
            screenHeightDp = 1,
            densityDpi = 1,
            navBottomPx = 0,
        )
        val matchedFingerprint = KeyboardReservationConfig(
            orientation = 1,
            screenWidthDp = 411,
            screenHeightDp = 914,
            densityDpi = 420,
            navBottomPx = 0,
        )
        var staleState by mutableStateOf(
            LauncherUiState(
                filteredApps = apps,
                dockedApps = docked,
                keyboardReservation = KeyboardReservation(
                    bottomPx = 900,
                    configFingerprint = staleFingerprint,
                ),
                isKeyboardAutoShown = true,
            ),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                TypeLauncherTheme {
                    TypeLauncherApp(
                        state = staleState,
                        onQueryChanged = {},
                        onClearQuery = {},
                        onLaunchActiveApp = {},
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUnhideApp = {},
                        onOpenSettings = {},
                        onCloseSettings = {},
                        onRequestDefaultLauncher = {},
                        onDockEnabledChanged = {},
                        onAppListIconOnlyChanged = {},
                        onDockVisibleIconCountChanged = {},
                        onAppListSortOrderChanged = {},
                        onShowAgenda = {},
                        onShowWidgets = {},
                        onShowHome = {},
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
        }
        composeRule.waitForIdle()
        val dockBoundsStaleConfig = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        // Now flip to a matching fingerprint — the same 900px reservation is
        // suddenly applicable, and the dock should rise above the reserved
        // keyboard slot.
        staleState = staleState.copy(
            keyboardReservation = KeyboardReservation(
                bottomPx = 900,
                configFingerprint = matchedFingerprint,
            ),
        )
        composeRule.waitForIdle()
        val dockBoundsMatchedConfig = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        assertTrue(
            "Dock under a fingerprint-matching 900px reservation must sit visibly higher than " +
                "under a fingerprint-mismatched one (which seeded 0 — i.e. dock rests at the bottom).",
            (dockBoundsStaleConfig.bottom - dockBoundsMatchedConfig.bottom).value > 100f,
        )
    }

    @Test
    fun swipingBackToHomeWithAgendaDisabled_showsKeyboardOnce() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(
            LauncherUiState(
                destination = LauncherDestination.Widgets(),
                filteredApps = emptyList(),
                isAgendaEnabled = false,
                isKeyboardAutoShown = true,
            ),
        )
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.waitForIdle()
        assertEquals("widgets precondition", LauncherScreen.Widgets, state.screen)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals("two-page carousel must not double-compose a keyboard show", 1, keyboard.showCount)
        assertEquals("two-page return Home must not hide after showing the keyboard", 0, keyboard.hideCount)
    }

    @Test
    fun swipingBackToHome_keepsKeyboardHiddenWhenAutoShowDisabled() {
        val keyboard = CountingKeyboardController()
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), isKeyboardAutoShown = false))
        renderWithKeyboard(keyboard, stateProvider = { state }, onStateChanged = { state = it })
        composeRule.waitForIdle()
        assertEquals("auto-show disabled precondition", 0, keyboard.showCount)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(LauncherScreen.Widgets, state.screen)

        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, state.screen)
        assertEquals(0, keyboard.showCount)
    }

    private fun renderWithKeyboard(
        keyboard: CountingKeyboardController,
        stateProvider: () -> LauncherUiState,
        onStateChanged: (LauncherUiState) -> Unit,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                TypeLauncherTheme {
                    val state = stateProvider()
                    TypeLauncherApp(
                        state = state,
                        onQueryChanged = {},
                        onClearQuery = {},
                        onLaunchActiveApp = {},
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                        onUnhideApp = {},
                        onOpenSettings = {},
                        onCloseSettings = {},
                        onRequestDefaultLauncher = {},
                        onDockEnabledChanged = {},
                        onAppListIconOnlyChanged = {},
                        onDockVisibleIconCountChanged = {},
                        onAppListSortOrderChanged = {},
                        onShowAgenda = { onStateChanged(state.copy(destination = LauncherDestination.Agenda)) },
                        onShowWidgets = { onStateChanged(state.copy(destination = LauncherDestination.Widgets())) },
                        onShowHome = { onStateChanged(state.copy(destination = LauncherDestination.Home)) },
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
        }
        composeRule.waitForIdle()
    }

    private class CountingKeyboardController : SoftwareKeyboardController {
        var showCount = 0
            private set
        var hideCount = 0
            private set

        override fun show() {
            showCount += 1
        }

        override fun hide() {
            hideCount += 1
        }
    }

    private fun fakeApp(name: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )
}
