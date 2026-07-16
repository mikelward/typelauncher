package app.typelauncher

import android.content.Intent
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Process
import android.os.UserHandle
import android.view.KeyEvent as AndroidKeyEvent
import android.view.WindowManager
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowRoleManager
import org.robolectric.shadows.ShadowToast
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRobolectricScreenshotTest {
    private val placeholderSuffixRule = TestSearchPlaceholderSuffixRule()
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(placeholderSuffixRule)
        .around(SeedLauncherStateRule())
        .around(composeRule)

    @Before
    fun awaitInitialAppLoad() {
        ShadowToast.reset()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !composeRule.activity.viewModel.uiState.value.isLoadingApps
        }
    }

    @After
    fun resetToastState() {
        ShadowToast.reset()
    }

    @Test
    fun screenshot_home_rendersClothesCastMaterialCards() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Type an app name").assertIsDisplayed()
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
        composeRule.onNodeWithText("Agenda").assertDoesNotExist()
        composeRule.onNodeWithText("Find an app").assertDoesNotExist()
        composeRule.onNodeWithText("Dock").assertDoesNotExist()
        composeRule.onNodeWithText("Installed apps").assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        saveScreenshot("compose_home_material_cards_robolectric.png")
    }

    @Test
    fun wallpaper_replacesAppListOnEmptyHomeUntilTyping() {
        // Default: the app list is the empty-Home surface, no wallpaper slot.
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertDoesNotExist()

        // Turn on "Show wallpaper" through Settings — exercises the whole
        // string/switch/callback/ViewModel/state wiring end to end.
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(WALLPAPER_SHOWN_SWITCH_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Empty Home now shows the wallpaper slot in place of the app list; the
        // search box stays visible (the gate) so the user can still type.
        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        // No screenshot here: the slot is a transparent hole to the window
        // wallpaper (via FLAG_SHOW_WALLPAPER), which Robolectric can't composite,
        // so a capture would only show empty background. The wallpaper rendering
        // is verified on-device.

        // Typing brings the filtered list back and hides the wallpaper.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertIsDisplayed()

        // Clearing the query returns to the wallpaper.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextClearance()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun wallpaperShownSetting_togglesWindowWallpaperFlag() {
        // Default (setting off): no FLAG_SHOW_WALLPAPER on the window, so the
        // launcher composites no wallpaper and forwards no touches to a live
        // wallpaper — everything wallpaper-related stays off until the setting.
        assertFalse(windowRequestsWallpaper())

        // Turning "Show wallpaper" on requests the flag.
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(WALLPAPER_SHOWN_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { windowRequestsWallpaper() }
        assertTrue(windowRequestsWallpaper())

        // Turning it back off clears the flag again.
        composeRule.onNodeWithTag(WALLPAPER_SHOWN_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { !windowRequestsWallpaper() }
        assertFalse(windowRequestsWallpaper())
    }

    private fun windowRequestsWallpaper(): Boolean =
        (composeRule.activity.window.attributes.flags and
            WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0

    @Test
    @Config(qualifiers = "w600dp-h240dp-420dpi")
    fun wallpaper_notShownWhenSearchBoxHidden() {
        composeRule.waitForIdle()
        // Cramped landscape (Compact) hides the search box, so the app list is
        // the only launch surface — the wallpaper must not take it over even
        // with the setting on, or the user would be stranded with nothing to
        // type into and no list.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        composeRule.runOnUiThread { composeRule.activity.viewModel.setWallpaperShown(true) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun cardOpacitySlider_isDisabledUntilShowWallpaperIsOn() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // The slider only affects the wallpaper backdrop, so it's disabled until
        // Show wallpaper is turned on.
        composeRule.onNodeWithTag(CARD_OPACITY_SLIDER_TAG).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithTag(WALLPAPER_SHOWN_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(CARD_OPACITY_SLIDER_TAG).assertIsEnabled()
    }

    @Test
    @Config(qualifiers = "w1280dp-h900dp-420dpi")
    fun screenshot_landscape_full_showsDockAndBox() {
        composeRule.waitForIdle()
        // A tall (tablet) landscape fits the search box, keyboard, an app row, and
        // the dock together — the Full state, which keeps everything on screen.
        assertEquals(
            HomeLandscapeTier.Full,
            composeRule.activity.viewModel.uiState.value.homeLandscapeTier,
        )
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        saveScreenshot("compose_home_landscape_full_robolectric.png", widthPx = 3360, heightPx = 2362)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-420dpi")
    fun screenshot_landscape_dockNoKeyboard_showsDockWithKeyboardDown() {
        composeRule.waitForIdle()
        // A typical phone landscape can't fit the keyboard alongside the search
        // box, dock, and an app row — but the search box, the app-list floor,
        // and a single-row dock do fit without the keyboard. So rather than the
        // cramped Compact state, Home shows the dock with the keyboard down
        // (DockNoKeyboard); the user taps the search box to bring the keyboard,
        // which then yields the dock its space.
        val state = composeRule.activity.viewModel.uiState.value
        assertEquals(HomeLandscapeTier.DockNoKeyboard, state.homeLandscapeTier)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        // The app list keeps the user's persisted layout (the default text
        // rows) in landscape — the old Compact icon-grid override is gone.
        assertFalse(
            "the persisted layout is still the default, not icon-only",
            state.appListLayout == AppListLayout.IconOnly,
        )
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertExists()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertDoesNotExist()

        saveScreenshot("compose_home_landscape_dock_no_keyboard_robolectric.png", widthPx = 2400, heightPx = 1080)
    }

    // A 6x2 portrait dock flattens into a single reading-order landscape row —
    // the top portrait row first, then the bottom row appended on the right —
    // returning the saved vertical space to the app list. The portrait
    // positions are never rewritten (this is a render-time reflow), so the
    // grid is restored when rotated back. Asserts every docked icon shares one
    // row and records the visual.
    @Test
    @Config(qualifiers = "w914dp-h411dp-420dpi")
    fun screenshot_landscape_flattensDockToOneRow() {
        composeRule.waitForIdle()
        val viewModel = composeRule.activity.viewModel
        // Eight docked apps at the default six icons per row would be a 6+2
        // two-row grid in portrait.
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        val docked = viewModel.uiState.value.dockedApps
        assertTrue("expected 8 docked apps (was=${docked.size})", docked.size == 8)
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            viewModel.uiState.value.homeLandscapeTier,
        )

        // Every docked icon shares the first row's top: the grid is one row now.
        val tops = docked.map { app ->
            composeRule.onNodeWithTag("$DOCK_APP_TAG:${app.displayName}").getBoundsInRoot().top.value
        }
        val firstTop = tops.first()
        tops.forEachIndexed { index, top ->
            assertTrue(
                "dock icon $index should share the single landscape row " +
                    "(firstTop=$firstTop, top=$top, allTops=$tops)",
                kotlin.math.abs(top - firstTop) <= 1f,
            )
        }

        saveScreenshot(
            "compose_dock_landscape_flattened_one_row_robolectric.png",
            widthPx = 2400,
            heightPx = 1080,
        )
    }

    @Test
    @Config(qualifiers = "w600dp-h240dp-420dpi")
    fun screenshot_landscape_short_showsDockWithoutSearchBox() {
        composeRule.waitForIdle()
        // A short landscape viewport without typing headroom hides the box but
        // keeps the dock: the keyboard-down fit no longer budgets the 88dp
        // search card it will never render, so two list rows + the dock fit
        // where the with-box budget would have dropped everything to Compact.
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            composeRule.activity.viewModel.uiState.value.homeLandscapeTier,
        )
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        saveScreenshot("compose_home_landscape_short_dock_no_search_box_robolectric.png", widthPx = 1575, heightPx = 630)
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-420dpi")
    fun screenshot_landscape_cachedKeyboard_doesNotReserveWithKeyboardDown() {
        composeRule.waitForIdle()
        // Even with a keyboard height cached for this size (seeded by the rule),
        // a keyboard-down landscape state must not reserve its height — the app
        // list and the single-row dock fill the viewport instead. At this
        // phone-landscape size that keyboard-down state is DockNoKeyboard, so
        // the box and dock show.
        assertEquals(
            HomeLandscapeTier.DockNoKeyboard,
            composeRule.activity.viewModel.uiState.value.homeLandscapeTier,
        )
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        saveScreenshot(
            "compose_home_landscape_dock_no_keyboard_cached_keyboard_robolectric.png",
            widthPx = 2400,
            heightPx = 1080,
        )
    }

    @Test
    @Config(qualifiers = "w600dp-h240dp-420dpi")
    fun landscape_keepsSearchVisibleWhileQueryActive() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        // A retained query must keep the search field on screen even where the
        // typing-headroom gate hides it (e.g. after a rotation carried a
        // filtered portrait search into this window), so the user can always
        // see and clear what's filtering the list.
        composeRule.runOnUiThread { composeRule.activity.viewModel.setQuery("ca") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w600dp-h240dp-420dpi")
    fun landscape_pullUpDoesNotRevealSearchWithoutTypingHeadroom() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
        // With a measured keyboard seeded (by the rule), this viewport can't
        // fit the box, the raised keyboard, and one result row together, so
        // the launcher never shows the box — a pull-up must not reveal it
        // either (it would raise a keyboard that clips the results to a
        // sliver). Search is a portrait affordance here.
        composeRule.runOnUiThread { composeRule.activity.viewModel.requestShowKeyboard() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()
    }

    // A window that fits the keyboard-down dock but not the search box: at
    // w914dp-h360dp the box + the raised keyboard + one result row would
    // exceed the height, so the box is hidden entirely (never shown, not
    // revealed) and the window is just the flattened dock and the app list.
    // The launcher never shows a search box it can't honor.
    @Test
    @Config(qualifiers = "w914dp-h360dp-420dpi")
    fun screenshot_landscape_dockNoSearchBox_hidesBoxWithoutTypingHeadroom() {
        composeRule.waitForIdle()
        val state = composeRule.activity.viewModel.uiState.value
        assertEquals(HomeLandscapeTier.DockNoKeyboard, state.homeLandscapeTier)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()

        // The pull-up is suppressed too — no box, no keyboard.
        composeRule.runOnUiThread { composeRule.activity.viewModel.requestShowKeyboard() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()

        // A query retained from portrait still forces the box on screen so the
        // filtered list stays clearable; clearing it hides the box again.
        composeRule.runOnUiThread { composeRule.activity.viewModel.setQuery("ca") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeRule.runOnUiThread { composeRule.activity.viewModel.setQuery("") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertDoesNotExist()

        saveScreenshot(
            "compose_home_landscape_dock_no_search_box_robolectric.png",
            widthPx = 2400,
            heightPx = 945,
        )
    }

    @Test
    @Config(qualifiers = "w600dp-h180dp-420dpi")
    fun landscape_compact_honorsAppListLayout() {
        composeRule.waitForIdle()
        // Even the cramped Compact state honors the persisted "App list" layout
        // (the default full-width text rows here) instead of forcing the old
        // icon-only grid. Filtering pins Calculator into the composed window so
        // the assertion doesn't depend on scroll position.
        assertEquals(
            HomeLandscapeTier.Compact,
            composeRule.activity.viewModel.uiState.value.homeLandscapeTier,
        )
        composeRule.runOnUiThread { composeRule.activity.viewModel.setQuery("calcu") }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertExists()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertDoesNotExist()
    }

    @Test
    fun screenshot_agenda_withoutPermission_showsPermissionCard() {
        composeRule.activity.viewModel.showAgenda()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(AGENDA_PERMISSION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Allow calendar access to show your agenda.").assertIsDisplayed()
        composeRule.onNodeWithText("Allow calendar access to show your agenda on this -1 screen.").assertDoesNotExist()
        composeRule.onNodeWithText("Allow calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Agenda").assertDoesNotExist()
        composeRule.onNodeWithText("Swipe right to return home").assertDoesNotExist()
        composeRule.onNodeWithText("Show calendar events").assertDoesNotExist()
        composeRule.onNodeWithTag(AGENDA_EVENTS_TAG).assertDoesNotExist()

        saveScreenshot("compose_agenda_permission_robolectric.png")
    }

    @Test
    fun screenshot_agenda_events_rendersGoogleCalendarStyleRows() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val tomorrow = today.plusDays(1)
        composeRule.activity.viewModel.showAgendaEventsForTest(todayAgendaSample())
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(AGENDA_EVENTS_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$AGENDA_EVENT_ROW_TAG:1").assertIsDisplayed()
        composeRule.onNodeWithText("Standup").assertIsDisplayed()
        composeRule.onNodeWithText("9:30 AM").assertIsDisplayed()
        composeRule.onNodeWithText("Design review").assertIsDisplayed()
        composeRule.onNodeWithText("1:00 PM").assertIsDisplayed()
        composeRule.onNodeWithTag("$AGENDA_DAY_HEADER_TAG:$today").assertIsDisplayed()
        composeRule.onNodeWithTag("$AGENDA_DAY_HEADER_TAG:$tomorrow").assertIsDisplayed()
        composeRule.onNodeWithText("Today").assertIsDisplayed()
        composeRule.onNodeWithText("Tomorrow").assertIsDisplayed()

        saveScreenshot("compose_agenda_events_robolectric.png")
    }

    @Test
    fun tappingAgendaEventRow_opensCalendarEventViaIntent() {
        val sample = todayAgendaSample()
        composeRule.activity.viewModel.showAgendaEventsForTest(sample)
        composeRule.waitForIdle()

        val designReview = sample.first { it.eventId == 42L }
        composeRule.onNodeWithTag("$AGENDA_EVENT_ROW_TAG:42").performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(
            android.content.ContentUris.withAppendedId(
                android.provider.CalendarContract.Events.CONTENT_URI,
                42L,
            ),
            startedIntent.data,
        )
        assertEquals(
            designReview.beginMillis,
            startedIntent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L),
        )
        assertEquals(
            designReview.endMillis,
            startedIntent.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, 0L),
        )
        assertStandardLauncherFlags(startedIntent)
    }

    @Test
    fun homeReadySignalPublishesIsHomeReadyOnceAppListLoadsAndImeTimeoutElapses() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.isHomeReady
        }
        assertTrue(
            "isHomeReady gates MainActivity's deferred AppWidgetHost.startListening",
            viewModel.uiState.value.isHomeReady,
        )

        // Idempotent: a second call must not unset the published flag or
        // otherwise disturb the contract that downstream consumers rely on.
        composeRule.activity.runOnUiThread { viewModel.onHomeReady() }
        composeRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isHomeReady)
    }

    @Test
    fun receivingHomeLauncherIntent_returnsToAppListFromOtherScreens() {
        val viewModel = composeRule.activity.viewModel
        viewModel.showAgenda()
        composeRule.waitForIdle()
        assertEquals(LauncherDestination.Agenda, viewModel.uiState.value.destination)

        composeRule.activity.runOnUiThread {
            composeRule.activity.handleLauncherIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            )
        }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Home, viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun receivingLauncherIntent_closesSettingsAndReturnsHome() {
        val viewModel = composeRule.activity.viewModel
        viewModel.openSettings()
        composeRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isSettingsOpen)

        composeRule.activity.runOnUiThread {
            composeRule.activity.handleLauncherIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            )
        }
        composeRule.waitForIdle()

        assertFalse(viewModel.uiState.value.isSettingsOpen)
        assertEquals(LauncherDestination.Home, viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun swipingMovesFromHomeToWidgetsToAgendaAndWrapsAround() {
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Agenda, composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Widgets(0), composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Home, composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Widgets(0), composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Agenda, composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Home, composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun fastCarouselFlingAdvancesOnlyOneScreen() {
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val startPage = carousel.carouselVirtualPage()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        val afterFirstSwipePage = carousel.carouselVirtualPage()
        assertEquals(startPage + 1, afterFirstSwipePage)
        assertEquals(LauncherDestination.Widgets(0), composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(afterFirstSwipePage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherDestination.Agenda, composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun fastAppListFlingAdvancesOnlyOneScreen() {
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val startPage = carousel.carouselVirtualPage()

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(startPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherDestination.Widgets(0), composeRule.activity.viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()
    }

    // The dock no longer has horizontal scroll, so a left swipe on it has
    // nothing to consume and should propagate straight through to the
    // carousel's nested-scroll connection — paging Home → Widgets in one
    // gesture instead of the previous two-step "scroll dock to edge first,
    // then swipe again to page" choreography.
    @Test
    fun swipingDockPagesCarousel() {
        val viewModel = composeRule.activity.viewModel
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val startPage = carousel.carouselVirtualPage()

        composeRule.onNodeWithTag(DOCK_LIST_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(startPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherDestination.Widgets(0), viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun screenshot_widgets_showsAddWidgetCard() {
        composeRule.activity.viewModel.showWidgets()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(ADD_WIDGET_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add widget").assertIsDisplayed()

        saveScreenshot("compose_widgets_add_card_robolectric.png")
    }

    @Test
    fun screenshot_widgets_removePaddingWidgetPage() {
        val widgetId = 42

        composeRule.activity.viewModel.addWidget(widgetId)
        composeRule.waitForIdle()
        awaitUnavailableWidgetCards(count = 1)

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:$widgetId").assertIsDisplayed()
        composeRule.onNodeWithText("Widget unavailable").assertIsDisplayed()
        composeRule.onNodeWithTag(ADD_WIDGET_CARD_TAG).assertIsDisplayed()

        saveScreenshot("compose_widgets_remove_padding_page_robolectric.png")
    }

    @Test
    fun tappingAddOnScrollableWidgetPagePlacesSelectedWidgetOnNewPageToRight() {
        val viewModel = composeRule.activity.viewModel
        val existingWidgets = (1..20).toList()
        existingWidgets.forEach(viewModel::addWidget)
        viewModel.showWidgets()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performScrollToNode(hasTestTag(ADD_WIDGET_CARD_TAG))
        composeRule.onNodeWithTag(ADD_WIDGET_CARD_TAG).performClick()
        composeRule.waitForIdle()
        viewModel.addWidget(999)
        composeRule.waitForIdle()

        assertEquals(listOf(existingWidgets, listOf(999)), viewModel.uiState.value.widgetPages)
        assertEquals(LauncherDestination.Widgets(1), viewModel.uiState.value.destination)
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:999").assertIsDisplayed()
    }

    @Test
    fun tappingAddOnNonScrollableWidgetPagePlacesSelectedWidgetOnCurrentPage() {
        val viewModel = composeRule.activity.viewModel
        viewModel.addWidget(42)
        viewModel.showWidgets()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(ADD_WIDGET_CARD_TAG).performClick()
        composeRule.waitForIdle()
        viewModel.addWidget(43)
        composeRule.waitForIdle()

        assertEquals(listOf(listOf(42, 43)), viewModel.uiState.value.widgetPages)
        assertEquals(LauncherDestination.Widgets(0), viewModel.uiState.value.destination)
    }

    @Test
    fun tappingAddWidgetCard_showsInAppWidgetPicker() {
        composeRule.activity.viewModel.showWidgets()
        composeRule.waitForIdle()

        composeRule.activity.viewModel.showWidgetPicker()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !composeRule.activity.viewModel.uiState.value.isLoadingAvailableWidgets
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WIDGET_PICKER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Add widget").assertIsDisplayed()
        composeRule.onNodeWithText("Choose a widget for the home screen").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        assertTrue(composeRule.activity.viewModel.uiState.value.isAddingWidget)
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WIDGET_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun screenshot_widgetPicker_expandedAppShowsInlineProviderPreviews() {
        val month = fakeWidgetProvider(appName = "Calendar", label = "Calendar month view")
        val schedule = fakeWidgetProvider(appName = "Calendar", label = "Calendar schedule")
        composeRule.activity.viewModel.showWidgetPickerForTest(listOf(month, schedule))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Calendar month view").assertIsDisplayed()
        composeRule.onNodeWithText("Calendar schedule").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${month.id}", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${schedule.id}", useUnmergedTree = true)
            .assertExists()

        saveScreenshot("compose_widget_picker_inline_previews_robolectric.png")
    }

    @Test
    fun screenshot_widgetPicker_workProfileGroupHasNoCustomBadge() {
        val personal = fakeWidgetProvider(appName = "Calendar", label = "Calendar month")
        val work = fakeWidgetProvider(
            appName = "Calendar",
            label = "Calendar work month",
            packageName = "app.typelauncher.fakewidget.work",
            profile = workUserHandle(),
            isWorkProvider = true,
        )
        composeRule.activity.viewModel.showWidgetPickerForTest(listOf(personal, work))
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar|work")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(WIDGET_WORK_BADGE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()

        saveScreenshot("compose_widget_picker_work_profile_no_badge_robolectric.png")
    }

    @Test
    fun widgetLongPress_showsRemoveActionAndRemovesWidget() {
        composeRule.activity.viewModel.addWidget(42)
        composeRule.waitForIdle()
        awaitUnavailableWidgetCards(count = 1)

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:42").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$REMOVE_WIDGET_ACTION_TAG:42").assertIsDisplayed()
        captureScreen("compose_widget_remove_menu_robolectric.png")
        composeRule.onNodeWithTag("$REMOVE_WIDGET_ACTION_TAG:42").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Int>(), composeRule.activity.viewModel.uiState.value.widgetIds)
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:42").assertDoesNotExist()
    }

    @Test
    fun removingWidget_routesHostDeleteThroughInjectableIoDispatcher() {
        // The host-binding delete is a Binder IPC that must stay off the main
        // thread, and it has to run on the *injectable* ioDispatcher (mirroring
        // LauncherViewModel.ioDispatcher) rather than a hardcoded Dispatchers.IO
        // so tests can drive it deterministically. This locks that seam in: a
        // recording dispatcher runs the delete block inline and flags itself, so
        // a regression back to Dispatchers.IO leaves `dispatched` false.
        val recording = RecordingCoroutineDispatcher()
        composeRule.activity.runOnUiThread { composeRule.activity.ioDispatcher = recording }

        composeRule.activity.viewModel.addWidget(42)
        composeRule.waitForIdle()
        awaitUnavailableWidgetCards(count = 1)

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:42").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$REMOVE_WIDGET_ACTION_TAG:42").performClick()
        composeRule.waitForIdle()

        assertTrue(
            "removeWidget must run the host-binding delete on the injected ioDispatcher",
            recording.dispatched,
        )
        assertEquals(emptyList<Int>(), composeRule.activity.viewModel.uiState.value.widgetIds)
    }

    @Test
    fun widgetLongPress_showsMoveActionsAndReordersWidget() {
        val viewModel = composeRule.activity.viewModel
        listOf(10, 20, 30).forEach(viewModel::addWidget)
        viewModel.showWidgets()
        composeRule.waitForIdle()
        awaitUnavailableWidgetCards(count = 3)

        // A middle widget can move in both directions, so its menu offers both.
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:20").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_UP_WIDGET_ACTION_TAG:20").assertIsDisplayed()
        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:20").assertIsDisplayed()
        captureScreen("compose_widget_move_menu_robolectric.png")

        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:20").performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(10, 30, 20), viewModel.uiState.value.widgetIds)
    }

    @Test
    fun widgetLongPress_hidesMoveActionsAtPageEdges() {
        val viewModel = composeRule.activity.viewModel
        listOf(10, 20).forEach(viewModel::addWidget)
        viewModel.showWidgets()
        composeRule.waitForIdle()
        awaitUnavailableWidgetCards(count = 2)

        // The top widget cannot move up; the bottom widget cannot move down.
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:10").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_UP_WIDGET_ACTION_TAG:10").assertDoesNotExist()
        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:10").assertIsDisplayed()
        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:10").performClick()
        composeRule.waitForIdle()

        // 10 is now at the bottom (order is [20, 10]); it can move up but not down.
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:10").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$MOVE_UP_WIDGET_ACTION_TAG:10").assertIsDisplayed()
        composeRule.onNodeWithTag("$MOVE_DOWN_WIDGET_ACTION_TAG:10").assertDoesNotExist()
    }

    @Test
    fun typingInSearch_filtersInstalledAppsByNameSubstring() {
        composeRule.onNodeWithText("Type an app name").assertIsDisplayed()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("settings")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Type an app name").assertDoesNotExist()
        composeRule.onNodeWithText("settings").assertIsDisplayed()
        assertVisibleApps("Settings")

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextClearance()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()
        assertVisibleApps("Calculator", "Calendar", "Camera", "Work Calendar")
    }

    @Test
    fun typingHidesDockAndClearingRestoresIt() {
        // The dock row is a stable, always-tappable surface only while the
        // user is browsing with an empty query. As soon as they type, the dock
        // disappears so the filtered results get the freed space; clearing the
        // query brings it back.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
        saveScreenshot("compose_home_typing_hides_dock_robolectric.png")

        composeRule.onNodeWithContentDescription("Clear search text").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun firstVisibleAppInTextList_isSelectedAsActiveLaunchTarget() {
        composeRule.onNodeWithTag("$APP_ROW_TAG:Browser").assertIsNotSelected()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsNotSelected()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsSelected()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calendar").assertIsNotSelected()
    }

    @Test
    fun firstVisibleAppInIconList_isSelectedAsActiveLaunchTarget() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_DROPDOWN_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_OPTION_ICON_ONLY_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Browser").assertIsNotSelected()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertIsNotSelected()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertIsSelected()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calendar").assertIsNotSelected()
        saveScreenshot("compose_home_icon_only_active_app_robolectric.png")
    }

    @Test
    fun screenshot_appListIconOnly_reversedSort_anchorsTypedMatchToBottomRow() {
        // Switch to icon-only layout and Usage ↑ sort, then type a query that
        // produces fewer matches than fill the apps card. Under reverseLayout
        // the top match (item 0) must render in the visual bottom row of the
        // grid, anchored to the bottom edge of the card next to the keyboard,
        // rather than floating at the top of the card with empty space below.
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_OPTION_ICON_ONLY_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_SORT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_SORT_OPTION_USAGE_REVERSED_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()
        composeRule.awaitAppIconsResolved()

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val topMatchBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").getBoundsInRoot()
        val rowMateBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calendar").getBoundsInRoot()
        assertTrue(
            "top match icon should sit nearest the bottom of the apps card (card.bottom=${appsCardBounds.bottom}, icon.bottom=${topMatchBounds.bottom})",
            (appsCardBounds.bottom - topMatchBounds.bottom).value <= 24f,
        )
        assertTrue(
            "top match should share the bottom row with the next match (top match.top=${topMatchBounds.top}, row mate.top=${rowMateBounds.top})",
            kotlin.math.abs((topMatchBounds.top - rowMateBounds.top).value) <= 1f,
        )

        saveScreenshot("compose_home_icon_only_reversed_typed_match_bottom_row_robolectric.png")
    }

    @Test
    fun clearButton_clearsSearchFieldAndRestoresUnfilteredResults() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.onNodeWithContentDescription("Clear search text").performClick()
        composeRule.waitForIdle()

        assertEquals(ALL_FAKE_APP_NAMES, composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name })
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsDisplayed()
    }

    @Test
    fun emptySearchShowsSettingsButtonAndNonEmptySearchShowsOnlyClearButton() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear search text").assertDoesNotExist()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear search text").assertIsDisplayed()
    }

    @Test
    fun availablePlayUpdateShowsSettingsBadgeAndBannerThenDismisses() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setPlayUpdateAvailable(123)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("New version available").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to update from Google Play.").assertIsDisplayed()
        saveScreenshot("compose_settings_play_update_banner_robolectric.png")
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_DISMISS_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue((viewModel.uiState.value.playUpdate as PlayUpdateState.Available).isDismissed)
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun tappingPlayUpdateShowsInFlightStateInsteadOfHidingBanner() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setPlayUpdateAvailable(123)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()

        viewModel.setPlayUpdateProgress(UpdateProgress.Starting)
        composeRule.waitForIdle()

        val state = viewModel.uiState.value.playUpdate as PlayUpdateState.Available
        // Tap-Update no longer session-dismisses; the banner stays visible and
        // reflects the in-flight state.
        assertFalse("Update tap must not session-dismiss the banner", state.isDismissed)
        assertEquals(UpdateProgress.Starting, state.progress)
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_PROGRESS_TAG).assertIsDisplayed()
        // The X dismiss disappears while a download is in flight — there's no
        // sensible meaning for "dismiss" once Play has accepted the user's
        // consent and started downloading.
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_DISMISS_TAG).assertDoesNotExist()
        // Tap-Update must not write a persisted dismissal.
        assertEquals(0, PlayUpdateStore(composeRule.activity).dismissedVersionCode)
        saveScreenshot("compose_settings_play_update_banner_starting_robolectric.png")
    }

    @Test
    fun playUpdateBannerShowsDownloadingStateWhilePlayDownloadIsInFlight() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setPlayUpdateAvailable(123)
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloading)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Updating…").assertIsDisplayed()
        composeRule.onNodeWithText("Downloading from Google Play.").assertIsDisplayed()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_PROGRESS_TAG).assertIsDisplayed()
        saveScreenshot("compose_settings_play_update_banner_downloading_robolectric.png")
    }

    @Test
    fun playUpdateBannerShowsRestartActionWhenDownloadFinishes() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setPlayUpdateAvailable(123)
        viewModel.setPlayUpdateProgress(UpdateProgress.Downloaded)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Update ready").assertIsDisplayed()
        composeRule.onNodeWithText("Tap to restart and install.").assertIsDisplayed()
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_RESTART_TAG).assertIsDisplayed()
        // The dismiss X is back so a user who doesn't want to restart right
        // now can snooze the version, same as the Idle state.
        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_DISMISS_TAG).assertIsDisplayed()
        saveScreenshot("compose_settings_play_update_banner_downloaded_robolectric.png")
    }

    @Test
    fun screenshot_settingsButton_playUpdateBadge_rendersAsCornerDotWithoutText() {
        composeRule.activity.viewModel.setPlayUpdateAvailable(123)
        composeRule.waitForIdle()

        val badge = composeRule.onNodeWithTag(PLAY_UPDATE_BADGE_TAG, useUnmergedTree = true)
        badge.assertExists()
        // The dot is intentionally text-free — assert the legacy "New" pill copy
        // is no longer rendered so we don't silently regress to the old style.
        composeRule.onNodeWithText("New").assertDoesNotExist()

        val badgeBounds = badge.getBoundsInRoot()
        val width = (badgeBounds.right - badgeBounds.left).value
        val height = (badgeBounds.bottom - badgeBounds.top).value
        assertTrue("badge should be square, was ${width}x$height", kotlin.math.abs(width - height) <= 1f)
        // Badge sits in the gear's top-right corner; assert it is meaningfully
        // smaller than the gear icon so it reads as a dot rather than a pill.
        val gearBounds = composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).getBoundsInRoot()
        val gearWidth = (gearBounds.right - gearBounds.left).value
        assertTrue("badge ${width}dp should be smaller than gear ${gearWidth}dp", width < gearWidth / 2f)

        saveScreenshot("compose_settings_button_play_update_badge_robolectric.png")
    }

    @Test
    fun settingsShowsLocalBuildBannerWhenNoPlayUpdateIsAvailable() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        if (BuildConfig.LOCAL_BUILD_BRANCH.isBlank()) {
            composeRule.onNodeWithTag(LOCAL_BUILD_BANNER_TAG).assertDoesNotExist()
            return
        }
        val displayBranch = BuildConfig.LOCAL_BUILD_BRANCH.substringAfter('/')
        composeRule.onNodeWithTag(LOCAL_BUILD_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(
            displayBranch,
            substring = true,
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            " · ${BuildConfig.LOCAL_BUILD_SHA}",
            substring = true,
        ).assertIsDisplayed()
        saveScreenshot("compose_settings_local_build_banner_robolectric.png")

        composeRule.activity.viewModel.setPlayUpdateAvailable(123)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PLAY_UPDATE_BANNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(LOCAL_BUILD_BANNER_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsButtonOpensSettingsAndDoneReturnsHome() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertIsDisplayed()
        // Match the page title by tag — onNodeWithText("Settings") would also
        // match the "Settings" app row in the apps preview.
        composeRule.onNodeWithTag(SETTINGS_TITLE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("App list/grid style").assertIsDisplayed()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_DROPDOWN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Dock style").assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_LAYOUT_DROPDOWN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Pull down").assertDoesNotExist()
        composeRule.onNodeWithText("Show recents").assertDoesNotExist()
        composeRule.onNodeWithText("Hide recents from app list").assertDoesNotExist()
        composeRule.onNodeWithText("Show dock").assertExists()
        composeRule.onNodeWithText("Show agenda").assertExists()
        composeRule.onNodeWithText("Icons per row: 6").assertIsDisplayed()
        composeRule.onNodeWithTag(DEFAULT_LAUNCHER_BUTTON_TAG).assertIsDisplayed()
        saveScreenshot("compose_settings_default_launcher_button_robolectric.png")

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsOverflowMenuExposesReportBugAppInfoAndAboutActions() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_APP_INFO_ACTION_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_ABOUT_ACTION_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Report bug").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_APP_INFO_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("App info").assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_ABOUT_ACTION_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("About").assertIsDisplayed()
        captureScreen("compose_settings_overflow_menu_robolectric.png")
    }

    @Test
    fun reportBugAction_showsConsentDialogBeforeSharing() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Send bug report?").assertIsDisplayed()
        composeRule.onNodeWithText("Don't show this again").assertIsDisplayed()
        saveScreenshot("compose_bug_report_consent_dialog_robolectric.png")

        // No share intent should fire while the dialog is up.
        assertEquals(null, shadowOf(composeRule.activity).nextStartedActivity)
    }

    @Test
    fun reportBugConsentDialog_cancelDismissesWithoutSharing() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_CANCEL_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DIALOG_TAG).assertDoesNotExist()
        assertEquals(null, shadowOf(composeRule.activity).nextStartedActivity)
        assertFalse(
            "consent suppression must not be persisted when the user cancels",
            DockSettingsStore(composeRule.activity).isBugReportConsentSuppressed,
        )
    }

    @Test
    fun reportBugConsentDialog_continueWithoutCheckboxSharesAndDoesNotSuppress() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DIALOG_TAG).assertDoesNotExist()
        assertFalse(
            "consent suppression must remain off when the checkbox isn't ticked",
            DockSettingsStore(composeRule.activity).isBugReportConsentSuppressed,
        )
    }

    @Test
    fun reportBugConsentDialog_continueWithCheckboxPersistsSuppression() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DONT_SHOW_AGAIN_CHECKBOX_TAG).performClick()
        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_CONFIRM_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DIALOG_TAG).assertDoesNotExist()
        assertTrue(
            "consent suppression must persist when the user ticks 'don't show again'",
            DockSettingsStore(composeRule.activity).isBugReportConsentSuppressed,
        )
    }

    @Test
    fun reportBugAction_skipsConsentDialogWhenAlreadySuppressed() {
        DockSettingsStore(composeRule.activity).isBugReportConsentSuppressed = true

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_REPORT_BUG_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(BUG_REPORT_CONSENT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsOverflowAppInfoAction_opensAndroidAppInfoForLauncherPackage() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_APP_INFO_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, startedIntent.action)
        assertEquals(
            android.net.Uri.parse("package:${composeRule.activity.packageName}"),
            startedIntent.data,
        )
        assertSettingsAppInfoFlags(startedIntent)
    }

    @Test
    fun aboutMenuItemShowsVersionAndVersionCodeAndDismisses() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_ABOUT_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_ABOUT_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("About Type Launcher").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_ABOUT_PRIVACY_POLICY_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Privacy policy").assertIsDisplayed()
        saveScreenshot("compose_settings_about_dialog_robolectric.png")

        composeRule.onNodeWithTag(SETTINGS_ABOUT_DIALOG_DISMISS_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_ABOUT_DIALOG_TAG).assertDoesNotExist()
    }

    @Test
    fun aboutDialogPrivacyPolicyLinkOpensHostedPrivacyPolicyUrl() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_OVERFLOW_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_ABOUT_ACTION_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_ABOUT_PRIVACY_POLICY_TAG).performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, startedIntent.action)
        assertEquals(
            android.net.Uri.parse("https://mikelward.github.io/typelauncher/PRIVACY"),
            startedIntent.data,
        )
    }

    @Test
    fun defaultLauncherButtonStartsHomeRoleRequest() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(DEFAULT_LAUNCHER_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivityForResult.intent
        assertEquals("android.app.role.action.REQUEST_ROLE", startedIntent.action)
        assertEquals(RoleManager.ROLE_HOME, startedIntent.getStringExtra("android.intent.extra.ROLE_NAME"))
    }

    @Test
    fun defaultLauncherButtonIsDisabledWhenAlreadyDefaultLauncher() {
        val roleManager = composeRule.activity.getSystemService(RoleManager::class.java)
        (shadowOf(roleManager) as ShadowRoleManager).addHeldRole(RoleManager.ROLE_HOME)
        composeRule.activity.viewModel.refreshPermissionDrivenUi()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DEFAULT_LAUNCHER_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithText("Already default launcher").assertIsDisplayed()
        saveScreenshot("compose_settings_already_default_launcher_robolectric.png")
    }

    @Test
    fun screenshot_appListIconOnly_sevenPerRow_packsSevenIconsInTheTopRow() {
        // The "Icons per row" slider's densest stop reaches 7 on this 411dp
        // screen (the default test width — see the 32dp icon-size floor). Render
        // the new state so CI captures the 34dp icons and their spacing, and
        // prove the grid actually packs seven columns without wrapping.
        val viewModel = composeRule.activity.viewModel
        viewModel.setAppListLayout(AppListLayout.IconOnly)
        viewModel.setDockVisibleIconCount(7)
        composeRule.waitForIdle()

        assertEquals(AppListLayout.IconOnly, viewModel.uiState.value.appListLayout)
        // The slider stored the 34dp icon size that fills 7 per row at 411dp,
        // and the dock renders those 7 columns back.
        assertEquals(34, viewModel.uiState.value.dockIconSizeDp)
        assertEquals(7, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))

        composeRule.awaitAppIconsResolved()

        // The seed has nine apps in the list, so seven fill the top row and two
        // wrap to a second row: item 6 shares the first item's top edge, item 7
        // sits below it.
        val names = viewModel.uiState.value.filteredApps.map { it.name }
        val firstBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:${names[0]}").getBoundsInRoot()
        val seventhBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:${names[6]}").getBoundsInRoot()
        val eighthBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:${names[7]}").getBoundsInRoot()
        assertTrue(
            "Item 7 (${names[6]}) should share the top row with item 1 (${names[0]}): " +
                "tops ${firstBounds.top} vs ${seventhBounds.top}",
            kotlin.math.abs((firstBounds.top - seventhBounds.top).value) <= 1f,
        )
        assertTrue(
            "Item 8 (${names[7]}) should wrap to the second row, below item 1 (${names[0]}): " +
                "tops ${firstBounds.top} vs ${eighthBounds.top}",
            (eighthBounds.top - firstBounds.top).value > 1f,
        )

        saveScreenshot("compose_home_icon_only_seven_per_row_robolectric.png")
    }

    @Test
    fun appListIconOnlySettingShowsDockStyleIconsWithoutNames() {
        val viewModel = composeRule.activity.viewModel
        // This test compares a non-docked app's icon to the docked Calculator
        // icon, so it relies on Calculator NOT appearing in the main list —
        // the dedup that hides docked apps from the empty-query list.
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_LAYOUT_OPTION_ICON_ONLY_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(AppListLayout.IconOnly, viewModel.uiState.value.appListLayout)

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Calculator is docked and the show-docked-in-list toggle is off, so
        // it's excluded from the main list and only renders in the dock row.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Browser").assertIsDisplayed()

        val appIconBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_ICON_TAG:Browser").getBoundsInRoot()
        val dockIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator").getBoundsInRoot()
        // Allow 1dp tolerance for sub-pixel rounding on non-integer densities.
        assertTrue(
            kotlin.math.abs(((dockIconBounds.right - dockIconBounds.left) - (appIconBounds.right - appIconBounds.left)).value) <= 1f,
        )
        assertTrue(
            kotlin.math.abs(((dockIconBounds.bottom - dockIconBounds.top) - (appIconBounds.bottom - appIconBounds.top)).value) <= 1f,
        )

        saveScreenshot("compose_home_icon_only_app_list_robolectric.png")
    }

    @Test
    fun appListSortOrderDropdownSortsAlphabeticallyAndPersistsRanks() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.launchApp(calculator)
        viewModel.launchApp(calculator)
        composeRule.waitForIdle()

        assertEquals(AppListSortOrder.Usage, viewModel.uiState.value.appListSortOrder)
        assertEquals("Calculator", viewModel.uiState.value.filteredApps.first().name)

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_SORT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_SORT_OPTION_NAME_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(AppListSortOrder.Alphabetical, viewModel.uiState.value.appListSortOrder)

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val orderedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertEquals(orderedNames.sortedWith(String.CASE_INSENSITIVE_ORDER), orderedNames)
    }

    @Test
    fun appListSortOrderDropdownExposesReversedVariantsThatPreserveDataOrdering() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.launchApp(calculator)
        viewModel.launchApp(calculator)
        composeRule.waitForIdle()
        // The reversed variants are a visual flip applied via `reverseLayout =
        // true` on the LazyColumn / LazyVerticalGrid; the underlying data
        // ordering still has the most-launched app at index 0 so the
        // Enter/search "active first filtered app" target stays consistent
        // across forward and reversed sorts.
        val usageOrderedNames = viewModel.uiState.value.filteredApps.map { it.name }

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_SORT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_SORT_OPTION_USAGE_REVERSED_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(AppListSortOrder.UsageReversed, viewModel.uiState.value.appListSortOrder)
        assertEquals(usageOrderedNames, viewModel.uiState.value.filteredApps.map { it.name })

        composeRule.onNodeWithTag(APP_LIST_SORT_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(APP_LIST_SORT_OPTION_NAME_REVERSED_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(AppListSortOrder.AlphabeticalReversed, viewModel.uiState.value.appListSortOrder)
        val nameReversedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertEquals(nameReversedNames.sortedWith(String.CASE_INSENSITIVE_ORDER), nameReversedNames)
    }

    @Test
    fun settingsDockToggleHidesDockOnHomeAndPreviewExpandsAppList() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        // Scroll the apps card into view before reading bounds — the settings
        // page is now scrollable and the preview's four-bar budget pushes the
        // bottom of the apps card below the viewport on the test screen, and
        // boundsInRoot returns CLIPPED bounds (Compose's localBoundingBoxOf
        // defaults clipBounds=true). Without performScrollTo both enabled and
        // disabled measurements clip to the visible viewport portion.
        // Settings preview wraps its cards in a clearAndSetSemantics {} Box
        // so the cards' testTags are stripped from the merged tree — query
        // them via useUnmergedTree = true.
        val enabledPreviewBounds = composeRule.onNodeWithTag(APPS_CARD_TAG, useUnmergedTree = true)
            .performScrollTo().getBoundsInRoot()
        val enabledPreviewHeight = enabledPreviewBounds.bottom - enabledPreviewBounds.top
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).assertIsOff()
        assertEquals(false, viewModel.uiState.value.isDockEnabled)
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).assertDoesNotExist()
        val disabledPreviewBounds = composeRule.onNodeWithTag(APPS_CARD_TAG, useUnmergedTree = true)
            .performScrollTo().getBoundsInRoot()
        val disabledPreviewHeight = disabledPreviewBounds.bottom - disabledPreviewBounds.top
        assertTrue(
            "disabled dock preview gives dock space to the app list",
            disabledPreviewHeight > enabledPreviewHeight,
        )
        saveScreenshot("compose_settings_preview_dock_disabled_robolectric.png")

        // Done button lives in the header at the top of the page, but we
        // scrolled down to the apps card earlier — bring it back into view
        // before clicking, otherwise the click misses and we never return
        // to home.
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun showAgendaToggle_inSettingsRemovesAgendaFromCarousel() {
        val viewModel = composeRule.activity.viewModel
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SHOW_AGENDA_SWITCH_TAG).performScrollTo().assertIsOn()
        composeRule.onNodeWithTag(SHOW_AGENDA_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SHOW_AGENDA_SWITCH_TAG).performScrollTo().assertIsOff()
        assertFalse(viewModel.uiState.value.isAgendaEnabled)
        assertFalse(DockSettingsStore(composeRule.activity).isAgendaEnabled)
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherDestination.Widgets(0), viewModel.uiState.value.destination)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun increasingDockVisibleIconCountShrinksLiveSettingsPreview() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.toggleDock(calculator, maxDockedApps = 6)
        // Anchor the starting count below the target so the test measures a real
        // shrink regardless of the default density.
        viewModel.setDockVisibleIconCount(4)
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Scroll the dock card into view before measuring the icon — the
        // preview's four-bar budget pushes the dock card below the viewport
        // on the test screen and boundsInRoot would otherwise return clipped
        // zero-width bounds (Compose's localBoundingBoxOf clips by default).
        // We have to scroll the dock CARD (an ancestor of the vertical
        // settings scroll), not the icon directly, because the icon sits
        // inside ScrollableIconRow's horizontalScroll — performScrollTo on
        // the icon would target the inner horizontal scroller, leaving the
        // outer page where it was. Settings preview wraps its cards in
        // clearAndSetSemantics {} so query the testTags via the unmerged
        // tree.
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).performScrollTo()
        val defaultIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator", useUnmergedTree = true)
            .getBoundsInRoot()
        val defaultIconSize = defaultIconBounds.right - defaultIconBounds.left
        viewModel.setDockVisibleIconCount(7)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Icons per row: 7").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).performScrollTo()
        val largerIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator", useUnmergedTree = true)
            .getBoundsInRoot()
        val smallerIconSize = largerIconBounds.right - largerIconBounds.left
        assertTrue("preview icon shrinks to fit more visible dock icons", smallerIconSize < defaultIconSize)

        saveScreenshot("compose_settings_dock_preview_robolectric.png")
    }

    @Test
    fun settingsPreview_reflectsCardOpacityLive() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.toggleDock(calculator, maxDockedApps = 6)
        // The preview mirrors Home's "Card opacity" only while Show wallpaper is
        // on — the same gate that enables the slider — so turn it on and drop the
        // opacity. The preview cards' fill should fade in the snapshot while their
        // icons and text stay fully opaque, so a drag of the slider is visible
        // without leaving Settings. (Robolectric can't composite the real
        // wallpaper behind Settings, so the fade reveals the settings surface
        // here; on-device it reveals the wallpaper exactly as Home does.)
        composeRule.runOnUiThread {
            viewModel.setWallpaperShown(true)
            viewModel.setCardOpacity(0.5f)
        }
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Bring the preview into the viewport before capturing (its four-bar
        // budget pushes the dock card below the fold on the test screen). The
        // preview strips its cards' semantics via clearAndSetSemantics {}, so
        // query the testTag through the unmerged tree — same convention as the
        // dock-preview test above.
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).performScrollTo()
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).assertIsDisplayed()

        saveScreenshot("compose_settings_preview_card_opacity_robolectric.png")
    }

    @Test
    fun settingsQuery_highlightsSettingsAndLaunchesAndroidSettingsBySearchAction() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("settings")
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_SETTINGS, startedIntent.action)
        assertStandardLauncherFlags(startedIntent)
    }

    @Test
    fun emptySearchAction_opensLauncherSettings() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performImeAction()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertIsDisplayed()
        assertEquals(null, shadowOf(composeRule.activity).nextStartedActivity)
    }

    @Test
    fun emptySearchEnterKey_opensLauncherSettings() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyPress(
            KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER)),
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertIsDisplayed()
        assertEquals(null, shadowOf(composeRule.activity).nextStartedActivity)
    }

    @Test
    fun enterKeyRepeatDoesNotLaunch() {
        // Hardware-keyboard key repeat delivers one ACTION_DOWN per repeat
        // with an increasing repeatCount. Only the initial press
        // (repeatCount == 0) may launch — each repeat used to fire another
        // onLaunchActiveApp, launching the app again and then (query
        // cleared by the first launch) shoving the user into settings.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("calcu")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyPress(
            KeyEvent(
                AndroidKeyEvent(
                    0L,
                    50L,
                    AndroidKeyEvent.ACTION_DOWN,
                    AndroidKeyEvent.KEYCODE_ENTER,
                    1,
                ),
            ),
        )
        composeRule.waitForIdle()

        assertEquals(
            "a key repeat must not launch the active app",
            null,
            shadowOf(composeRule.activity).nextStartedActivity,
        )
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertDoesNotExist()

        // The genuine initial press (repeatCount == 0) still launches.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performKeyPress(
            KeyEvent(AndroidKeyEvent(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.KEYCODE_ENTER)),
        )
        composeRule.waitForIdle()
        assertEquals(
            "app.typelauncher.fake1",
            shadowOf(composeRule.activity).nextStartedActivity?.component?.packageName,
        )
    }

    @Test
    fun tappingInstalledApp_launchesAndClearsSearchQuery() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", startedIntent.component?.packageName)
        assertStandardLauncherFlags(startedIntent)
        assertEquals("query is cleared after launch", "", composeRule.activity.viewModel.uiState.value.query)
    }

    @Test
    fun launchedAppsMoveAheadOfLessUsedAppsWhenSearchIsEmpty() {
        composeRule.activity.viewModel.setQuery("calendar")
        composeRule.activity.viewModel.launchApp(
            composeRule.activity.viewModel.uiState.value.filteredApps.first { it.name == "Calendar" },
        )
        composeRule.activity.viewModel.setQuery("calculator")
        composeRule.activity.viewModel.launchApp(composeRule.activity.viewModel.uiState.value.filteredApps.single())
        composeRule.activity.viewModel.setQuery("calculator")
        composeRule.activity.viewModel.launchApp(composeRule.activity.viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()

        assertEquals(
            listOf("Calculator", "Calendar", "Browser", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun dockedAppsAreExcludedFromUnfilteredList() {
        val viewModel = composeRule.activity.viewModel
        // Docked apps are hidden from the empty-query main list; assert that
        // dedup path explicitly here while the dock is on.
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        assertEquals(
            listOf("Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun disabledDockKeepsDockedAppsInNaturalOpenCountOrder() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        viewModel.setDockEnabled(false)
        composeRule.waitForIdle()

        assertEquals(
            listOf("Calculator", "Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun typedSearchRanksByLaunchCountWithinTier() {
        val viewModel = composeRule.activity.viewModel
        // Boost Camera so it outranks Calculator and Calendar in the Prefix
        // tier under the "ca" query. Without launch-count weighting the
        // alphabetical fallback would surface Calculator first even though
        // Camera is the app the user clearly reaches for. Work Calendar
        // (anchored at the inner C) lives below the entire Prefix tier.
        repeat(3) {
            viewModel.setQuery("camera")
            viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
            // Drain the launch intent each iteration so the harness's intent
            // queue doesn't bleed across the assertion below.
            shadowOf(composeRule.activity).nextStartedActivity
        }
        viewModel.setQuery("ca")
        composeRule.waitForIdle()

        assertEquals(
            listOf("Camera", "Calculator", "Calendar", "Work Calendar"),
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun tappingDockedApp_incrementsLaunchCountSoItRanksAheadOfUntappedApps() {
        val viewModel = composeRule.activity.viewModel
        // Dock Calculator first so subsequent launches come from the dock row,
        // not the main list.
        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.first { it.name == "Calculator" },
            maxDockedApps = 6,
        )
        composeRule.waitForIdle()

        repeat(2) {
            composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").performClick()
            composeRule.waitForIdle()
            shadowOf(composeRule.activity).nextStartedActivity
        }

        // Hide the dock so Calculator surfaces in the main list, then verify
        // it outranks the alphabetically-earlier Browser thanks to the launch
        // count picked up from dock taps.
        viewModel.setDockEnabled(false)
        composeRule.waitForIdle()
        assertEquals(
            listOf("Calculator", "Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun tappingRecentsApp_incrementsLaunchCountSoItRanksAheadOfUntappedApps() {
        val viewModel = composeRule.activity.viewModel
        // Seed Browser and Calendar each with one main-list launch — they
        // tie on launch count, so the empty-query sort falls through to the
        // alphabetical tie-break and Browser leads. Calendar lands in the
        // recents row.
        viewModel.setQuery("browser")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calendar")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.first { it.name == "Calendar" })
        composeRule.waitForIdle()
        repeat(2) { shadowOf(composeRule.activity).nextStartedActivity }
        assertEquals("Browser", viewModel.uiState.value.filteredApps.first().name)

        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calendar").performClick()
        composeRule.waitForIdle()

        // After the recents tap Calendar's launch count is 2 vs Browser's 1,
        // so Calendar leapfrogs Browser in the empty-query list. The pre-tap
        // assertion above pins down the baseline, so this only passes if the
        // recents-row tap actually routed through `recordLaunch`.
        assertEquals("Calendar", viewModel.uiState.value.filteredApps.first().name)
    }

    @Test
    fun typedSearchFloatsHiddenDockEntryToTopOfTier() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.first { it.name == "Camera" },
            maxDockedApps = 6,
        )
        viewModel.setDockEnabled(false)
        composeRule.waitForIdle()

        viewModel.setQuery("ca")
        composeRule.waitForIdle()

        // Camera is docked and the dock UI is hidden, so it floats to the top
        // of the Prefix tier ahead of Calculator and Calendar — the user's
        // pinned app stays reachable even though the row is sharing the main
        // list. Work Calendar still lives in its anchored tier below.
        assertEquals(
            listOf("Camera", "Calculator", "Calendar", "Work Calendar"),
            viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun resetRankAction_resetsLaunchCountAndReordersApps() {
        composeRule.activity.viewModel.setQuery("calculator")
        composeRule.activity.viewModel.launchApp(composeRule.activity.viewModel.uiState.value.filteredApps.single())
        composeRule.activity.viewModel.setQuery("calculator")
        composeRule.activity.viewModel.launchApp(composeRule.activity.viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        assertEquals("Calculator", composeRule.activity.viewModel.uiState.value.filteredApps.first().name)

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        assertEquals(ALL_FAKE_APP_NAMES, composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name })
    }

    @Test
    fun appActionsMenu_keepsSearchFocusedWhileOpen() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocusedBySemantics()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calculator").assertIsDisplayed()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsFocusedBySemantics()
    }

    @Test
    fun appActionsMenu_rendersRoundedMaterialSurface() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("calc")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.waitForIdle()

        // The actions menu is a DropdownMenu, which Compose hosts in a separate
        // popup window. saveScreenshot() only draws the activity's decorView, so
        // it would miss the popup entirely; captureScreenRoboImage composites
        // every window and captures the rounded menu surface on top of the list.
        captureScreen("compose_app_actions_menu_robolectric.png")
    }

    @Test
    fun appActionsMenuAppInfo_opensAndroidAppInfoForThatApp() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("calendar")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calendar").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calendar").performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, startedIntent.action)
        assertEquals(android.net.Uri.parse("package:app.typelauncher.fake2"), startedIntent.data)
        assertSettingsAppInfoFlags(startedIntent)
    }

    @Test
    fun hidingApp_removesItFromAppListAndAllOtherSurfaces() {
        val viewModel = composeRule.activity.viewModel
        // Pin Calculator to the dock and launch it once so it would appear in
        // the dock and recents rows too — hide should sweep all three surfaces.
        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.first { it.name == "Calculator" },
            maxDockedApps = 6,
        )
        viewModel.launchApp(viewModel.uiState.value.dockedApps.first { it.name == "Calculator" })
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$HIDE_APP_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertDoesNotExist()
        assertEquals(listOf("Calculator"), viewModel.uiState.value.hiddenApps.map { it.name })
        assertFalse(viewModel.uiState.value.filteredApps.any { it.name == "Calculator" })
        assertFalse(viewModel.uiState.value.dockedApps.any { it.name == "Calculator" })
    }

    @Test
    fun manageHiddenAppsDialog_listsHiddenAppsAndUnhideRestoresThem() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("calculator")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$HIDE_APP_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Clear search text").performClick()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_MANAGE_HIDDEN_APPS_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$SETTINGS_HIDDEN_APPS_ROW_TAG:Calculator").assertIsDisplayed()
        saveScreenshot("compose_settings_hidden_apps_dialog_robolectric.png")

        composeRule.onNodeWithTag("$SETTINGS_HIDDEN_APPS_UNHIDE_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        // Unhiding the only entry brings Calculator back to the app list and
        // collapses the hidden list to the empty state.
        assertEquals(emptyList<String>(), composeRule.activity.viewModel.uiState.value.hiddenApps.map { it.name })
        composeRule.onNodeWithTag("$SETTINGS_HIDDEN_APPS_ROW_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_EMPTY_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_DIALOG_DISMISS_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        assertTrue(
            "unhide should restore Calculator to the main app list",
            composeRule.activity.viewModel.uiState.value.filteredApps.any { it.name == "Calculator" },
        )
    }

    @Test
    fun editDialog_savingCustomLabel_overridesLabelAndMakesSearchMatchTheNewName() {
        // Apply the rename via the ViewModel to keep the assertion focused on
        // the override-propagation contract; the dialog's typed-input flow is
        // exercised by the screenshot test below.
        val viewModel = composeRule.activity.viewModel
        val target = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.renameApp(target, "Numbers")
        composeRule.waitForIdle()

        // Searching for the override surfaces the renamed entry; the original
        // "Calculator" label no longer matches anywhere.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("numb")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Numbers").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertDoesNotExist()
        assertEquals(
            "Numbers",
            viewModel.uiState.value.filteredApps
                .single { it.name == "Calculator" }
                .displayName,
        )
    }

    // Dialog-open-from-menu tests (Restore button visibility,
    // screenshot of the dialog with its field + metadata + buttons) live in
    // EditAppDialogScreenshotTest as an isolated Compose unit test rather
    // than this MainActivity integration suite. Opening a dialog with a
    // TextField via a DropdownMenu inside the running launcher window
    // triggers a Compose composition loop under Robolectric that
    // `composeRule.waitForIdle` can't settle within 60 s. The isolated
    // composition reproduces the exact same dialog content without the
    // menu-popup → dialog → IME cascade that produces the loop.

    @Test
    fun manageHiddenAppsDialog_showsEmptyMessageWhenNothingIsHidden() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_MANAGE_HIDDEN_APPS_BUTTON_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_DIALOG_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_EMPTY_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_HIDDEN_APPS_LIST_TAG).assertDoesNotExist()
    }

    @Test
    fun dockingApp_addsItToDockAndOffersUndock() {
        // Dock Calculator from its long-press menu in the typed list. Typing
        // hides the dock row, so the docked icon only appears once the query
        // is cleared — but the docked app stays in the filtered results
        // (floated to the top) while the query is active.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        assertTrue(composeRule.activity.viewModel.uiState.value.dockedApps.single().isDocked)
        // Docked apps remain in the typed search results now, floated to the
        // top of their tier.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsDisplayed()

        // Clearing the query brings the dock back; the docked entry offers Undock.
        composeRule.onNodeWithContentDescription("Clear search text").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithText("Undock").assertIsDisplayed()
    }

    @Test
    fun dockLongPress_showsAppInfoUndockAndResetRankActions() {
        // The post-dock assertion sees the empty-query dedup path; the
        // long-press menu is independent of it.
        composeRule.activity.viewModel.setQuery("calculator")
        composeRule.activity.viewModel.launchApp(composeRule.activity.viewModel.uiState.value.filteredApps.single())
        composeRule.activity.viewModel.toggleDock(
            composeRule.activity.viewModel.uiState.value.filteredApps.first { it.name == "Calculator" },
            maxDockedApps = 6,
        )
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        // Calculator is docked, so it stays out of the main list while the
        // dock is enabled; the reset only matters once the dock is disabled
        // or the app is undocked.
        assertEquals(
            listOf("Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar"),
            composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun dockRowKeepsSparseSlotsWhenUnderfilled() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        // The first user dock clears the "+" hint, so the trailing add-button
        // anchor that used to verify the second sparse slot is no longer
        // rendered. The remaining check — the docked icon's center is pinned
        // to the first sparse slot, not centered across the row — is what the
        // sparse-row layout invariant is really about.
        val dockListBounds = composeRule.onNodeWithTag(DOCK_LIST_TAG).getBoundsInRoot()
        val dockIconBounds = composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").getBoundsInRoot()
        val gap = DOCK_ITEM_SPACING_DP.dp
        val cellWidth = (
            (dockListBounds.right - dockListBounds.left) -
                gap * (dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp) - 1)
            ) / dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp)
        val firstSlotCenter = dockListBounds.left + cellWidth / 2f
        val dockIconCenter = dockIconBounds.left + (dockIconBounds.right - dockIconBounds.left) / 2f

        assertTrue(
            "docked app should stay in the first sparse slot",
            kotlin.math.abs((dockIconCenter - firstSlotCenter).value) <= 1f,
        )
    }

    @Test
    fun dockCard_staysAtBottomAfterAppList() {
        composeRule.waitForIdle()

        val appsBottom = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot().bottom
        val dockTop = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().top
        assertTrue("dock is below the apps list", dockTop > appsBottom)
    }

    @Test
    fun dockAddButton_showsFormattedDockingHintToast() {
        // Sanity-check the precondition the rest of the test relies on: the
        // fake-app fixture contains no `POPULAR_APP_PACKAGES` matches, so
        // first-run prefill adds 0 apps and the ViewModel sets the hint flag
        // true (`dockedAppIds.size = 0 < dockIconCount`). If a future fixture
        // change adds a popular package, this assertion fails loudly here
        // instead of silently turning the toast assertion into a no-op.
        assertTrue(
            "fresh-prefill fixture should set the dock-add hint flag",
            composeRule.activity.viewModel.uiState.value.shouldShowDockAddHint,
        )

        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(
            "Find an app in the list above,\nlong press then tap Dock",
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun typingHidesDockAndSurfacesDockedAppsInList() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Browser" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        // With an empty query the dock row is visible.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()

        // Typing hides the whole dock; the docked apps move into the filtered
        // list instead, so a matching docked app is still reachable as a list
        // row rather than a dock icon.
        viewModel.setQuery("cal")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_LIST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsDisplayed()

        // Clearing the query restores the dock row.
        viewModel.setQuery("")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()
    }

    @Test
    fun launchActiveApp_launchesDockedMatchSurfacedInListWhileTyping() {
        val viewModel = composeRule.activity.viewModel
        // Typing hides the dock and surfaces the docked app in the filtered
        // list, so a query matching only the docked Calculator resolves it as
        // the top result.
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        // "calc" only matches the docked Calculator; it now appears in the
        // list while typing rather than only on the (hidden) dock row.
        viewModel.setQuery("calc")
        composeRule.waitForIdle()
        assertEquals(listOf("Calculator"), viewModel.uiState.value.filteredApps.map { it.name })

        viewModel.launchActiveApp()
        composeRule.waitForIdle()

        val launched = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", launched.component?.packageName)
    }

    @Test
    fun launchActiveApp_matchesDockedAppAgainstRenameOverride() {
        // Regression: a docked app renamed to "Numbers" must be matchable by
        // the override while typing. Typing hides the dock and surfaces the
        // docked app in the list, where the matcher reads `displayName`, so a
        // query that only matches the override still resolves and launches.
        val viewModel = composeRule.activity.viewModel
        val target = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.toggleDock(target, maxDockedApps = 6)
        viewModel.renameApp(target, "Numbers")
        composeRule.waitForIdle()

        viewModel.setQuery("numb")
        composeRule.waitForIdle()
        // The renamed docked app surfaces in the list while typing and matches
        // on its override.
        assertEquals(listOf("Numbers"), viewModel.uiState.value.filteredApps.map { it.displayName })

        viewModel.launchActiveApp()
        composeRule.waitForIdle()

        val launched = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", launched.component?.packageName)
    }

    @Test
    fun dockingMoreAppsThanFit_keepsAllDockedAppsScrollable() {
        val viewModel = composeRule.activity.viewModel
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }

        assertEquals(8, viewModel.uiState.value.dockedApps.size)
        assertEquals(null, ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun dockRecentsPanel_isHiddenByDefault() {
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_HINT_TAG).assertDoesNotExist()
        assertFalse(composeRule.activity.viewModel.uiState.value.isRecentsOpen)
    }

    @Test
    fun openRecentsPanel_showsLaunchedAppsMostRecentOnRight() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calendar")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.first { it.name == "Calendar" })
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()

        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calendar").assertIsDisplayed()
        // Calculator was launched after Calendar, so it sits to the right in the row.
        val calculatorLeft = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").getBoundsInRoot().left
        val calendarLeft = composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calendar").getBoundsInRoot().left
        assertTrue("most-recent app appears on the right of the recents row", calculatorLeft > calendarLeft)
    }

    @Test
    fun openRecentsPanel_withNoLaunchesYet_showsEmptyHint() {
        composeRule.activity.viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        composeRule.activity.viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_HINT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("No recent apps yet").assertIsDisplayed()
    }

    @Test
    fun tappingRecentsApp_launchesItAndClosesPanel() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        // Drain the launch intent that recorded the recents entry so the next
        // assertion picks up the click intent, not the seeding one.
        shadowOf(composeRule.activity).nextStartedActivity
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        // Tapping a recents entry routes through launchApp, which clears
        // recentsOpen so the panel doesn't reappear when the user returns home.
        assertFalse(viewModel.uiState.value.isRecentsOpen)
        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals("app.typelauncher.fake1", startedIntent.component?.packageName)
        assertStandardLauncherFlags(startedIntent)
    }

    @Test
    fun longPressRecentsApp_showsAppInfoDockAndDismissActions() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.waitForIdle()

        // Recents items represent launch history, not list rank — Reset rank
        // and Hide are off-context, replaced by Dismiss (drops the entry off
        // the bar without touching launch counts).
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$HIDE_APP_ACTION_TAG:Calculator").assertDoesNotExist()
    }

    @Test
    fun dismissRecent_removesAppFromRecentsBarWithoutResettingRank() {
        val viewModel = composeRule.activity.viewModel
        // Launch Calculator twice so it has both a non-zero launch count and a
        // recents entry — Dismiss should remove the recents entry but leave the
        // count alone, which is what distinguishes it from Reset rank.
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertIsDisplayed()

        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertDoesNotExist()
        // Calculator stays at the top of the main list because launch count is
        // preserved — Dismiss is a recents-only operation.
        assertEquals("Calculator", viewModel.uiState.value.filteredApps.first().name)
    }

    @Test
    fun openingSettings_closesRecentsPanel() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()
        assertTrue(viewModel.uiState.value.isRecentsOpen)

        viewModel.openSettings()
        composeRule.waitForIdle()

        assertFalse(viewModel.uiState.value.isRecentsOpen)
    }

    @Test
    fun openRecentsPanel_renderRecentsCardBelowDock() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()

        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        val dockBottom = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().bottom
        val recentsTop = composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).getBoundsInRoot().top
        assertTrue("recents card sits below the dock card", recentsTop >= dockBottom)
    }

    @Test
    fun screenshot_recentsBar_pushesContentUpAndSitsFlushAtBottom() {
        // With no reserved keyboard space (the keyboard is dismissed), opening
        // the recents bar takes real space at the bottom: the dock shifts up to
        // make room and the bar lands flush at the bottom edge — "everything
        // moves up".
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calendar")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.first { it.name == "Calendar" })
        composeRule.waitForIdle()

        // With no keyboard reservation the dock is the flush bottom element, so
        // its bottom edge marks where the layout ends.
        val layoutBottom = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().bottom

        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_LIST_TAG).assertIsDisplayed()
        val dockBottomAfter = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().bottom
        val recentsBottom = composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).getBoundsInRoot().bottom
        // The dock moved up to make room, and the recents bar now occupies the
        // flush bottom slot the dock used to hold.
        assertTrue(
            "dock should shift up when recents opens (after=$dockBottomAfter, before=$layoutBottom)",
            dockBottomAfter < layoutBottom,
        )
        assertTrue(
            "recents bar should sit flush at the bottom (recents.bottom=$recentsBottom, layout.bottom=$layoutBottom)",
            kotlin.math.abs((recentsBottom - layoutBottom).value) <= 2f,
        )

        saveScreenshot("compose_home_recents_bar_flush_bottom_robolectric.png")
    }

    @Test
    fun recentsSecondaryBar_staysHiddenUntilForcedOpen() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(1_600)
        composeRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isRecentsOpen)

        // With auto-show on, the recents bar never appears on its own — the
        // keyboard owns the slot until the user dismisses it or drags the bar
        // open. Robolectric never shows the IME, so it stays hidden.
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertDoesNotExist()

        // A drag (force-open) reveals it with the launched app.
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertIsDisplayed()
    }

    @Test
    fun recentsSecondaryBar_isOrthogonalToDockToggle() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setDockEnabled(false)
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        // Dock is hidden, but the recents card still renders independently.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun settingsPreview_alwaysShowsRecentsSecondaryBar() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Show recents").assertDoesNotExist()
        // Settings preview wraps its cards in clearAndSetSemantics {} —
        // descendants live only in the unmerged tree.
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun settingsPreview_hasNoNotificationBarOrPullDownSetting() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Pull down").assertDoesNotExist()
        composeRule.onNodeWithText("Bar below").assertDoesNotExist()
        composeRule.onNodeWithText("System shade").assertDoesNotExist()
        saveScreenshot("compose_settings_secondary_bars_preview_robolectric.png")
    }

    @Test
    fun settingsPreview_dockIcon_isNotInteractiveOnEitherSurface() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.first { it.name == "Calculator" },
            maxDockedApps = 6,
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        // Drain any started intents accumulated from opening Settings.
        while (shadowOf(composeRule.activity).nextStartedActivity != null) { /* drain */ }

        // (1) Accessibility / semantics path. clearAndSetSemantics on the
        // SettingsPreview wrapper strips the cards' descendants from the
        // merged tree, so the dock-icon node must not exist there at all.
        // This blocks TalkBack double-tap, Switch Access, keyboard / D-pad,
        // and merged-tree performClick() from reaching DockedAppButton's
        // clickable / combinedClickable / long-press semantics — the
        // long-press path was important to block at the semantics layer
        // because its handler arms `menuExpanded` / `editDialogVisible`
        // before calling out to onOpenAppInfo, so a no-op callback alone
        // wouldn't have stopped the action menu from popping over Settings.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertDoesNotExist()
        // (2) Defense-in-depth no-op callback. Even via the unmerged tree
        // (which still surfaces the cards' click semantics), invoking the
        // click action must not launch Calculator — the SettingsPreview
        // wires every card callback to a no-op.
        composeRule.onNodeWithTag(DOCK_CARD_TAG, useUnmergedTree = true).performScrollTo()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator", useUnmergedTree = true).performClick()
        composeRule.waitForIdle()

        assertEquals(null, shadowOf(composeRule.activity).nextStartedActivity)
    }

    @Test
    fun keyboardAutoShowToggle_inSettings_persistsAndUpdatesWindowSoftInput() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()
        val initialMode = composeRule.activity.window.attributes.softInputMode
        assertEquals(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            initialMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        assertEquals(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            initialMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE,
        )

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(KEYBOARD_AUTO_SHOW_SWITCH_TAG).performScrollTo().assertIsOn()
        assertEquals(true, viewModel.uiState.value.isKeyboardAutoShown)

        composeRule.onNodeWithTag(KEYBOARD_AUTO_SHOW_SWITCH_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(KEYBOARD_AUTO_SHOW_SWITCH_TAG).performScrollTo().assertIsOff()
        assertEquals(false, viewModel.uiState.value.isKeyboardAutoShown)
        // MainActivity observes the preference and switches the window
        // softInputMode state; adjustResize stays active while stateAlwaysHidden
        // prevents retained search focus from re-opening the IME on resume.
        val updatedMode = composeRule.activity.window.attributes.softInputMode
        assertEquals(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            updatedMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
        )
        val flag = updatedMode and
            android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE
        assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN, flag)
    }

    @Test
    fun themeModeDropdown_inSettings_persistsSelection() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Theme").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(THEME_MODE_DROPDOWN_TAG).performScrollTo().assertIsDisplayed()
        assertEquals(ThemeMode.System, viewModel.uiState.value.themeMode)

        composeRule.onNodeWithTag(THEME_MODE_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THEME_MODE_OPTION_DARK_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(ThemeMode.Dark, viewModel.uiState.value.themeMode)

        composeRule.onNodeWithTag(THEME_MODE_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(THEME_MODE_OPTION_LIGHT_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(ThemeMode.Light, viewModel.uiState.value.themeMode)
    }

    @Test
    fun iconShapeDropdown_inSettings_persistsSelection() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Icon shape").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(ICON_SHAPE_DROPDOWN_TAG).performScrollTo().assertIsDisplayed()
        assertEquals(IconShape.System, viewModel.uiState.value.iconShape)

        saveScreenshot("compose_settings_icon_shape_robolectric.png")

        composeRule.onNodeWithTag(ICON_SHAPE_DROPDOWN_TAG).performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(ICON_SHAPE_OPTION_SQUIRCLE_TAG).performClick()
        composeRule.waitForIdle()

        assertEquals(IconShape.Squircle, viewModel.uiState.value.iconShape)
    }

    // The dock wraps to additional rows so every docked app stays visible
    // without horizontal scrolling. At 4 icons per row this means 8 docked
    // apps render as a 2-row grid; all eight icons must be present in the
    // composition tree.
    @Test
    fun dockWrapsToTwoRowsWhenAppsExceedRowWidth() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        assertEquals(8, viewModel.uiState.value.dockedApps.size)
        assertEquals(4, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        viewModel.uiState.value.dockedApps.forEach { app ->
            composeRule.onNodeWithTag("$DOCK_APP_TAG:${app.displayName}").assertIsDisplayed()
        }
        // The dock with 2 rows is taller than the same dock with 1 row would
        // be: at least one icon-height plus one inter-row gap separates the
        // top of the first icon from the top of an icon on the second row.
        val firstRowFirst = composeRule.onNodeWithTag(
            "$DOCK_APP_TAG:${viewModel.uiState.value.dockedApps[0].displayName}",
        ).getBoundsInRoot()
        val secondRowFirst = composeRule.onNodeWithTag(
            "$DOCK_APP_TAG:${viewModel.uiState.value.dockedApps[4].displayName}",
        ).getBoundsInRoot()
        assertTrue(
            "second row should sit below the first (firstTop=${firstRowFirst.top}, secondTop=${secondRowFirst.top})",
            secondRowFirst.top.value > firstRowFirst.bottom.value - 1f,
        )

        saveScreenshot("compose_dock_two_rows_robolectric.png")
    }

    @Test
    fun screenshot_dockSupportsSparseGridPositions() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        val docked = viewModel.uiState.value.filteredApps.take(5)
        docked.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        val moved = docked.first()
        val bottomLeft = docked.last()
        viewModel.reorderDockedApps(moved.id, row = 1, column = 3)
        composeRule.waitForIdle()

        val bottomLeftBounds = composeRule.onNodeWithTag("$DOCK_APP_TAG:${bottomLeft.displayName}").getBoundsInRoot()
        val movedBounds = composeRule.onNodeWithTag("$DOCK_APP_TAG:${moved.displayName}").getBoundsInRoot()
        assertTrue(
            "moved icon should stay on the sparse bottom row",
            kotlin.math.abs((movedBounds.top - bottomLeftBounds.top).value) <= 1f,
        )
        assertTrue(
            "moved icon should occupy the far-right sparse slot",
            movedBounds.left.value > bottomLeftBounds.right.value,
        )

        saveScreenshot("compose_dock_sparse_grid_robolectric.png")
    }

    // Regression-shaped invariant: the home layout reserves at least two
    // rows of the apps list above the dock no matter how many apps are
    // docked, so a heavy dock collection cannot starve the app list.
    @Test
    fun dockReservesAtLeastTwoAppListRows_evenWithManyDockedApps() {
        val viewModel = composeRule.activity.viewModel
        // Force icons-per-row to 1 so each docked app takes a full row at
        // the maximum icon size (80dp). With the 9 seeded fake apps that
        // gives ~864dp of natural dock height — well over the cap on the
        // 914dp test viewport, exercising the "apps list keeps ≥2 rows"
        // floor without needing the test to fabricate dozens more apps via
        // the Robolectric package-manager shim.
        viewModel.setDockVisibleIconCount(1)
        composeRule.waitForIdle()
        viewModel.uiState.value.filteredApps.forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        val dockedCount = viewModel.uiState.value.dockedApps.size
        // The seed registers 8 fake apps + the real launcher activity from
        // the manifest = 9. Six is plenty to exceed the cap at 1 icon per
        // row; the lower bound just guards against the seed shrinking.
        assertTrue("expected ≥6 docked apps (was=$dockedCount)", dockedCount >= 6)
        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val appsCardHeight = appsCardBounds.bottom - appsCardBounds.top
        assertTrue(
            "apps card should retain ≥100dp of height (was=$appsCardHeight)",
            appsCardHeight.value >= 100f,
        )
    }

    // Regression for the v403 dock layout bug: at 411dp/420dpi the dock
    // wrapped to 5+1 instead of fitting all 6 icons on a single row when
    // the user picked 6 icons-per-row. The dp math said 6×51 + 5×8 = 346 dp
    // ≤ 347 dp available, but `Density.roundToPx` rounds each `Dp` of every
    // dock item's `padding(4.dp) + AppIcon(iconSize.dp)` independently, so
    // the rendered per-item width was 1 px wider than the dp logical width.
    // Six items × 1 px overrun exceeded the natural slack and `FlowRow`
    // wrapped the trailing icon. The fix reserves 1 dp slack per slot when
    // sizing dock icons, matching the behaviour of the apps-list
    // `LazyVerticalGrid.Adaptive` (which divides the row's actual pixel
    // width across columns and so doesn't accumulate per-child rounding).
    @Test
    fun dockFitsRequestedIconCountInOneRow_atSixSlotsAndDefaultScreen() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()
        val sixApps = viewModel.uiState.value.filteredApps.take(6)
        sixApps.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        assertEquals(6, viewModel.uiState.value.dockedApps.size)
        assertEquals(6, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))

        val tops = viewModel.uiState.value.dockedApps.map { app ->
            composeRule.onNodeWithTag("$DOCK_APP_TAG:${app.displayName}").getBoundsInRoot().top.value
        }
        val firstTop = tops.first()
        tops.forEachIndexed { index, top ->
            assertTrue(
                "dock icon $index should share the first row's top (firstTop=$firstTop, top=$top, allTops=$tops)",
                kotlin.math.abs(top - firstTop) <= 1f,
            )
        }

        saveScreenshot("compose_dock_six_slots_one_row_robolectric.png")
    }

    // A folder tile sits on the dock next to loose app icons, so its size can
    // be compared against a sibling icon directly. The folder mini-icon fills
    // the full dock slot (icon size + vertical padding), matching the loose
    // icons' footprint rather than reading as a smaller cluster. Renders the
    // dock with real (colored) app icons — unlike DockFolderScreenshotTest,
    // which composes the mini-icon in isolation with placeholder bitmaps.
    @Test
    fun dockFolderTileMatchesLooseIconSize() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()
        val apps = viewModel.uiState.value.filteredApps.take(6)
        apps.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()
        // Build a four-member folder (the 2×2 mini-icon fully populated, the
        // common case) so the dock shows a folder tile alongside loose icons at
        // the same dock icon size.
        viewModel.mergeDockItems(sourceId = apps[1].id, targetId = apps[0].id)
        composeRule.waitForIdle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, apps[2].id)
        viewModel.addAppToDockFolder(folderId, apps[3].id)
        composeRule.waitForIdle()

        val folder = viewModel.uiState.value.dockFolders.single()
        val looseIcon = viewModel.uiState.value.dockedApps.first()
        val folderBounds = composeRule
            .onNodeWithTag("$DOCK_FOLDER_TAG:${folder.id}", useUnmergedTree = true)
            .getBoundsInRoot()
        val iconBounds = composeRule
            .onNodeWithTag("$DOCK_APP_TAG:${looseIcon.displayName}")
            .getBoundsInRoot()
        // The folder tile is at least as wide as a loose icon's slot box.
        val folderWidth = folderBounds.right.value - folderBounds.left.value
        val iconWidth = iconBounds.right.value - iconBounds.left.value
        assertTrue(
            "folder tile ($folderWidth) should be >= a loose icon slot ($iconWidth)",
            folderWidth >= iconWidth - 1f,
        )

        saveScreenshot("compose_dock_folder_in_dock_robolectric.png")
    }

    // Tapping a dock folder opens it *in place of the dock* — same card
    // footprint, the app list above unchanged. Seeds a dock with a folder, taps
    // it open, and captures the whole home so the open folder is shown in real
    // context with colored icons (unlike DockFolderInPlaceScreenshotTest, which
    // composes the open folder in an isolated stub dock card).
    @Test
    fun homeWithOpenDockFolder_rendersFolderInDockFootprint() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()
        val apps = viewModel.uiState.value.filteredApps.take(6)
        apps.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()
        // A four-member folder (full 2×2 mini-icon) alongside the two remaining
        // loose dock icons.
        viewModel.mergeDockItems(sourceId = apps[1].id, targetId = apps[0].id)
        composeRule.waitForIdle()
        val folderId = viewModel.uiState.value.dockFolders.single().id
        viewModel.addAppToDockFolder(folderId, apps[2].id)
        viewModel.addAppToDockFolder(folderId, apps[3].id)
        composeRule.waitForIdle()

        val folder = viewModel.uiState.value.dockFolders.single()
        composeRule
            .onNodeWithTag("$DOCK_FOLDER_TAG:${folder.id}", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()

        // The open folder's grid now occupies the dock card in place of the dock.
        composeRule
            .onNodeWithTag(DOCK_FOLDER_POPUP_TAG, useUnmergedTree = true)
            .assertExists()
        saveScreenshot("compose_home_open_dock_folder_robolectric.png")
    }

    // Returning to a fresh Home — a launcher entry intent (HOME press / launcher
    // icon), routed through `returnToLauncherHome` — closes any open dock folder,
    // so launching an app from elsewhere and coming back never carries a stale
    // open folder back into view.
    @Test
    fun returningToLauncherHomeClosesOpenDockFolder() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()
        val apps = viewModel.uiState.value.filteredApps.take(2)
        apps.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()
        viewModel.mergeDockItems(sourceId = apps[1].id, targetId = apps[0].id)
        composeRule.waitForIdle()

        val folder = viewModel.uiState.value.dockFolders.single()
        composeRule
            .onNodeWithTag("$DOCK_FOLDER_TAG:${folder.id}", useUnmergedTree = true)
            .performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(DOCK_FOLDER_POPUP_TAG, useUnmergedTree = true)
            .assertExists()

        viewModel.returnToLauncherHome()
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(DOCK_FOLDER_POPUP_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    // In a window wider than portrait (here a tall tablet landscape, which is
    // the only landscape that stays in the Full state and therefore renders the
    // dock), the dock keeps its portrait icon size and the gray card is narrowed
    // to its content and centered — an island with the screen background showing
    // in the margins — rather than a bar stretched edge-to-edge. Asserts the dock
    // card is narrower than the full-width apps card and symmetrically inset, and
    // records the visual for review.
    @Test
    @Config(qualifiers = "w1280dp-h900dp-420dpi")
    fun dockCardIsNarrowedAndCenteredInLandscape() {
        val config = composeRule.activity.resources.configuration
        assertTrue(
            "expected a landscape window (w=${config.screenWidthDp}, h=${config.screenHeightDp})",
            config.screenWidthDp > config.screenHeightDp,
        )
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()
        val sixApps = viewModel.uiState.value.filteredApps.take(6)
        sixApps.forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        assertEquals(6, viewModel.uiState.value.dockedApps.size)

        // The apps card fills the full content width; the dock card should be
        // a centered island narrower than it.
        val appsCard = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val dockCard = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()

        val dockWidth = (dockCard.right - dockCard.left).value
        val appsWidth = (appsCard.right - appsCard.left).value
        val leftMargin = (dockCard.left - appsCard.left).value
        val rightMargin = (appsCard.right - dockCard.right).value
        assertTrue(
            "dock card should be narrower than the full-width apps card " +
                "(dock=$dockWidth, apps=$appsWidth)",
            dockWidth < appsWidth,
        )
        assertTrue(
            "dock card should leave a real side margin (left=$leftMargin)",
            leftMargin > 8f,
        )
        assertTrue(
            "dock card should be centered (left=$leftMargin, right=$rightMargin)",
            kotlin.math.abs(leftMargin - rightMargin) <= 2f,
        )

        saveScreenshot(
            "compose_dock_landscape_centered_robolectric.png",
            widthPx = 3360,
            heightPx = 2362,
        )
    }

    // The "+" hint is a one-shot onboarding affordance: it appears after a
    // first-run prefill that leaves a free slot, and disappears for good as
    // soon as the user docks anything to that dock. The fake-app fixture has
    // no POPULAR_APP_PACKAGES matches, so prefill adds 0 apps and the flag is
    // set on the empty dock.
    @Test
    fun dockAddButton_appearsAfterFreshPrefill() {
        composeRule.waitForIdle()

        assertEquals(0, composeRule.activity.viewModel.uiState.value.dockedApps.size)
        assertTrue(composeRule.activity.viewModel.uiState.value.shouldShowDockAddHint)
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertIsDisplayed()
    }

    @Test
    fun dockAddButton_hiddenAfterFirstUserDock() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertIsDisplayed()

        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.first { it.name == "Calculator" },
            maxDockedApps = 6,
        )
        composeRule.waitForIdle()

        assertEquals(1, viewModel.uiState.value.dockedApps.size)
        assertFalse(viewModel.uiState.value.shouldShowDockAddHint)
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertDoesNotExist()
    }

    // Upgrade-install path: a pre-prefill build's persisted dock + a
    // hand-seeded `dock_prefilled = true` flag (mirroring what
    // `LauncherViewModel`'s upgrade-skip branch latches) must NEVER advertise
    // the hint, even though the first row has free slots.
    @Test
    fun dockAddButton_hiddenOnUpgradeInstall() {
        composeRule.waitForIdle()

        // The carve-out in `SeedLauncherStateRule` seeded a docked entry and
        // set `dock_prefilled = true` *before* the activity started — the
        // shape of state left behind when a user upgrades from a build that
        // predates `prefillDock`. The flag default of `false` must hold.
        assertFalse(composeRule.activity.viewModel.uiState.value.shouldShowDockAddHint)
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun dockAddButton_hiddenWhenMoreThanOneRow() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        viewModel.uiState.value.filteredApps.take(5).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        // 5 docked apps × 4 icons per row = 2 rows; user has already learned
        // the gesture, so the hint must not appear.
        assertEquals(5, viewModel.uiState.value.dockedApps.size)
        assertEquals(4, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertDoesNotExist()
    }

    // When the user has filled every cell in the wrapped grid (an exact
    // multiple of icons-per-row), the + button drops out regardless.
    @Test
    fun dockAddButton_hiddenWhenGridIsFull() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        // 8 docked apps × 4 icons per row = 2 full rows, no empty cells.
        assertEquals(8, viewModel.uiState.value.dockedApps.size)
        assertEquals(4, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        composeRule.onNodeWithTag(DOCK_ADD_BUTTON_TAG).assertDoesNotExist()
    }

    @Test
    fun appsListOverflow_placesBottomChevronAtOuterCardEdge() {
        addFakeLauncherApps((1..60).map { index -> "Overflow App %02d".format(index) })
        composeRule.activity.viewModel.reloadInstalledAppsForTest()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.viewModel.uiState.value.filteredApps.any { app -> app.name == "Overflow App 60" }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()

        val appsCardBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val chevronBounds = composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).getBoundsInRoot()
        assertTrue(
            "bottom chevron should sit at the card edge (card=${appsCardBounds.bottom}, chevron=${chevronBounds.bottom})",
            kotlin.math.abs((chevronBounds.bottom - appsCardBounds.bottom).value) <= 1f,
        )
        saveScreenshot("compose_apps_list_bottom_overflow_chevron_robolectric.png")
    }

    @Test
    fun screenshot_appListIconOnly_overflowingGrid() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()

        assertEquals(AppListLayout.IconOnly, viewModel.uiState.value.appListLayout)
        assertTrue(viewModel.uiState.value.filteredApps.any { app -> app.name == "Overflow App 60" })
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Browser").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Browser").assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.awaitAppIconsResolved()

        saveScreenshot("compose_home_icon_only_overflowing_grid_robolectric.png")
    }

    // Regression: on ~427dp screens (Pixel 9 Pro) GridCells.Adaptive(iconSizeDp) fitted 7 columns
    // instead of 6 because it ignored the 4dp padding on each side of each icon button.
    // 427dp × 2.625 (420dpi) = 1120.875 ≈ 1121px wide.
    @Test
    @Config(qualifiers = "w427dp-h914dp-420dpi")
    fun screenshot_appListIconOnly_overflowingGrid_pixel9ProWidth() {
        val viewModel = composeRule.activity.viewModel
        composeRule.waitForIdle()

        assertEquals(AppListLayout.IconOnly, viewModel.uiState.value.appListLayout)
        assertTrue(viewModel.uiState.value.filteredApps.any { app -> app.name == "Overflow App 60" })
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Browser").assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.awaitAppIconsResolved()

        saveScreenshot(
            "compose_home_icon_only_overflowing_grid_pixel9pro_robolectric.png",
            widthPx = 1121,
            heightPx = 2400,
        )
    }

    @Test
    fun recentsOverflow_showsStartChevronHint() {
        val viewModel = composeRule.activity.viewModel
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.launchApp(app)
        }
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
        // Recents auto-scrolls to the end so the freshest app stays visible
        // on the right; older apps sit off-screen to the left, so the start
        // chevron points the way to them and the end chevron is gone.
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun recentsWithoutOverflow_hidesScrollChevrons() {
        val viewModel = composeRule.activity.viewModel
        viewModel.launchApp(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" })
        viewModel.setKeyboardReservation(KeyboardReservation(bottomPx = 900))
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun pausedWorkApp_isHiddenFromEverySurface() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("work")
        composeRule.waitForIdle()
        // Dock the work app first while it is "active" so the dock store
        // pins it; pausing the profile should then drop it from the
        // derived dock list (and every other surface) without unpinning.
        viewModel.toggleDock(
            viewModel.uiState.value.filteredApps.single { it.name == "Work Calendar" },
            maxDockedApps = 6,
        )
        // Clear the query so the dock row (hidden while typing) is rendered
        // and we can confirm the work app was pinned before pausing.
        viewModel.setQuery("")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Work Calendar").assertIsDisplayed()

        // Drive quiet mode through the test seam — Robolectric's shadows
        // can't simulate a real work profile in quiet mode for the launcher
        // to read back through `UserManager.isQuietModeEnabled`. The
        // production reactive path is exercised by the unit test for
        // `personalProfileAppsAreNotMarkedQuiet` and by the
        // broadcast-receiver wiring in `LauncherViewModel`.
        viewModel.markAsPausedWorkAppForTest("app.typelauncher.fake8")
        composeRule.waitForIdle()

        // Dropped from the dock derivation even though the dock store still
        // pins it; the same `visibleInstalledApps()` filter feeds search
        // results and recents so they all hide it too without per-surface
        // plumbing.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Work Calendar").assertDoesNotExist()
        assertTrue(
            "Paused work app should be filtered out of dockedApps",
            viewModel.uiState.value.dockedApps.none { it.name == "Work Calendar" },
        )
        viewModel.setQuery("work")
        composeRule.waitForIdle()
        assertTrue(
            "Paused work app should not appear in search results",
            viewModel.uiState.value.filteredApps.none { it.name == "Work Calendar" },
        )
        composeRule.onNodeWithTag("$APP_ROW_TAG:Work Calendar").assertDoesNotExist()
    }

    @Test
    fun workAppIcon_rendersWithoutCustomOverlay() {
        composeRule.activity.viewModel.setQuery("work")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Work Calendar").assertIsDisplayed()
        assertEquals(listOf("Work Calendar"), composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name })
        // On a real device the work briefcase rides in a separate overlay
        // (AppIconLoader.loadWorkBadge) drawn outside the icon's circular clip.
        // Robolectric's default PackageManager doesn't composite a badge, so no
        // overlay node appears here — only the base icon node is asserted.
        composeRule.onNodeWithTag("$APP_ICON_TAG:Work Calendar", useUnmergedTree = true).assertExists()

        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.single(), maxDockedApps = 6)
        // Clear the query so the dock row (hidden while typing) renders the
        // docked work icon for the screenshot.
        viewModel.setQuery("")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:Work Calendar").assertIsDisplayed()
        assertTrue(viewModel.uiState.value.dockedApps.single().name.startsWith("Work"))

        saveScreenshot("compose_home_work_app_icon_robolectric.png")
    }

    @Test
    fun workDockCard_isHiddenByDefaultEvenWithActiveWorkProfile() {
        val viewModel = composeRule.activity.viewModel
        viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake8")
        composeRule.waitForIdle()

        // Default `isWorkDockEnabled = false` — even when a work profile is
        // active, the work dock stays opted out until the user flips the
        // setting. The personal dock card remains visible.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun typingDoesNotFloatWorkDockPinsWhenWorkDockIsDisabled() {
        // Regression: stale work-dock pins (left in the work store after the
        // user turned the work dock off) must not float to the top of the
        // search results — or become the Enter launch target — while typing.
        // They should rank as normal apps.
        val viewModel = composeRule.activity.viewModel
        viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake8")
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()

        val workApp = viewModel.uiState.value.filteredApps.first { it.name == "Work Calendar" }
        viewModel.toggleWorkDock(workApp, maxDockedApps = 6)
        // Rename so the pin shares the "cal" prefix tier with the non-docked
        // Calculator / Calendar; alphabetically it sorts last, so floating
        // (the bug) would visibly jump it to the front.
        viewModel.renameApp(workApp, "Calzzz")
        // Turn the work dock back off; the pin stays in the work store.
        viewModel.setWorkDockEnabled(false)
        composeRule.waitForIdle()

        viewModel.setQuery("cal")
        composeRule.waitForIdle()

        val names = viewModel.uiState.value.filteredApps.map { it.displayName }
        assertTrue("Renamed work app should still be a candidate, got $names", "Calzzz" in names)
        assertEquals("Disabled work pin must not float to the top, got $names", "Calculator", names.first())
        assertEquals("Disabled work pin ranks by normal alpha order, got $names", "Calzzz", names.last())
    }

    @Test
    fun workDockCard_rendersBelowPersonalDockWhenEnabledAndProfileActive() {
        val viewModel = composeRule.activity.viewModel
        // Seed two real `POPULAR_APP_PACKAGES` entries into the shadow
        // PackageManager and flip them to work so the prefill's tier (c)
        // popular-fallback path has something to land. The default fake
        // seed uses `app.typelauncher.fakeN` packages that aren't in the
        // popular list, so the prefill would otherwise leave the work
        // dock empty.
        addPopularWorkApps(
            listOf(
                "Gmail" to "com.google.android.gm",
                "Calendar" to "com.google.android.calendar",
            ),
        )
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()
        val personalTop = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().top
        val workTop = composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).getBoundsInRoot().top
        assertTrue("work dock should sit below the personal dock", workTop > personalTop)
        // Both popular packages landed via the prefill's popular-fallback
        // tier, with the "+" hint preserved because the row isn't full.
        composeRule.onNodeWithTag("$WORK_DOCK_APP_TAG:Gmail").assertExists()
        composeRule.onNodeWithTag("$WORK_DOCK_APP_TAG:Calendar").assertExists()
        composeRule.onNodeWithTag(WORK_DOCK_ADD_BUTTON_TAG).assertExists()
    }

    // Regression: enabling the work dock used to cap *each* dock at half the
    // slot via `weight(1f, fill = false)`, which trimmed pixels off the
    // second row of a 2-row personal dock even when the combined natural
    // height fit comfortably inside `dockMaxPx`. The work dock is now pinned
    // to a single icon row's height and the personal dock carries
    // `weight(1f, fill = false)` so it gets all the slot space left below
    // the work card, so a 2-row personal dock + 1-row work dock that fits
    // the budget renders both cards at their natural heights and the
    // bottom-row icons sit fully inside the personal dock card.
    @Test
    fun bothDocksRenderAtNaturalHeightWhenSlotBudgetAllows() {
        val viewModel = composeRule.activity.viewModel
        addPopularWorkApps(
            listOf("Gmail" to "com.google.android.gm"),
        )
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()

        // Dock 8 apps so the personal dock spills onto a second row at the
        // default 4-icon row width. `maxDockedApps = 1` keeps `toggleDock`
        // from undocking what we just added on subsequent iterations.
        viewModel.uiState.value.filteredApps
            .filter { app -> !app.isWorkApp }
            .take(8)
            .forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        assertEquals(8, viewModel.uiState.value.dockedApps.size)
        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()

        val dockCardBounds = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot()
        val secondRowApp = viewModel.uiState.value.dockedApps[4]
        val secondRowBounds = composeRule
            .onNodeWithTag("$DOCK_APP_TAG:${secondRowApp.displayName}")
            .getBoundsInRoot()
        // The bottom-row icon must sit strictly inside the personal dock
        // card's bottom edge — anything else means the card was capped
        // before the second row finished rendering.
        assertTrue(
            "second-row icon should be fully inside the personal dock card " +
                "(iconBottom=${secondRowBounds.bottom}, cardBottom=${dockCardBounds.bottom})",
            secondRowBounds.bottom.value <= dockCardBounds.bottom.value + 0.5f,
        )
    }

    // Regression for the work-dock-starvation edge case: with the original
    // wrapper-Column shape and no weights, a heavily-docked personal dock
    // could consume the entire `dockMaxPx` budget and leave the work card
    // with zero height — collapsing it out of view despite the user having
    // it enabled. The current shape caps the work card at
    // `MAX_WORK_DOCK_ROWS` icon rows via `Modifier.heightIn`, with the row
    // count derived from `workApps` only. With one work app and the default
    // 4-icon-per-row dock, that resolves to the one-row cap regardless of
    // how many personal apps are docked.
    @Test
    fun workDockCard_staysAtOneRowEvenWithHeavilyDockedPersonalDock() {
        val viewModel = composeRule.activity.viewModel
        addPopularWorkApps(
            listOf("Gmail" to "com.google.android.gm"),
        )
        viewModel.setWorkDockEnabled(true)
        // Force 1-icon-per-row on the personal dock so that every docked
        // app takes a full row at the maximum icon size — 9 fake apps then
        // give ~900dp of natural personal-dock height, far exceeding the
        // ~600dp dock-slot budget on the 914dp test viewport. This is the
        // shape that used to starve the work card to zero height under the
        // earlier unweighted-Column layout.
        viewModel.setDockVisibleIconCount(1)
        composeRule.waitForIdle()

        viewModel.uiState.value.filteredApps
            .filter { app -> !app.isWorkApp }
            .forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()
        val workBounds = composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).getBoundsInRoot()
        val workHeight = workBounds.bottom.value - workBounds.top.value
        // The cap is
        // `dockIconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP + 2 * SECTION_CARD_PADDING_DP`.
        // The test viewport is `w411dp-h914dp` with the default 4-slot dock,
        // which `dockIconSizeForSlotCount` resolves to 56dp icons; adding
        // 8dp of per-icon tap-target padding and 2 × 16dp of SectionCard
        // padding gives a 96dp one-row cap. Allow a couple of dp of slack
        // for sub-pixel rounding when measuring in root coordinates.
        val iconSizeDp = dockIconSizeForSlotCount(411, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        val expectedMaxDp = iconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP + 2 * SECTION_CARD_PADDING_DP
        assertTrue(
            "work card should be capped at one row (height=${workHeight}dp, cap=${expectedMaxDp}dp)",
            workHeight <= expectedMaxDp + 2f,
        )
        assertTrue(
            "work card should have non-zero height (height=${workHeight}dp)",
            workHeight > 0f,
        )
    }

    // Once the work dock holds more apps than fit on a single row at the
    // current `dockIconCount`, the card grows to a second row instead of
    // hiding the overflow behind a horizontal scroll the same as the
    // personal dock would do. The cap is `MAX_WORK_DOCK_ROWS` rows, so
    // even with many more work apps than two rows can hold the card stays
    // bounded — the remainder scrolls inside the card's own
    // `verticalScroll`.
    @Test
    fun workDockCard_expandsToSecondRowWhenWorkAppsExceedOneRow() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        // Mark five seeded fake apps as work apps so the work dock has more
        // than one row's worth of content at 4 icons per row: five apps means
        // exactly one slot bleeds onto a second row.
        (0..4).forEach { i ->
            viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake$i")
        }
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()

        // The prefill's launchCount-tier finds nothing in fakes (no launch
        // history) and the POPULAR_APP_PACKAGES-tier doesn't match either,
        // so the work dock is empty after enabling. Dock every work app
        // explicitly so we hit the 5-app shape.
        viewModel.uiState.value.filteredApps
            .filter { app -> app.isWorkApp && !app.isWorkDocked }
            .forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()

        assertTrue(
            "expected ≥5 work apps in work dock (was=${viewModel.uiState.value.workDockedApps.size})",
            viewModel.uiState.value.workDockedApps.size >= 5,
        )

        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()
        val workBounds = composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).getBoundsInRoot()
        val workHeight = workBounds.bottom.value - workBounds.top.value
        val iconSizeDp = dockIconSizeForSlotCount(411, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        val rowHeightDp = iconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP
        val oneRowMaxDp = rowHeightDp + 2 * SECTION_CARD_PADDING_DP
        val twoRowMaxDp =
            2 * rowHeightDp + DOCK_ITEM_SPACING_DP + 2 * SECTION_CARD_PADDING_DP
        assertTrue(
            "work card should grow past one row (height=${workHeight}dp, oneRow=${oneRowMaxDp}dp)",
            workHeight > oneRowMaxDp + 2f,
        )
        assertTrue(
            "work card should be capped at MAX_WORK_DOCK_ROWS=$MAX_WORK_DOCK_ROWS " +
                "(height=${workHeight}dp, twoRow=${twoRowMaxDp}dp)",
            workHeight <= twoRowMaxDp + 2f,
        )
    }

    // The cap has to reflect `DockCard`'s own row-count calculation
    // (`maxOccupiedRow + 1` over the resolved positions, not just
    // `ceil(size / dockIconCount)`), otherwise a sparse persisted layout —
    // e.g. four apps with one parked at row 1 from when the dock had five —
    // clips the row-1 icon behind the card's vertical scroll despite the
    // dock visibly occupying two rows.
    @Test
    fun workDockCard_keepsTwoRowsForSparsePersistedPositions() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setDockVisibleIconCount(4)
        (0..4).forEach { i ->
            viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake$i")
        }
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()

        // Dock 5 work apps so the persisted positions span row 0 and row 1.
        viewModel.uiState.value.filteredApps
            .filter { app -> app.isWorkApp && !app.isWorkDocked }
            .forEach { app -> viewModel.toggleDock(app, maxDockedApps = 1) }
        composeRule.waitForIdle()
        assertTrue(viewModel.uiState.value.workDockedApps.size >= 5)

        // Identify the lone row-1 app and a row-0 sibling, then undock the
        // sibling so only 4 apps remain but the row-1 app keeps its
        // persisted (row = 1) position. After this, `ceil(4 / 4) = 1` would
        // wrongly cap at one row; the resolved-positions row count is 2.
        val state = viewModel.uiState.value
        val resolved = resolvedDockPositions(
            state.workDockedApps.map { app -> app.id },
            state.workDockPositions,
            dockSlotCountForIconSize(411, state.dockIconSizeDp),
        )
        val row1Apps = state.workDockedApps.filter { app -> resolved[app.id]?.row == 1 }
        val row0Apps = state.workDockedApps.filter { app -> resolved[app.id]?.row == 0 }
        assertEquals("expected exactly one app on row 1", 1, row1Apps.size)
        assertTrue("expected at least one app on row 0 to undock", row0Apps.isNotEmpty())

        viewModel.toggleDock(row0Apps.first(), maxDockedApps = 1)
        composeRule.waitForIdle()

        assertEquals(4, viewModel.uiState.value.workDockedApps.size)

        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()
        val workBounds = composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).getBoundsInRoot()
        val workHeight = workBounds.bottom.value - workBounds.top.value
        val iconSizeDp = dockIconSizeForSlotCount(411, dockSlotCountForIconSize(411, viewModel.uiState.value.dockIconSizeDp))
        val rowHeightDp = iconSizeDp + DOCK_ITEM_VERTICAL_PADDING_DP
        val oneRowMaxDp = rowHeightDp + 2 * SECTION_CARD_PADDING_DP
        val twoRowMaxDp =
            2 * rowHeightDp + DOCK_ITEM_SPACING_DP + 2 * SECTION_CARD_PADDING_DP
        assertTrue(
            "sparse 4-app two-row work dock should render past one row " +
                "(height=${workHeight}dp, oneRow=${oneRowMaxDp}dp)",
            workHeight > oneRowMaxDp + 2f,
        )
        assertTrue(
            "sparse work dock should stay within MAX_WORK_DOCK_ROWS=$MAX_WORK_DOCK_ROWS " +
                "(height=${workHeight}dp, twoRow=${twoRowMaxDp}dp)",
            workHeight <= twoRowMaxDp + 2f,
        )
    }

    @Test
    fun workDockCard_isHiddenWhenWorkProfileIsPaused() {
        val viewModel = composeRule.activity.viewModel
        viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake8")
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertIsDisplayed()

        // Flip the same work app into quiet mode — `isWorkProfileActive`
        // becomes false and the card disappears without restarting the
        // activity, mirroring `ACTION_MANAGED_PROFILE_UNAVAILABLE`.
        viewModel.markAsPausedWorkAppForTest("app.typelauncher.fake8")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertDoesNotExist()
        // The personal dock is unaffected.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun workDockSettings_switchIsHiddenWhenNoWorkProfile() {
        composeRule.activity.viewModel.openSettings()
        composeRule.waitForIdle()

        // No work-profile entries in the fake seed → no "Show work dock" row.
        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertDoesNotExist()
    }

    @Test
    fun workDockSettings_switchIsDisabledWhenWorkProfilePaused() {
        val viewModel = composeRule.activity.viewModel
        viewModel.markAsPausedWorkAppForTest("app.typelauncher.fake8")
        composeRule.waitForIdle()
        viewModel.openSettings()
        composeRule.waitForIdle()

        // `isWorkProfileConfigured` true → row visible; `isWorkProfileActive`
        // false → Switch reports disabled. Tapping the row surfaces the
        // "Work profile is off" toast.
        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertIsNotEnabled()
    }

    @Test
    fun workDockSettings_switchIsInteractableWhenWorkProfileActive() {
        val viewModel = composeRule.activity.viewModel
        viewModel.markAsActiveWorkAppForTest("app.typelauncher.fake8")
        viewModel.setWorkDockEnabled(true)
        composeRule.waitForIdle()
        viewModel.openSettings()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(WORK_DOCK_ENABLED_SWITCH_TAG).assertIsOn()
    }

    /**
     * Seeds extra launcher activities in the shadow `PackageManager` for the
     * given `(label, packageName)` pairs and triggers a viewmodel reload so
     * the personal copies land in `installedApps`, then clones each one as
     * a work-profile entry via
     * [LauncherViewModel.addWorkProfileCopyForTest]. This mirrors the
     * on-device shape: a popular app like Gmail typically exists in both
     * profiles, the personal copy stays in the main app list, and the work
     * copy is what the work dock pins. The default fake seed only installs
     * `app.typelauncher.fakeN` packages (none in `POPULAR_APP_PACKAGES`),
     * so without this helper the prefill's tier (c) has nothing to land.
     */
    private fun addPopularWorkApps(apps: List<Pair<String, String>>) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(
            ApplicationProvider.getApplicationContext<android.content.Context>().packageManager,
        )
        apps.forEach { (label, packageName) ->
            val componentName = ComponentName(packageName, "$packageName.LaunchActivity")
            val resolveInfo = ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = componentName.className
                }
            }
            @Suppress("DEPRECATION")
            packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
            packageManager.addActivityIcon(
                componentName,
                ColorDrawable(OVERFLOW_ICON_COLORS[0]),
            )
        }
        composeRule.activity.viewModel.reloadInstalledAppsForTest()
        composeRule.waitForIdle()
        val workUser = workUserHandle()
        apps.forEach { (_, packageName) ->
            composeRule.activity.viewModel.addWorkProfileCopyForTest(packageName, workUser)
        }
        composeRule.waitForIdle()
    }

    private fun assertVisibleApps(vararg names: String) {
        val actual = composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name }
        assertEquals(names.toList(), actual)
        names.forEach { name ->
            composeRule.onNodeWithTag("$APP_ROW_TAG:$name").assertExists()
        }
    }

    private fun SemanticsNodeInteraction.carouselVirtualPage(): Int =
        fetchSemanticsNode().config[CarouselVirtualPageKey]

    // The hosted widget card resolves its AppWidgetProviderInfo off the main
    // thread, so a bare waitForIdle can race the lookup: these unbound test
    // widgets only render their "Widget unavailable" surface (and its
    // long-press menu) once the null resolution lands. Await it before
    // long-pressing or screenshotting widget cards.
    private fun awaitUnavailableWidgetCards(count: Int) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Widget unavailable").fetchSemanticsNodes().size == count
        }
    }

    private fun saveScreenshot(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    /**
     * Capture the whole device screen, compositing every window, so popups such
     * as the [androidx.compose.material3.DropdownMenu] action menus appear in the
     * snapshot. [saveScreenshot] draws only the activity decorView and silently
     * drops popup windows, which is why it can't be used for the menus.
     */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
        captureScreenRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun assertStandardLauncherFlags(intent: Intent) {
        val launcherFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals(launcherFlags, intent.flags and launcherFlags)
    }

    private fun assertSettingsAppInfoFlags(intent: Intent) {
        val appInfoFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        assertEquals(appInfoFlags, intent.flags and appInfoFlags)
    }

    private fun fakeWidgetProvider(
        appName: String,
        label: String,
        packageName: String = "app.typelauncher.fakewidget",
        profile: UserHandle = Process.myUserHandle(),
        isWorkProvider: Boolean = false,
    ): WidgetProvider =
        WidgetProvider(
            appName = appName,
            label = label,
            componentName = ComponentName(packageName, "$label.Provider"),
            profile = profile,
            icon = null,
            appIcon = null,
            minWidth = 112,
            minHeight = 56,
            targetCellWidth = 2,
            targetCellHeight = 1,
            previewImage = null,
            isWorkProvider = isWorkProvider,
        )

    /**
     * Encodes user 10 (the conventional first managed-profile user on Android).
     * UserHandle.getUserHandleForUid(uid) extracts the user id as `uid / 100000`.
     */
    private fun workUserHandle(): UserHandle = UserHandle.getUserHandleForUid(WORK_USER_TEST_UID)

    private fun addFakeLauncherApps(labels: List<String>) {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
        labels.forEachIndexed { index, label ->
            val packageName = "app.typelauncher.overflow$index"
            val resolveInfo = ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.LaunchActivity"
                }
            }
            @Suppress("DEPRECATION")
            packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
        }
    }

    private class SeedLauncherStateRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val application = RuntimeEnvironment.getApplication()
                    listOf(
                        "docked_apps",
                        "work_docked_apps",
                        "dock_settings",
                        "app_launch_stats",
                        "widgets",
                        "app_metadata",
                        "hidden_apps",
                        "renamed_apps",
                    ).forEach { preferenceName ->
                        application
                            .getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    }
                    seedFakeLauncherApps()
                    if (description.methodName in listOf(
                            "screenshot_appListIconOnly_overflowingGrid",
                            "screenshot_appListIconOnly_overflowingGrid_pixel9ProWidth",
                        )
                    ) {
                        seedOverflowLauncherApps()
                        application
                            .getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putBoolean("app_list_icon_only", true)
                            .commit()
                    }
                    if (description.methodName == "dockAddButton_hiddenOnUpgradeInstall") {
                        // Simulate an upgrade install: a docked entry + the
                        // `dock_prefilled` flag latched. Mirrors what
                        // `LauncherViewModel`'s cold-start sees on a device
                        // that upgraded from a build pre-dating `prefillDock`.
                        // The hint's `show_add_button_hint` key is left
                        // unset so it returns its default `false`.
                        application
                            .getSharedPreferences("docked_apps", android.content.Context.MODE_PRIVATE)
                            .edit()
                            .putString("docked_app_ids", "upgrade-fixture-id")
                            .putString("docked_app_positions", "upgrade-fixture-id\t0\t0")
                            .putBoolean("dock_prefilled", true)
                            .commit()
                    }
                    if (description.methodName in listOf(
                            "screenshot_landscape_dockNoSearchBox_hidesBoxWithoutTypingHeadroom",
                            "landscape_pullUpDoesNotRevealSearchWithoutTypingHeadroom",
                        )
                    ) {
                        // Pin the typing-headroom gate to the measured-keyboard
                        // path (rather than the fallback estimate) with a
                        // height that exceeds these windows' budgets: 600px at
                        // 420dpi = 228dp, over the ~181dp budget at h360 and
                        // the ~59dp budget at h240.
                        DockSettingsStore(application).keyboardReservation = KeyboardReservation(
                            bottomPx = 600,
                            configFingerprint = null,
                            source = KeyboardReservationSource.VisibleIme,
                        )
                    }
                    if (description.methodName == "screenshot_landscape_cachedKeyboard_doesNotReserveWithKeyboardDown") {
                        // A keyboard height already cached for this landscape size
                        // (wildcard fingerprint, so it applies under any config).
                        // A keyboard-down state must not reserve it — exercises the
                        // suppressed-state reservation gate.
                        DockSettingsStore(application).keyboardReservation = KeyboardReservation(
                            bottomPx = 600,
                            configFingerprint = null,
                            source = KeyboardReservationSource.VisibleIme,
                        )
                    }
                    base.evaluate()
                }
            }

        private fun seedFakeLauncherApps() {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packageManager = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
            ALL_FAKE_APP_NAMES.forEachIndexed { index, label ->
                // The real Type Launcher activity from the manifest already
                // surfaces with this label via `queryIntentActivities`, so a
                // fake "Type Launcher" entry would now show up alongside it
                // (dedup keys on `InstalledApp.id`, not on lowercased name).
                // Skip the fake seed and let the real activity stand in for
                // it — keeps the expected-result list unchanged.
                if (label == "Type Launcher") return@forEachIndexed
                val packageName = "app.typelauncher.fake$index"
                val componentName = ComponentName(packageName, "$packageName.LaunchActivity")
                val resolveInfo = ResolveInfo().apply {
                    nonLocalizedLabel = label
                    activityInfo = ActivityInfo().apply {
                        this.packageName = packageName
                        name = componentName.className
                    }
                }
                @Suppress("DEPRECATION")
                packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
                packageManager.addActivityIcon(componentName, ColorDrawable(OVERFLOW_ICON_COLORS[index % OVERFLOW_ICON_COLORS.size]))
            }
        }

        private fun seedOverflowLauncherApps() {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packageManager = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
            (1..60).forEach { index ->
                val label = "Overflow App %02d".format(index)
                val packageName = "app.typelauncher.overflow$index"
                val componentName = ComponentName(packageName, "$packageName.LaunchActivity")
                val resolveInfo = ResolveInfo().apply {
                    nonLocalizedLabel = label
                    activityInfo = ActivityInfo().apply {
                        this.packageName = packageName
                        name = componentName.className
                    }
                }
                @Suppress("DEPRECATION")
                packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
                packageManager.addActivityIcon(componentName, ColorDrawable(OVERFLOW_ICON_COLORS[index % OVERFLOW_ICON_COLORS.size]))
            }
        }
    }

    private class TestSearchPlaceholderSuffixRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val previous = System.getProperty(TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY)
                    System.setProperty(TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY, "")
                    try {
                        base.evaluate()
                    } finally {
                        if (previous == null) {
                            System.clearProperty(TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY)
                        } else {
                            System.setProperty(TEST_SEARCH_PLACEHOLDER_SUFFIX_PROPERTY, previous)
                        }
                    }
                }
            }
    }

    private fun todayAgendaSample(): List<AgendaEvent> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val tomorrow = today.plusDays(1)
        return listOf(
            AgendaEvent(
                title = "Standup",
                beginMillis = today.atTime(9, 30).atZone(zone).toInstant().toEpochMilli(),
                endMillis = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(),
                isAllDay = false,
                displayTime = "9:30 AM",
                eventId = 1L,
            ),
            AgendaEvent(
                title = "Design review",
                beginMillis = today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(),
                isAllDay = false,
                displayTime = "1:00 PM",
                eventId = 42L,
            ),
            AgendaEvent(
                title = "Workout",
                beginMillis = tomorrow.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = tomorrow.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
                isAllDay = false,
                displayTime = "7:00 AM",
                eventId = 7L,
            ),
        )
    }

    private fun SemanticsNodeInteraction.assertIsFocusedBySemantics(): SemanticsNodeInteraction {
        assertTrue(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true).matches(fetchSemanticsNode()))
        return this
    }

    private companion object {
        val ALL_FAKE_APP_NAMES = listOf(
            "Browser",
            "Calculator",
            "Calendar",
            "Camera",
            "Clock",
            "Files",
            "Settings",
            "Type Launcher",
            "Work Calendar",
        )

        val OVERFLOW_ICON_COLORS = intArrayOf(
            Color.rgb(0x1A, 0x73, 0xE8),
            Color.rgb(0xD9, 0x30, 0x25),
            Color.rgb(0x18, 0x80, 0x38),
            Color.rgb(0xF9, 0xAB, 0x00),
        )

        const val WORK_USER_TEST_UID = 1_010_000
    }
}

/**
 * A [CoroutineDispatcher] that runs each block inline on the caller's thread and
 * records that it was used. Lets a test assert that work MainActivity intends to
 * push onto its injectable ioDispatcher actually goes there — deterministically,
 * with no background thread to await.
 */
private class RecordingCoroutineDispatcher : CoroutineDispatcher() {
    var dispatched = false
        private set

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatched = true
        block.run()
    }
}
