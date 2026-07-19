package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Search-time dock reveal: typing hides the dock, so a long-press on a list
 * app while the query is non-blank hides the keyboard and shows the dock as a
 * drop target — search, long-press, drag to the dock in one motion.
 *
 * Covered here: the dock appears on the long-press arm without moving the app
 * list (the drag gesture lives in the pressed item's modifier node, so a
 * reflow would kill it); a drop on the revealed dock docks the app and clears
 * the query with no keyboard restore; a release anywhere else asks the
 * keyboard back; a release that opens the item menu holds the reveal until
 * the menu closes, then asks the keyboard back; and the reveal never triggers
 * for a blank query or with no dock enabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class SearchDockRevealTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(name: String, isWorkApp: Boolean = false): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = isWorkApp,
            launchWithLauncherApps = false,
        )

    private val dockDrops = mutableListOf<Triple<String, Int, Int>>()
    private val dockMerges = mutableListOf<Pair<String, String>>()
    private var keyboardRestoreRequests = 0
    private var clearQueryCalls = 0

    private fun setLauncherContent(
        query: String = "app",
        isDockEnabled: Boolean = true,
        isWorkDockEnabled: Boolean = false,
        isWorkProfileActive: Boolean = false,
        dockedApps: List<InstalledApp> = listOf(fakeApp("Docked01").copy(isDocked = true)),
        extraApps: List<InstalledApp> = emptyList(),
    ) {
        dockDrops.clear()
        dockMerges.clear()
        keyboardRestoreRequests = 0
        clearQueryCalls = 0
        // Extra apps go first so they land in the visible part of the list.
        val apps = extraApps + (1..30).map { fakeApp("App%02d".format(it)) }
        var state by mutableStateOf(
            LauncherUiState(
                query = query,
                filteredApps = apps,
                dockedApps = dockedApps,
                isDockEnabled = isDockEnabled,
                isWorkDockEnabled = isWorkDockEnabled,
                isWorkProfileActive = isWorkProfileActive,
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state,
                    onQueryChanged = { state = state.copy(query = it) },
                    onClearQuery = {
                        clearQueryCalls++
                        state = state.copy(query = "")
                    },
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onDockAppAtPosition = { id, row, column -> dockDrops += Triple(id, row, column) },
                    onDockAppIntoOccupant = { id, occupant -> dockMerges += id to occupant },
                    onResetRank = {},
                    onRenameApp = { _, _ -> },
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onRequestShowKeyboard = { keyboardRestoreRequests++ },
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
    }

    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    /** Presses [appName]'s row and holds past the long-press timeout (no up). */
    private fun ComposeContentTestRule.armLongPress(appName: String) {
        onNodeWithTag("$APP_ROW_TAG:$appName").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
        }
        waitForIdle()
    }

    private fun boundsInRootPx(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    @Test
    fun longPressWithQuery_revealsDockWithoutMovingTheList() {
        setLauncherContent()

        // Typing hides the dock; no reveal is up before the long-press.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
        val appsCardBefore = boundsInRootPx(APPS_CARD_TAG)
        val firstRowBefore = boundsInRootPx("$APP_ROW_TAG:App01")

        composeRule.armLongPress("App01")

        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertExists()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertExists()
        // The reveal takes no space from the layout: the apps card and the
        // pressed row keep their exact geometry, so the in-flight drag gesture
        // (hosted in the row's modifier node) can't be disposed by a reflow.
        assertEquals(appsCardBefore, boundsInRootPx(APPS_CARD_TAG))
        assertEquals(firstRowBefore, boundsInRootPx("$APP_ROW_TAG:App01"))

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput { cancel() }
    }

    @Test
    fun dropOnRevealedDock_docksAppClearsQueryAndKeepsKeyboardDown() {
        setLauncherContent()
        composeRule.armLongPress("App01")

        val dockBounds = boundsInRootPx(DOCK_CARD_TAG)
        val rowBounds = boundsInRootPx("$APP_ROW_TAG:App01")
        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput {
            // Cross the 8dp pick-up slop so the drag starts, then release over
            // the revealed dock.
            moveBy(Offset(0f, -60f))
            moveTo(dockBounds.center - rowBounds.topLeft)
            up()
        }
        composeRule.waitForIdle()

        assertEquals(
            "release over the revealed dock must dock or merge the app " +
                "(drops=$dockDrops merges=$dockMerges)",
            1,
            dockDrops.size + dockMerges.size,
        )
        assertEquals("a landed drop clears the query", 1, clearQueryCalls)
        assertEquals(
            "a landed drop keeps the keyboard down (the blank-query home is the confirmation)",
            0,
            keyboardRestoreRequests,
        )
        // The blank query brings the dock back as the regular in-layout slot.
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertExists()
    }

    @Test
    fun releaseOffDock_restoresKeyboardAndKeepsQuery() {
        setLauncherContent()
        composeRule.armLongPress("App01")

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput {
            // Drag upward within the list and release far from the dock.
            moveBy(Offset(0f, -200f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("no dock action for an off-dock release", 0, dockDrops.size + dockMerges.size)
        assertEquals("the query survives a missed drop", 0, clearQueryCalls)
        assertEquals("a missed drop asks the keyboard back", 1, keyboardRestoreRequests)
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()
    }

    @Test
    fun menuRelease_holdsRevealUntilMenuCloses() {
        setLauncherContent()
        composeRule.armLongPress("App01")

        // Release without crossing the slop: the item menu opens and the
        // reveal holds — no keyboard under an open popup.
        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:App01").assertExists()
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertExists()
        assertEquals(0, keyboardRestoreRequests)

        // Any menu item routes through onDismiss; picking one closes the menu,
        // ends the reveal, and asks the keyboard back.
        composeRule.onNodeWithTag("$RESET_RANK_ACTION_TAG:App01").performClick()
        composeRule.waitForIdle()

        assertEquals("dismissing the long-press menu asks the keyboard back", 1, keyboardRestoreRequests)
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
    }

    @Test
    fun blankQuery_neverTriggersTheReveal() {
        setLauncherContent(query = "")

        // Blank query: the dock is already on screen as the regular slot.
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertExists()
        composeRule.armLongPress("App01")

        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput {
            moveBy(Offset(0f, -200f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals("the reveal machinery stays out of blank-query drags", 0, keyboardRestoreRequests)
    }

    @Test
    fun workDockOnly_revealsOnlyForWorkApps() {
        // Personal dock off, work dock on: the work dock is work-apps-only, so
        // a long-press on a personal app must not hide the keyboard for a
        // target that can't accept the drop — while a work app still reveals.
        setLauncherContent(
            isDockEnabled = false,
            isWorkDockEnabled = true,
            isWorkProfileActive = true,
            dockedApps = emptyList(),
            extraApps = listOf(fakeApp("WorkApp01", isWorkApp = true)),
        )

        composeRule.armLongPress("App01")
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput { cancel() }
        composeRule.waitForIdle()

        composeRule.armLongPress("WorkApp01")
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertExists()
        composeRule.onNodeWithTag(WORK_DOCK_CARD_TAG).assertExists()
        composeRule.onNodeWithTag("$APP_ROW_TAG:WorkApp01").performTouchInput { cancel() }
    }

    @Test
    fun droppingDockedAppOntoItsOwnIcon_countsAsAMiss() {
        // While typing, docked apps resurface in the filtered list. Releasing
        // one onto its own loose dock icon resolves to a self-merge, which the
        // store no-ops — so it must not report a landed drop (which would
        // clear the query for a dock that didn't change).
        val docked = fakeApp("Docked01").copy(isDocked = true)
        setLauncherContent(dockedApps = listOf(docked), extraApps = listOf(docked))
        composeRule.armLongPress("Docked01")

        val ownIconBounds = boundsInRootPx("$DOCK_APP_TAG:Docked01")
        val rowBounds = boundsInRootPx("$APP_ROW_TAG:Docked01")
        composeRule.onNodeWithTag("$APP_ROW_TAG:Docked01").performTouchInput {
            moveBy(Offset(0f, -60f))
            moveTo(ownIconBounds.center - rowBounds.topLeft)
            up()
        }
        composeRule.waitForIdle()

        assertEquals("a self-merge dispatches nothing", 0, dockDrops.size + dockMerges.size)
        assertEquals("the query survives a self-merge", 0, clearQueryCalls)
        assertEquals("a self-merge restores the keyboard like any miss", 1, keyboardRestoreRequests)
    }

    @Test
    fun folderMember_neverTriggersTheReveal() {
        // A folder member is already docked (inside its folder): the store's
        // dock()/merge no-op for it, so the reveal must not hide the keyboard
        // for a drop that can never land. Folder members do appear in the
        // filtered list while a query is active.
        setLauncherContent(
            extraApps = listOf(fakeApp("Foldered01").copy(isInFolder = true)),
        )

        composeRule.armLongPress("Foldered01")
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag("$APP_ROW_TAG:Foldered01").performTouchInput {
            moveBy(Offset(0f, -200f))
            up()
        }
        composeRule.waitForIdle()
        assertEquals("no dock action for a folder member", 0, dockDrops.size + dockMerges.size)
        assertEquals("no keyboard churn for a folder member", 0, keyboardRestoreRequests)
        assertEquals("the query survives", 0, clearQueryCalls)
    }

    @Test
    fun noDockEnabled_neverTriggersTheReveal() {
        setLauncherContent(isDockEnabled = false, dockedApps = emptyList())

        composeRule.armLongPress("App01")

        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(DOCK_CARD_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag("$APP_ROW_TAG:App01").performTouchInput {
            moveBy(Offset(0f, -200f))
            up()
        }
        composeRule.waitForIdle()
        assertTrue("no dock to reveal, so no keyboard churn", keyboardRestoreRequests == 0)
    }
}
