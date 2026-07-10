package app.typelauncher

import android.content.ComponentName
import android.os.Process
import android.os.UserHandle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
    fun widgetPicker_autoLoadsProviderPreviewsWhenAppExpands() {
        val month = fakeWidgetProvider(appName = "Calendar", label = "Calendar month view")
        val schedule = fakeWidgetProvider(appName = "Calendar", label = "Calendar schedule")

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(month, schedule),
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
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${month.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${schedule.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${month.id}").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${schedule.id}").assertDoesNotExist()

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${month.id}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PROVIDER_ROW_TAG:${schedule.id}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${month.id}", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("$WIDGET_PREVIEW_TAG:${schedule.id}", useUnmergedTree = true).assertExists()
    }

    @Test
    fun widgetPicker_showsSingularAndPluralWidgetCounts() {
        val month = fakeWidgetProvider(appName = "Calendar", label = "Calendar month view")
        val schedule = fakeWidgetProvider(appName = "Calendar", label = "Calendar schedule")
        val clock = fakeWidgetProvider(appName = "Clock", label = "Analog clock")

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(month, schedule, clock),
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

        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar").performScrollTo()
        composeRule.onNodeWithText("2 widgets").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Clock").performScrollTo()
        composeRule.onNodeWithText("1 widget").assertIsDisplayed()
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
    fun widgetPicker_filtersAppGroupsWithLauncherTieredMatch() {
        // Three-tier matcher: prefix → anchored fuzzy → substring. See
        // LauncherQueryMatchTest.
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

        // "ba" anchors at B for BofA (anchored tier); 1password and Google Mail
        // have no anchor or substring match and stay hidden.
        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextInput("ba")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:BofA").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:1password").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Google Mail").assertDoesNotExist()

        // "m" anchors at the capital M in "Google Mail"; "1password" has no 'm'
        // anywhere so it doesn't substring-match either.
        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("m")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Google Mail").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:1password").assertDoesNotExist()

        // "ass" doesn't anchor against any of the three (the 'a' inside
        // "1password" / "BofA" is mid-word lowercase or unreachable), but
        // "1password" contains the literal substring, so the picker now shows
        // it via the substring tier instead of "No matching apps".
        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("ass")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:1password").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:BofA").assertDoesNotExist()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Google Mail").assertDoesNotExist()

        // A query with no tier match anywhere still shows the empty state.
        composeRule.onNodeWithTag(WIDGET_PICKER_FILTER_TAG).performTextReplacement("zzz")
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

        composeRule.onNodeWithText("Very small, will be padded")
            .performScrollTo()
            .assertIsDisplayed()
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

    @Test
    fun widgetPicker_keepsWorkProfileProviderGroupSeparateWithoutCustomBadge() {
        val personal = fakeWidgetProvider(appName = "Calendar", label = "Calendar month")
        val work = fakeWidgetProvider(
            appName = "Calendar",
            label = "Calendar work month",
            packageName = "app.typelauncher.fakewidget.work",
            profile = workUserHandle(),
            isWorkProvider = true,
        )

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = emptyList(),
                    availableWidgets = listOf(personal, work),
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

        // Personal and work groups for the same display name still render as
        // separate sections so binds use the provider's original profile.
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_APP_ROW_TAG:Calendar|work")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag(WIDGET_WORK_BADGE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun widgetPage_resetsScrollToTopWhenItStopsBeingCurrentPage() {
        // A page that survives a trip away from the widgets carousel keeps its
        // remembered LazyListState, so a page left scrolled down would hide the
        // first widget above the fold on return. The reset must fire when the
        // page stops being current (while it is off-screen), so it is already
        // at the top before the carousel translates it back on — resetting only
        // once it is current again would snap the list after the page is already
        // visible, producing a jump.
        val widgetIds = (1..30).toList()
        var isCurrentPage by mutableStateOf(true)

        composeRule.setContent {
            TypeLauncherTheme {
                WidgetsScreen(
                    widgetIds = widgetIds,
                    availableWidgets = emptyList(),
                    isAddingWidget = false,
                    // No host binds, so every card renders as the lightweight
                    // "Widget unavailable" surface — enough to make the column
                    // overflow and scroll without a real AppWidgetHost.
                    appWidgetHost = null,
                    appWidgetManager = null,
                    innerPadding = PaddingValues(),
                    isCurrentPage = isCurrentPage,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                )
            }
        }

        // Scroll the bottom widget into view; the first widget leaves the fold.
        // A LazyColumn recycles far-off items out of composition entirely, so
        // the off-screen card is scrolled to by node (not the un-composed item's
        // own performScrollTo) and the card that leaves the fold is asserted
        // absent rather than "not displayed".
        composeRule.onNodeWithTag(WIDGETS_SCREEN_TAG)
            .performScrollToNode(hasTestTag("$WIDGET_CARD_TAG:30"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").assertDoesNotExist()

        // Leaving the page (it stops being current) must reset it to the top
        // while it is off-screen — asserted here before it becomes current
        // again, so the reset cannot be the post-reveal snap the carousel's
        // settle-then-flip ordering would otherwise produce.
        isCurrentPage = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:30").assertDoesNotExist()

        // Returning to the page keeps it at the top.
        isCurrentPage = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:1").assertIsDisplayed()
        composeRule.onNodeWithTag("$WIDGET_CARD_TAG:30").assertDoesNotExist()
    }

    private fun fakeWidgetProvider(
        appName: String,
        label: String,
        minHeight: Int = 56,
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
            minHeight = minHeight,
            targetCellWidth = 2,
            targetCellHeight = 1,
            previewImage = null,
            isWorkProvider = isWorkProvider,
        )

    /**
     * Returns a `UserHandle` whose hash differs from the personal-profile
     * handle, so two `WidgetProvider`s with the same component but different
     * profiles produce distinct IDs (and aren't deduped by `distinctBy { id }`
     * if a future change reintroduces it). User 10 is the conventional first
     * managed-profile user ID on Android.
     */
    private fun workUserHandle(): UserHandle = UserHandle.getUserHandleForUid(WORK_USER_TEST_UID)

    private companion object {
        // Encodes user 10 (the conventional first managed-profile user on Android).
        // UserHandle.getUserHandleForUid(uid) extracts the user id as `uid / 100000`.
        const val WORK_USER_TEST_UID = 1_010_000
    }
}
