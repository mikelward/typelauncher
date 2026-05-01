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
    fun widgetPicker_rendersPreviewsOnlyAfterProviderExpansion() {
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
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${provider.id}").assertDoesNotExist()

        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${provider.id}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${provider.id}", useUnmergedTree = true).assertExists()
    }

    private fun fakeWidgetProvider(appName: String, label: String): WidgetProvider =
        WidgetProvider(
            appName = appName,
            label = label,
            componentName = ComponentName("app.typelauncher.fakewidget", "$label.Provider"),
            profile = Process.myUserHandle(),
            icon = null,
            appIcon = null,
            minWidth = 112,
            minHeight = 56,
            targetCellWidth = 2,
            targetCellHeight = 1,
            previewImage = null,
        )
}
