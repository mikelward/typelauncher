package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.app.role.RoleManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRobolectricScreenshotTest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SeedLauncherStateRule())
        .around(composeRule)

    @Before
    fun awaitInitialAppLoad() {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            !composeRule.activity.viewModel.uiState.value.isLoadingApps
        }
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
        assertEquals(LauncherScreen.Agenda, viewModel.uiState.value.screen)

        composeRule.activity.runOnUiThread {
            composeRule.activity.handleLauncherIntent(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            )
        }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, viewModel.uiState.value.screen)
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
        assertEquals(LauncherScreen.Home, viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun swipingMovesFromHomeToWidgetsToAgendaAndWrapsAround() {
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeRight() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Widgets, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Agenda, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(LauncherScreen.Home, composeRule.activity.viewModel.uiState.value.screen)
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
        assertEquals(LauncherScreen.Widgets, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(afterFirstSwipePage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Agenda, composeRule.activity.viewModel.uiState.value.screen)
        composeRule.onNodeWithTag(AGENDA_SCREEN_TAG).assertIsDisplayed()
    }

    @Test
    fun fastAppListFlingAdvancesOnlyOneScreen() {
        val carousel = composeRule.onNodeWithTag(CAROUSEL_TAG)
        val startPage = carousel.carouselVirtualPage()

        composeRule.onNodeWithTag(APPS_LIST_TAG).performTouchInput { swipeLeft(durationMillis = 1) }
        composeRule.waitForIdle()

        assertEquals(startPage + 1, carousel.carouselVirtualPage())
        assertEquals(LauncherScreen.Widgets, composeRule.activity.viewModel.uiState.value.screen)
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
        composeRule.onNodeWithText("Choose a widget for the home screen").assertIsDisplayed()
        composeRule.onNodeWithText("Done").assertIsDisplayed()
        assertTrue(composeRule.activity.viewModel.uiState.value.isAddingWidget)
        composeRule.onNodeWithText("Done").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WIDGET_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun widgetLongPress_showsRemoveActionAndRemovesWidget() {
        composeRule.activity.viewModel.addWidget(42)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:42").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$REMOVE_WIDGET_ACTION_TAG:42").assertIsDisplayed()
        saveScreenshot("compose_widget_remove_menu_robolectric.png")
        composeRule.onNodeWithTag("$REMOVE_WIDGET_ACTION_TAG:42").performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Int>(), composeRule.activity.viewModel.uiState.value.widgetIds)
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:42").assertDoesNotExist()
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
        composeRule.onNodeWithTag(APP_LIST_ICON_ONLY_SWITCH_TAG).performClick()
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
        composeRule.onNodeWithContentDescription("Clear search text").assertDoesNotExist()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Clear search text").assertIsDisplayed()
    }

    @Test
    fun settingsButtonOpensSettingsAndDoneReturnsHome() {
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Icon-only app list").assertIsDisplayed()
        composeRule.onNodeWithTag(APP_LIST_ICON_ONLY_SWITCH_TAG).assertIsOff()
        composeRule.onNodeWithText("Show dock").assertIsDisplayed()
        composeRule.onNodeWithText("Dock icons visible: 4").assertIsDisplayed()
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
        saveScreenshot("compose_settings_overflow_menu_robolectric.png")
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
        saveScreenshot("compose_settings_about_dialog_robolectric.png")

        composeRule.onNodeWithTag(SETTINGS_ABOUT_DIALOG_DISMISS_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_ABOUT_DIALOG_TAG).assertDoesNotExist()
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
    fun appListIconOnlySettingShowsDockStyleIconsWithoutNames() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_ICON_ONLY_SWITCH_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APP_LIST_ICON_ONLY_SWITCH_TAG).assertIsOn()
        assertEquals(true, viewModel.uiState.value.isAppListIconOnly)

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        // Calculator is docked, so it's excluded from the main list entirely
        // and only renders in the dock row. Compare a non-docked app's icon
        // to the docked Calculator icon to verify icon-only mode matches the
        // dock's icon size.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Browser").assertIsDisplayed()

        val appIconBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_ICON_TAG:Browser").getBoundsInRoot()
        val dockIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator").getBoundsInRoot()
        assertEquals(dockIconBounds.right - dockIconBounds.left, appIconBounds.right - appIconBounds.left)
        assertEquals(dockIconBounds.bottom - dockIconBounds.top, appIconBounds.bottom - appIconBounds.top)

        saveScreenshot("compose_home_icon_only_app_list_robolectric.png")
    }

    @Test
    fun appListSortOrderToggleSortsAlphabeticallyAndPersistsRanks() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.launchApp(calculator)
        viewModel.launchApp(calculator)
        composeRule.waitForIdle()

        assertEquals(AppListSortOrder.Usage, viewModel.uiState.value.appListSortOrder)
        assertEquals("Calculator", viewModel.uiState.value.filteredApps.first().name)

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(APP_LIST_SORT_ALPHABETICAL_SWITCH_TAG).assertIsOff()
        composeRule.onNodeWithTag(APP_LIST_SORT_ALPHABETICAL_SWITCH_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(APP_LIST_SORT_ALPHABETICAL_SWITCH_TAG).assertIsOn()
        assertEquals(AppListSortOrder.Alphabetical, viewModel.uiState.value.appListSortOrder)

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val orderedNames = viewModel.uiState.value.filteredApps.map { it.name }
        assertEquals(orderedNames.sortedWith(String.CASE_INSENSITIVE_ORDER), orderedNames)
    }

    @Test
    fun settingsDockToggleHidesDockOnHomeAndPreviewExpandsAppList() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        val enabledPreviewBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val enabledPreviewHeight = enabledPreviewBounds.bottom - enabledPreviewBounds.top
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).assertIsOff()
        assertEquals(false, viewModel.uiState.value.isDockEnabled)
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
        val disabledPreviewBounds = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot()
        val disabledPreviewHeight = disabledPreviewBounds.bottom - disabledPreviewBounds.top
        assertTrue(
            "disabled dock preview gives dock space to the app list",
            disabledPreviewHeight > enabledPreviewHeight,
        )
        saveScreenshot("compose_settings_preview_dock_disabled_robolectric.png")

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun increasingDockVisibleIconCountShrinksLiveSettingsPreview() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.toggleDock(calculator, maxDockedApps = 6)
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        val defaultIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator").getBoundsInRoot()
        val defaultIconSize = defaultIconBounds.right - defaultIconBounds.left
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dock icons visible: 6").assertIsDisplayed()
        val largerIconBounds = composeRule.onNodeWithTag("$DOCK_APP_ICON_TAG:Calculator").getBoundsInRoot()
        val smallerIconSize = largerIconBounds.right - largerIconBounds.left
        assertTrue("preview icon shrinks to fit more visible dock icons", smallerIconSize < defaultIconSize)

        saveScreenshot("compose_settings_dock_preview_robolectric.png")
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
    fun dockedAppsAreExcludedFromUnfilteredListWhileDockEnabled() {
        val viewModel = composeRule.activity.viewModel
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
    fun appActionsMenuAppInfo_opensAndroidAppInfoForThatApp() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("calendar")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calendar").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calendar").performClick()
        composeRule.waitForIdle()

        val startedIntent = shadowOf(composeRule.activity).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, startedIntent.action)
        assertEquals(android.net.Uri.parse("package:app.typelauncher.fake2"), startedIntent.data)
    }

    @Test
    fun dockingApp_addsItToDockAndOffersUndock() {
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("cal")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Calculator").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        assertTrue(composeRule.activity.viewModel.uiState.value.dockedApps.single().isDocked)
        // Once docked, Calculator drops out of the typed search results — long
        // press the dock entry instead to verify the Undock action surfaces.
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertDoesNotExist()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.onNodeWithText("Undock").assertIsDisplayed()
    }

    @Test
    fun dockLongPress_showsAppInfoUndockAndResetRankActions() {
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
    fun appRows_alignWithDockIcons() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        // Calculator is docked, so it isn't in the app row list anymore. Pick
        // any other (non-docked) app to verify the column alignment between
        // app rows and dock icons.
        val appIconLeft = composeRule.onNodeWithTag("$APP_ROW_TAG:Browser").getBoundsInRoot().left
        val dockIconLeft = composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").getBoundsInRoot().left
        assertEquals(appIconLeft, dockIconLeft)
    }

    @Test
    fun dockCard_staysAtBottomAfterAppList() {
        composeRule.waitForIdle()

        val appsBottom = composeRule.onNodeWithTag(APPS_CARD_TAG).getBoundsInRoot().bottom
        val dockTop = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().top
        assertTrue("dock is below the apps list", dockTop > appsBottom)
    }

    @Test
    fun undockingDockedApp_restoresDockHintWhenEmpty() {
        val viewModel = composeRule.activity.viewModel
        val calculator = viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }
        viewModel.toggleDock(calculator, maxDockedApps = 6)
        composeRule.waitForIdle()
        viewModel.toggleDock(viewModel.uiState.value.dockedApps.single(), maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_HINT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Long press apps to dock").assertIsDisplayed()
    }

    @Test
    fun dockedList_isNotFilteredBySearchQuery() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Browser" }, maxDockedApps = 6)

        // Typing a query that only matches one of the docked apps must leave
        // both icons visible — the dock is meant to be a stable, always-tappable
        // row that the user pinned by hand, so reordering or hiding entries
        // while typing would defeat the point. The recents row has the same
        // unfiltered contract.
        viewModel.setQuery("cal")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()

        viewModel.setQuery("browser")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()

        viewModel.setQuery("zzz-no-match")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()
    }

    @Test
    fun launchActiveApp_fallsBackToDockedMatchWhenMainListHasNone() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        // "calc" only matches the docked Calculator; the main list is empty.
        // Enter must still launch Calculator via the dock fallback even though
        // the dock is no longer pre-filtered to expose the matching entry.
        viewModel.setQuery("calc")
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), viewModel.uiState.value.filteredApps.map { it.name })

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
    fun longPressRecentsApp_showsAppInfoDockAndResetRankActions() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").performTouchInput { longClick() }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_INFO_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$TOGGLE_DOCK_ACTION_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:Calculator").assertIsDisplayed()
    }

    @Test
    fun openingSettings_closesRecentsPanel() {
        val viewModel = composeRule.activity.viewModel
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

        viewModel.setRecentsOpen(true)
        composeRule.waitForIdle()

        val dockBottom = composeRule.onNodeWithTag(DOCK_CARD_TAG).getBoundsInRoot().bottom
        val recentsTop = composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).getBoundsInRoot().top
        assertTrue("recents card sits below the dock card", recentsTop >= dockBottom)
    }

    @Test
    fun showRecentsSetting_keepsRecentsCardVisibleWithoutDragGesture() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        assertFalse(viewModel.uiState.value.isRecentsOpen)
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertDoesNotExist()

        viewModel.setRecentsAlwaysShown(true)
        composeRule.waitForIdle()

        // The setting alone is enough — no drag-up needed — and the card stays
        // visible after launching another app (the panel's auto-close that
        // applies to the gesture-driven mode doesn't override the setting).
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:Calculator").assertIsDisplayed()
        viewModel.setQuery("browser")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun showRecentsSetting_isOrthogonalToDockToggle() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setRecentsAlwaysShown(true)
        viewModel.setDockEnabled(false)
        composeRule.waitForIdle()

        // Dock is hidden, but the recents card still renders independently.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun showRecentsToggle_inSettings_revealsRecentsInPreview() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SHOW_RECENTS_SWITCH_TAG).assertIsOff()
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(SHOW_RECENTS_SWITCH_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SHOW_RECENTS_SWITCH_TAG).assertIsOn()
        // The preview now mirrors the home layout: dock above, recents below.
        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
    }

    @Test
    fun dockOverflow_showsEndChevronHint() {
        val viewModel = composeRule.activity.viewModel
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.toggleDock(app, maxDockedApps = 1)
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_SCROLL_END_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun dockWithoutOverflow_hidesScrollChevrons() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun recentsOverflow_showsStartChevronHint() {
        val viewModel = composeRule.activity.viewModel
        viewModel.uiState.value.filteredApps.take(8).forEach { app ->
            viewModel.launchApp(app)
        }
        viewModel.setRecentsAlwaysShown(true)
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
        viewModel.setRecentsAlwaysShown(true)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_RECENTS_CARD_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun workAppBadge_isShownForWorkApps() {
        composeRule.activity.viewModel.setQuery("work")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Work Calendar").assertIsDisplayed()
        assertEquals(listOf("Work Calendar"), composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name })
        composeRule.onNodeWithText("app.typelauncher.fake8").assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ICON_TAG:Work Calendar", useUnmergedTree = true).assertExists()

        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.single(), maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DOCK_APP_TAG:Work Calendar").assertIsDisplayed()
        assertTrue(viewModel.uiState.value.dockedApps.single().name.startsWith("Work"))
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

    private fun saveScreenshot(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun assertStandardLauncherFlags(intent: Intent) {
        val launcherFlags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        assertEquals(launcherFlags, intent.flags and launcherFlags)
    }

    private class SeedLauncherStateRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val application = RuntimeEnvironment.getApplication()
                    listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets", "app_metadata").forEach { preferenceName ->
                        application
                            .getSharedPreferences(preferenceName, android.content.Context.MODE_PRIVATE)
                            .edit()
                            .clear()
                            .commit()
                    }
                    seedFakeLauncherApps()
                    base.evaluate()
                }
            }

        private fun seedFakeLauncherApps() {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val packageManager = shadowOf(ApplicationProvider.getApplicationContext<android.content.Context>().packageManager)
            ALL_FAKE_APP_NAMES.forEachIndexed { index, label ->
                val packageName = "app.typelauncher.fake$index"
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
                calendarColor = 0xFF1A73E8.toInt(),
            ),
            AgendaEvent(
                title = "Design review",
                beginMillis = today.atTime(13, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(),
                isAllDay = false,
                displayTime = "1:00 PM",
                eventId = 42L,
                calendarColor = 0xFFD50000.toInt(),
            ),
            AgendaEvent(
                title = "Workout",
                beginMillis = tomorrow.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                endMillis = tomorrow.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
                isAllDay = false,
                displayTime = "7:00 AM",
                eventId = 7L,
                calendarColor = 0xFF7CB342.toInt(),
            ),
        )
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
    }
}
