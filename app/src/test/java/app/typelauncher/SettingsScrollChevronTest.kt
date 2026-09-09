package app.typelauncher

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings wears the same overflow chevrons as the apps list: a bottom one
 * while more of the page is below the viewport, a top one once the user has
 * scrolled past the start, and a tap on either pages the page in that
 * direction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SettingsScrollChevronTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun atTheTopOfThePage_onlyTheBottomChevronShows() {
        showSettings()

        composeRule.onNodeWithTag(SETTINGS_SCROLL_BOTTOM_CHEVRON_TAG).assertExists()
        composeRule.onNodeWithTag(SETTINGS_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
    }

    @Test
    fun atTheEndOfThePage_onlyTheTopChevronShows() {
        showSettings()

        scrollToEnd()

        composeRule.onNodeWithTag(SETTINGS_SCROLL_TOP_CHEVRON_TAG).assertExists()
        composeRule.onNodeWithTag(SETTINGS_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()
    }

    /**
     * Indicators, not controls: a tap band here would lie over the page's own
     * content — Settings has full-width buttons whose horizontal center is
     * exactly where the chevron sits — and would take presses meant for it.
     */
    @Test
    fun theChevrons_areNotTapTargets() {
        showSettings()

        composeRule.onNodeWithTag(SETTINGS_SCROLL_BOTTOM_CHEVRON_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick).not())
    }

    /**
     * The two chevrons are separate buttons to a screen reader, so sharing one
     * description would leave a user partway down the page unable to tell which
     * of them pages back and which pages on.
     */
    @Test
    fun theTwoChevrons_announceTheirOwnDirections() {
        showSettings()

        // One node per direction, not two: the description rides the icon, and
        // there is no second tap-band node repeating it as another TalkBack
        // focus stop.
        composeRule.onNodeWithTag(SETTINGS_SCROLL_BOTTOM_CHEVRON_TAG)
            .assert(describedAs("Scroll down for more"))

        scrollToEnd()

        composeRule.onNodeWithTag(SETTINGS_SCROLL_TOP_CHEVRON_TAG)
            .assert(describedAs("Scroll up for more"))
    }

    /**
     * The preview renders the real cards, but nothing in it scrolls, so a
     * chevron there would offer to page a list that cannot move — vertically on
     * the app list and dock, horizontally on the recents row.
     */
    @Test
    fun theDockPreview_showsNoChevronsOfItsOwn() {
        showSettings()
        scrollToEnd()

        composeRule.onNodeWithTag(APPS_LIST_SCROLL_TOP_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_START_CHEVRON_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_RECENTS_SCROLL_END_CHEVRON_TAG).assertDoesNotExist()
    }

    /** Scrolls far past the end; the scrollable clamps to its own maximum. */
    private fun scrollToEnd() {
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        composeRule.waitForIdle()
    }

    private fun describedAs(description: String) = SemanticsMatcher(
        "content description is \"$description\"",
    ) { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(description)
    }

    private fun showSettings() {
        composeRule.setContent {
            TypeLauncherTheme {
                SettingsScreen(
                    state = LauncherUiState(),
                    // Stands in for the system-bar insets the real page is laid
                    // out inside — the band the chevrons overhang into.
                    innerPadding = PaddingValues(top = 24.dp, bottom = 48.dp),
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onUnhideApp = {},
                    onOpenLauncherAppInfo = {},
                    onOpenPlayUpdate = {},
                    onCompletePlayUpdate = {},
                    onDismissPlayUpdate = {},
                )
            }
        }
        composeRule.waitForIdle()
    }
}
