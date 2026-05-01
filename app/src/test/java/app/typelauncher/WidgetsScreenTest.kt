package app.typelauncher

import android.content.ComponentName
import android.os.Process
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun widgetPicker_rendersProviderPreviewsOnAppExpansion() {
        val provider = fakeWidgetProvider(appName = "Clock", label = "Analog clock")

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(provider),
                    isAddingWidget = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithText("Add widget").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${provider.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${provider.id}").assertDoesNotExist()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${provider.id}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${provider.id}", useUnmergedTree = true).assertExists()
    }

    @Test
    fun widgetPicker_filtersAppGroupsByName() {
        val clock = fakeWidgetProvider(appName = "Clock", label = "Analog clock")
        val notes = fakeWidgetProvider(appName = "Notes", label = "Sticky note")

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(clock, notes),
                    isAddingWidget = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Notes").assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextInput("clo")
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Notes").assertDoesNotExist()

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("zzz")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_CLEAR_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Notes").assertIsDisplayed()
    }

    @Test
    fun widgetPicker_filtersAppGroupsWithLauncherFuzzyMatch() {
        // Same anchored fuzzy rules as the app list: capital letters anchor mid-name,
        // and lowercase interior letters do not. See LauncherQueryMatchTest.
        val bofa = fakeWidgetProvider(appName = "BofA", label = "Balance")
        val onePassword = fakeWidgetProvider(appName = "1password", label = "Vault")
        val googleMail = fakeWidgetProvider(appName = "Google Mail", label = "Inbox")

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(bofa, onePassword, googleMail),
                    isAddingWidget = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextInput("ba")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:BofA").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:1password").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Google Mail").assertDoesNotExist()

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("m")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Google Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:1password").assertDoesNotExist()

        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("ass")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No matching apps").assertIsDisplayed()
    }

    @Test
    fun widgetPicker_showsPaddedNoteForProvidersBelowHeightFloor() {
        val tiny = fakeWidgetProvider(appName = "Tiny", label = "Tiny widget", minHeight = 56)

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(tiny),
                    isAddingWidget = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithText("Very small, will be padded").assertDoesNotExist()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Tiny").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Very small, will be padded").assertIsDisplayed()
    }

    @Test
    fun widgetPicker_omitsPaddedNoteWhenProviderHeightMeetsFloor() {
        val tall = fakeWidgetProvider(appName = "Tall", label = "Tall widget", minHeight = 600)

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(tall),
                    isAddingWidget = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Tall").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Very small, will be padded").assertDoesNotExist()
    }

    @Test
    fun widgetPicker_showsLoadingIndicatorBeforeWidgetsResolve() {
        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = emptyList(),
                    isAddingWidget = true,
                    isLoadingAvailableWidgets = true,
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        composeRule.onNodeWithTag(WIDGET_PICKER_LOADING_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(WIDGET_PICKER_LIST_TAG).assertDoesNotExist()
    }

    private fun fakeWidgetProvider(
        appName: String,
        label: String,
        minHeight: Int = 56,
    ): WidgetProvider =
        WidgetProvider(
            appName = appName,
            label = label,
            componentName = ComponentName("app.typelauncher.fakewidget", "$label.Provider"),
            profile = Process.myUserHandle(),
            icon = null,
            appIcon = null,
            minWidth = 112,
            minHeight = minHeight,
            targetCellWidth = 2,
            targetCellHeight = 1,
            previewImage = null,
        )
}
