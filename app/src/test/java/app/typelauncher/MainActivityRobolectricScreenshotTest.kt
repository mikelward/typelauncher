package app.typelauncher

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRobolectricScreenshotTest {
    val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(SeedLauncherStateRule())
        .around(composeRule)

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
        composeRule.onNodeWithText("Allow calendar").assertIsDisplayed()
        composeRule.onNodeWithText("Agenda").assertDoesNotExist()
        composeRule.onNodeWithText("Swipe right to return home").assertDoesNotExist()
        composeRule.onNodeWithText("Show calendar events").assertDoesNotExist()
        composeRule.onNodeWithTag(AGENDA_EVENTS_TAG).assertDoesNotExist()

        saveScreenshot("compose_agenda_permission_robolectric.png")
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

        composeRule.onNodeWithTag(ADD_WIDGET_CARD_TAG).performClick()
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
    fun firstVisibleAppRow_isSelectedAsActiveLaunchTarget() {
        composeRule.onNodeWithTag("$APP_ROW_TAG:Browser").assertIsSelected()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsNotSelected()

        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).performTextInput("ca")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").assertIsSelected()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calendar").assertIsNotSelected()
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
        composeRule.onNodeWithText("Show dock").assertIsDisplayed()
        composeRule.onNodeWithText("Dock icons visible: 4").assertIsDisplayed()

        composeRule.onNodeWithTag(SETTINGS_DONE_BUTTON_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(HOME_SCREEN_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertDoesNotExist()
    }

    @Test
    fun settingsDockToggleHidesDockOnHomeButKeepsSettingsPreview() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SETTINGS_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_ENABLED_SWITCH_TAG).assertIsOff()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        assertEquals(false, viewModel.uiState.value.isDockEnabled)

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

        val defaultIconBounds = composeRule.onNodeWithTag("$APP_ICON_TAG:Calculator").getBoundsInRoot()
        val defaultIconSize = defaultIconBounds.right - defaultIconBounds.left
        viewModel.setDockVisibleIconCount(6)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dock icons visible: 6").assertIsDisplayed()
        val largerIconBounds = composeRule.onNodeWithTag("$APP_ICON_TAG:Calculator").getBoundsInRoot()
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
    fun dockedAppsSortAsNegativeRankAtBottomOfUnfilteredList() {
        val viewModel = composeRule.activity.viewModel
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.setQuery("calculator")
        viewModel.launchApp(viewModel.uiState.value.filteredApps.single())
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        assertEquals(
            listOf("Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar", "Calculator"),
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

        composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").performTouchInput { longClick() }
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

        assertEquals(
            listOf("Browser", "Calendar", "Camera", "Clock", "Files", "Settings", "Type Launcher", "Work Calendar", "Calculator"),
            composeRule.activity.viewModel.uiState.value.filteredApps.map { it.name },
        )
    }

    @Test
    fun appRows_alignWithDockIcons() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        composeRule.waitForIdle()

        val appIconLeft = composeRule.onNodeWithTag("$APP_ROW_TAG:Calculator").getBoundsInRoot().left
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
    fun dockedList_isFilteredBySameSearchQuery() {
        val viewModel = composeRule.activity.viewModel
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Calculator" }, maxDockedApps = 6)
        viewModel.toggleDock(viewModel.uiState.value.filteredApps.first { it.name == "Browser" }, maxDockedApps = 6)

        viewModel.setQuery("cal")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertDoesNotExist()

        viewModel.setQuery("browser")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Browser").assertIsDisplayed()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:Calculator").assertDoesNotExist()
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
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val file = File("build/reports/robolectric-screenshots/$name")
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("screenshot is not empty", file.length() > 0L)
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
                    listOf("docked_apps", "dock_settings", "app_launch_stats", "widgets").forEach { preferenceName ->
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
