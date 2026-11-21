package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Sort using Google Play" offer, and choosing the apps it looks up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaySortUiTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val thermo = app("Thermo")
    private val oddity = app("Oddity")
    private val sideload = app("Sideload")

    @Test
    fun sortOpensTheList_andOnlyTickedPlayAppsAreSent() {
        var started: List<String>? = null
        var unticked: List<String>? = null
        compose(
            PlaySortState(
                candidates = listOf(
                    PlaySortCandidate(oddity, fromPlay = true),
                    PlaySortCandidate(sideload, fromPlay = false),
                    PlaySortCandidate(thermo, fromPlay = true),
                ),
                showOffer = true,
            ),
            PlaySortActions(onStart = { chosen, left -> started = chosen; unticked = left }),
        )
        composeRule.onNodeWithText("Sort the 2 apps in Other using Google Play?").assertExists()
        composeRule.onNodeWithTag(PLAY_SORT_OFFER_ACCEPT_TAG).performClick()
        assertEquals("nothing is sent before choosing", null, started)

        // An app Google Play didn't install can't be ticked.
        composeRule.onNodeWithTag("$PLAY_SORT_ROW_TAG:${sideload.packageName}").assertIsOff().assertIsNotEnabled()
        composeRule.onNodeWithText("Not from Google Play").assertExists()
        composeRule.onNodeWithTag("$PLAY_SORT_ROW_TAG:${oddity.packageName}").performClick()
        composeRule.onNodeWithTag(PLAY_SORT_SHEET_CONFIRM_TAG).performClick()

        assertEquals(listOf(thermo.packageName), started)
        // The unticked app is passed back, so it's never looked up on its own later.
        assertEquals(listOf(oddity.packageName), unticked)
    }

    @Test
    fun resultStaysInView_withNoFolderOpen() {
        // Sorting can empty Other, which then closes; the result shows atop the list.
        composeRule.setContent {
            TypeLauncherTheme {
                AppsCard(
                    apps = listOf(thermo),
                    dockLimit = Int.MAX_VALUE,
                    layout = AppListLayout.NameBeside,
                    iconSizeDp = 43,
                    highlightFirst = false,
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUninstallApp = {},
                    topNote = { PlaySortNote(PlaySortState(phase = PlaySortPhase.Done, sorted = 3), PlaySortActions()) },
                )
            }
        }
        composeRule.onNodeWithTag(PLAY_SORT_RESULT_TAG).assertExists()
        composeRule.onNodeWithText("Sorted 3 apps").assertExists()
    }

    @Test
    fun notNow_reachesItsCallback() {
        var dismissed = false
        compose(
            PlaySortState(candidates = listOf(PlaySortCandidate(thermo, fromPlay = true)), showOffer = true),
            PlaySortActions(onDismissOffer = { dismissed = true }),
        )
        composeRule.onNodeWithTag(PLAY_SORT_OFFER_DISMISS_TAG).performClick()
        assertTrue(dismissed)
    }

    @Test
    fun result_saysHowItWent_untilDone() {
        var acknowledged = false
        compose(
            PlaySortState(phase = PlaySortPhase.Done, sorted = 6, unsorted = 1),
            PlaySortActions(onAcknowledge = { acknowledged = true }),
        )
        composeRule.onNodeWithText("Sorted 6 apps").assertExists()
        composeRule.onNodeWithText("1 had no folder on Google Play and stays here.").assertExists()
        composeRule.onNodeWithTag(PLAY_SORT_DONE_TAG).performClick()
        assertTrue(acknowledged)
    }

    @Test
    fun review_listsEachAppWithItsFolder_andAChangeMovesIt() {
        val home = AppFolderDef.builtIn(AppCategory.Home)
        val car = AppFolderDef.builtIn(AppCategory.Car)
        val otherFolder = AppFolderDef.builtIn(AppCategory.Other)
        val folders = listOf(car, home, otherFolder)
        val placed = mutableMapOf(thermo.packageName to home.key, oddity.packageName to otherFolder.key)
        val moves = mutableListOf<Pair<String, String?>>()
        val review = PlaySortReview(
            folders = folders,
            folderOf = { app -> placed[app.packageName] },
            playFolderOf = { app -> if (app == thermo) home.key else null },
            onMove = { app, key -> moves += app.packageName to key },
            onCreate = { _, _, _ -> },
        )
        // Hosted as HomeScreen does: the sheet above the note, which a move
        // can take away (Codex on PR #888).
        var noteShown by mutableStateOf(true)
        var reviewing by mutableStateOf(false)
        composeRule.setContent {
            TypeLauncherTheme {
                if (noteShown) {
                    PlaySortNote(
                        PlaySortState(phase = PlaySortPhase.Done, sorted = 1, unsorted = 1, reviewed = listOf(thermo, oddity)),
                        PlaySortActions(),
                        onReview = { reviewing = true },
                    )
                }
                if (reviewing) PlaySortReviewSheet(listOf(thermo, oddity), review, onDismiss = { reviewing = false })
            }
        }
        composeRule.onNodeWithTag(PLAY_SORT_REVIEW_TAG).performClick()
        noteShown = false
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(PLAY_SORT_REVIEW_SHEET_TAG).assertExists()
        composeRule.onNodeWithText("Sorted").assertExists()
        composeRule.onNodeWithText("Not sorted").assertExists()

        composeRule.onNodeWithTag("$PLAY_SORT_REVIEW_FOLDER_TAG:${oddity.packageName}").performClick()
        composeRule.onNodeWithTag("$PLAY_SORT_REVIEW_OPTION_TAG:none").performClick()
        composeRule.onNodeWithTag("$PLAY_SORT_REVIEW_FOLDER_TAG:${thermo.packageName}").performClick()
        composeRule.onNodeWithTag("$PLAY_SORT_REVIEW_OPTION_TAG:all").performClick()
        composeRule.onNodeWithTag("$APP_FOLDER_PICKER_OPTION_TAG:${car.key}").performClick()

        assertEquals(listOf(oddity.packageName to null, thermo.packageName to car.key), moves)
    }

    private fun compose(state: PlaySortState, actions: PlaySortActions) {
        composeRule.setContent { TypeLauncherTheme { PlaySortNote(state, actions) } }
        composeRule.waitForIdle()
    }

    private fun app(name: String) = InstalledApp(
        name = name,
        packageName = "com.example.${name.lowercase()}",
        launchIntent = Intent.makeMainActivity(ComponentName("com.example.${name.lowercase()}", "Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
    )
}
