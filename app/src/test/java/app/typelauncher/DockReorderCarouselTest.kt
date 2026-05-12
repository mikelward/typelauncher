package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class DockReorderCarouselTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun fakeApp(name: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    // Regression: long-press + horizontal drag on a docked icon used to also be
    // claimed by the carousel and page Home → Widgets/Agenda. The dock's
    // pointerInput consumes pointer changes, but the carousel reads raw deltas
    // via positionChangeIgnoreConsumed and does not see the consumption (raw
    // pointerInput.consume() does not dispatch nested scroll). The fix lets the
    // dock tell the launcher's gesture surface that a reorder is in flight so
    // the carousel does not double-claim the same horizontal motion.
    @Test
    fun dragReorderingDockedApp_doesNotPageCarousel() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), dockedApps = docked))
        var showAgendaCount = 0
        var showWidgetsCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
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
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(destination = LauncherDestination.Agenda)
                    },
                    onShowWidgets = {
                        showWidgetsCount += 1
                        state = state.copy(destination = LauncherDestination.Widgets())
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            // `move(delay)` (unlike `advanceEventTime`) advances the test
            // scheduler by `delay`, which is what fires the dock's long-press
            // timer. Same trick `longClick` uses internally — without it the
            // timer never fires and the moveBy below cancels the gesture
            // before drag mode arms, so onDragStart never runs.
            move(longPressMs + 100)
            // Drag well past the carousel's 96 dp commit threshold (~252 px at
            // this test's density). Without the fix this would page Home → an
            // adjacent screen because the dock's pointerInput consumption is
            // invisible to the carousel arbitrator.
            moveBy(Offset(700f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("dock reorder must not page the carousel to Widgets", 0, showWidgetsCount)
        assertEquals("dock reorder must not page the carousel to Agenda", 0, showAgendaCount)
        assertEquals(LauncherDestination.Home, state.destination)
    }

    // Regression: a small horizontal drift during the ~500 ms long-press wait
    // (within the long-press cancel slop, so the long-press still fires) used
    // to push the carousel's accumulated `rawDragX` close to its 8 dp claim
    // threshold while the dock's own slop accounting only started counting
    // after the long-press fired. A modest post-long-press move then crossed
    // the carousel's threshold on an event where the dock had not yet seen
    // its own slop crossed — so the carousel claimed the gesture and paged
    // Home → Widgets/Agenda before the dock's `onDragStart` signalled the
    // suppression latch. The fix arms the suppression latch the moment the
    // long-press fires, not when slop is later crossed.
    @Test
    fun dragReorderingDockedApp_doesNotPageCarousel_afterPreLongPressDrift() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), dockedApps = docked))
        var showAgendaCount = 0
        var showWidgetsCount = 0
        composeRule.setContent {
            TypeLauncherTheme {
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
                    onShowAgenda = {
                        showAgendaCount += 1
                        state = state.copy(destination = LauncherDestination.Agenda)
                    },
                    onShowWidgets = {
                        showWidgetsCount += 1
                        state = state.copy(destination = LauncherDestination.Widgets())
                    },
                    onShowHome = { state = state.copy(destination = LauncherDestination.Home) },
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

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            // Tiny pre-long-press drift well below the long-press cancel slop
            // (~21 px at 420 dpi). This bumps the carousel's accumulated
            // rawDragX so it sits just under the carousel's 8 dp claim
            // threshold, while the dock's own slop accounting still starts
            // from zero after the long-press fires.
            moveBy(Offset(13f, 0f))
            move(longPressMs + 100)
            // Small post-long-press move that, combined with the drift, pushes
            // the carousel's rawDragX above its claim threshold (13 + 10 = 23
            // px > 21 px touch slop) on an event where the dock's own
            // totalDelta is only 10 px — still below the dock's 8 dp slop.
            // Without the early-arm fix the carousel would claim on this event
            // because isDockDraggingState is not yet set, and the follow-up
            // 700 px move would then commit a page swipe.
            moveBy(Offset(10f, 0f))
            moveBy(Offset(700f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertEquals("dock reorder after pre-long-press drift must not page Widgets", 0, showWidgetsCount)
        assertEquals("dock reorder after pre-long-press drift must not page Agenda", 0, showAgendaCount)
        assertEquals(LauncherDestination.Home, state.destination)
    }

    // Regression: the dock is laid out as a Column of per-row Rows. Slot
    // centres used to be reported via `coords.positionInParent()`, which gives
    // each slot's position relative to its own per-row Row — so (row 0, col 0)
    // and (row 1, col 0) reported identical centres and the drag handler
    // could not distinguish rows. Dragging a docked icon to another row never
    // snapped or persisted, regardless of whether the target row was full.
    // The fix uses `coords.positionInRoot()` so every slot's centre lives in
    // one window-wide coordinate space.
    @Test
    fun dragReorderingDockedApp_movesIconBetweenRows() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
            fakeApp("App05").copy(isDocked = true),
        )
        val state = LauncherUiState(filteredApps = emptyList(), dockedApps = docked)
        var reorderTarget: Triple<String, Int, Int>? = null
        composeRule.setContent {
            TypeLauncherTheme {
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
                    onReorderDock = { id, row, column ->
                        reorderTarget = Triple(id, row, column)
                    },
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
        composeRule.waitForIdle()

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        // Drag the row-0 icon downward by well more than a slot height so the
        // dragged centre clears row 1's halfway threshold. Coordinates are in
        // pixels relative to the touched node, so a generous offset like 600
        // pixels reliably crosses one slot row at 420dpi without depending on
        // exact icon-size math.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            moveBy(Offset(0f, 600f))
            up()
        }
        composeRule.waitForIdle()

        val target = reorderTarget
        assertNotNull("vertical drag must trigger a reorder to row 1", target)
        assertEquals("dragged app id", docked[0].id, target!!.first)
        assertEquals("dragged app must land in a lower row", 1, target.second)
    }

    // Regression: a single dock drag used to stop one cell into the gesture
    // because the drag's pointerInput lived on the per-icon `key(app.id)`
    // composable, and the first reorder relocated that keyed group between
    // its parent Rows (or even to a different column within the same Row).
    // Compose tore down the gesture coroutine on the way through, so every
    // subsequent move event landed on whichever icon now occupied the old
    // slot — the user lifted their finger after the first cell and could
    // not drag any further. Hoisting the gesture loop up to the DockCard's
    // Column (a stable composable that never moves) keeps the coroutine
    // alive across the recompositions triggered by each intermediate
    // reorder, so a single drag can walk an icon through several cells.
    //
    // Reproducing the bug requires multiple separate `moveBy` events with
    // state updates between them — a single big `moveBy` (as
    // `dragReorderingDockedApp_movesIconBetweenRows` uses) only generates
    // one pointer event and so the drag handler walks the cells inside one
    // recomposition window, masking the tear-down.
    @Test
    fun dragReorderingDockedApp_continuesAcrossMultipleCells() {
        val apps = listOf(
            fakeApp("App01"),
            fakeApp("App02"),
            fakeApp("App03"),
            fakeApp("App04"),
        )
        var dockPositions by mutableStateOf<Map<String, DockPosition>>(
            apps.mapIndexed { index, app ->
                app.id to DockPosition(row = 0, column = index)
            }.toMap(),
        )
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                dockedApps = apps.map { it.copy(isDocked = true) },
                dockPositions = dockPositions,
            ),
        )
        val reorderCalls = mutableListOf<Triple<String, Int, Int>>()
        composeRule.setContent {
            TypeLauncherTheme {
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
                    onReorderDock = { id, row, column ->
                        reorderCalls += Triple(id, row, column)
                        // Mirror the production rebuild: place the dragged
                        // app at (row, column) and shuffle the displaced
                        // app into the dragged app's previous slot.
                        val current = dockPositions
                        val previous = current[id]
                        if (previous != null) {
                            val target = DockPosition(row, column)
                            val displaced = current.entries
                                .firstOrNull { it.value == target && it.key != id }
                            dockPositions = current.toMutableMap().apply {
                                this[id] = target
                                if (displaced != null) this[displaced.key] = previous
                            }
                            state = state.copy(dockPositions = dockPositions)
                        }
                    },
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
        composeRule.waitForIdle()

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        // Long-press App01, then walk the finger rightward through
        // App02, App03, App04 via three separate move events. Between
        // each event Compose has a chance to recompose on the prior
        // reorder; the per-icon pointerInput used to die during one of
        // those recompositions, leaving only the first reorder in the
        // call log. The Column-level loop must produce all three.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            // Two separate move events with a state-mutating reorder
            // between them. Per move, ~one slot of rightward travel —
            // the exact pixel size doesn't matter, only that each move
            // produces its own pointer event so the gesture coroutine
            // has to survive the recomposition triggered by the first
            // reorder in order to handle the second.
            moveBy(Offset(300f, 0f))
            moveBy(Offset(300f, 0f))
            up()
        }
        composeRule.waitForIdle()

        // The previous, per-icon pointerInput tore down after the first
        // reorder, so only one reorder ever fired under this sequence.
        // The hoisted Column-level loop must keep producing reorders
        // across each intermediate recomposition.
        assertTrue(
            "drag must survive past the first cell — got reorders=$reorderCalls",
            reorderCalls.size >= 2,
        )
        val finalPosition = dockPositions[apps[0].id]
        assertNotNull("App01 must still have a docked position", finalPosition)
        assertTrue(
            "App01 must have moved at least two columns from its starting slot — got $finalPosition",
            finalPosition!!.column >= 2,
        )
    }

    // Regression: starting a drag on a docked icon used to grow the dock card
    // by one extra empty row (the rowCount calculation in DockCard added
    // `+ 1` whenever `draggedAppId != null`). The dock should keep its
    // resting layout while the user lifts an icon — dock rows are added by
    // docking more apps (or via the `+` add button), not by dragging.
    //
    // Asserted via the drag-handler contract: with 4 apps in a 4-column dock
    // (1 row), a vertical-down drag must not find a target slot in row 1
    // because that row should not exist while the drag is in flight.
    @Test
    fun dragReorderingDockedApp_doesNotAddExtraRow() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        val positions = docked.mapIndexed { index, app ->
            app.id to DockPosition(row = 0, column = index)
        }.toMap()
        // Only row-0 slot centres are populated, mirroring what the dock UI
        // reports when there is no second row in the grid.
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
            DockPosition(0, 2) to Offset(250f, 50f),
            DockPosition(0, 3) to Offset(350f, 50f),
        )
        var movedTo: DockPosition? = null

        handleDockDrag(
            // A large downward delta — would cross into row 1 if a row-1
            // slot existed.
            delta = Offset(0f, 200f),
            draggedAppId = docked[0].id,
            currentDockedApps = docked,
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, row, column -> movedTo = DockPosition(row, column) },
            currentOffset = Offset.Zero,
            setOffset = { },
        )

        assertTrue(
            "drag must not target a row 1 slot — the dock should not grow a new row mid-drag (got $movedTo)",
            movedTo == null || movedTo!!.row == 0,
        )
    }

    @Test
    fun dockDragTargetsReportedAddButtonSlot() {
        val app = fakeApp("App01").copy(isDocked = true)
        val docked = listOf(app)
        val positions = mapOf(app.id to DockPosition(0, 0))
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            // In the UI this is the visible + add-button cell when the first
            // row is under-filled; it must participate in drag targeting the
            // same way an EmptyDockSlot does.
            DockPosition(0, 1) to Offset(150f, 50f),
        )
        var movedTo: DockPosition? = null
        var updatedOffset = Offset.Zero

        handleDockDrag(
            delta = Offset(80f, 0f),
            draggedAppId = app.id,
            currentDockedApps = docked,
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, row, column -> movedTo = DockPosition(row, column) },
            currentOffset = Offset.Zero,
            setOffset = { updatedOffset = it },
        )

        assertEquals(DockPosition(row = 0, column = 1), movedTo)
        assertEquals(Offset(-20f, 0f), updatedOffset)
    }

    @Test
    fun dockDragTargetsNearestVerticalSparseSlot() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
            fakeApp("App05").copy(isDocked = true),
        )
        val positions = docked.mapIndexed { index, app ->
            app.id to DockPosition(row = index / 4, column = index % 4)
        }.toMap()
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
            DockPosition(0, 2) to Offset(250f, 50f),
            DockPosition(0, 3) to Offset(350f, 50f),
            DockPosition(1, 0) to Offset(50f, 150f),
            DockPosition(1, 1) to Offset(150f, 150f),
            DockPosition(1, 2) to Offset(250f, 150f),
            DockPosition(1, 3) to Offset(350f, 150f),
        )
        var movedTo: DockPosition? = null
        var updatedOffset = Offset.Zero

        handleDockDrag(
            delta = Offset(0f, 80f),
            draggedAppId = docked[0].id,
            currentDockedApps = docked,
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, row, column -> movedTo = DockPosition(row, column) },
            currentOffset = Offset.Zero,
            setOffset = { updatedOffset = it },
        )

        assertEquals(DockPosition(row = 1, column = 0), movedTo)
        assertEquals(Offset(0f, -20f), updatedOffset)
    }
}
