package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// These drag/carousel tests render the dock at 4 icons per row (the
// per-row default they were written under). At the current 6-per-row default
// the dock icons shrink and each render composes more cells, accumulating to
// the point where Robolectric's shared Compose runtime can't settle a later
// drag-reorder assertion in the same JVM. Pinning 4 keeps the slot geometry the
// tests assert against and stays below that threshold.
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
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), dockedApps = docked, dockIconSizeDp = dockIconSizeForSlotCount(411, 4)))
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
                    onAppListLayoutChanged = {},
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
        var state by mutableStateOf(LauncherUiState(filteredApps = emptyList(), dockedApps = docked, dockIconSizeDp = dockIconSizeForSlotCount(411, 4)))
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
                    onAppListLayoutChanged = {},
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
        val state = LauncherUiState(filteredApps = emptyList(), dockedApps = docked, dockIconSizeDp = dockIconSizeForSlotCount(411, 4))
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
                    onAppListLayoutChanged = {},
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

    // Regression: a single dock drag used to drop one cell into the gesture
    // even after the popup-window cancellation was deferred. The cause was
    // structural — `key(app.id) { DockedAppButton(...) }` sat *inside* the
    // `if (app != null)` branch of a per-row `Row`'s nested for-loop, so
    // the keyed group's parent slot changed every time a swap happened.
    // Compose only matches-and-moves keyed groups within the same parent;
    // a cross-parent move falls back to remove + add, which detaches the
    // `DockedAppButton`'s `pointerInput` modifier node and cancels the
    // in-flight gesture coroutine. The next pointer event then arrives at a
    // freshly-restarted `awaitFirstDown` rather than the live drag.
    //
    // Reproducing the bug needs multiple separate pointer events with a
    // recomposition between them, because the tear-down only manifests on
    // the recomposition triggered by the first reorder. The existing
    // `dragReorderingDockedApp_movesIconBetweenRows` packs the whole drag
    // into one `moveBy`, so its `handleDockDrag` runs to completion inside
    // one composition window and the coroutine never has to survive a
    // recomposition; that test passes either way.
    //
    // The fix: a single `FlowRow(maxItemsInEachRow = columns)` parent, a
    // flat for-loop, and `key()` hoisted over the whole `if/else` chain.
    // With every keyed group under one parent at one slot-table depth, the
    // swap is a sibling move, the modifier nodes survive, and the gesture
    // coroutine sees every subsequent event.
    @Test
    fun dragReorderingDockedApp_continuesAcrossMultipleSlots() {
        val docked = listOf(
            fakeApp("App01").copy(isDocked = true),
            fakeApp("App02").copy(isDocked = true),
            fakeApp("App03").copy(isDocked = true),
            fakeApp("App04").copy(isDocked = true),
        )
        val positions = mutableStateMapOf(
            docked[0].id to DockPosition(0, 0),
            docked[1].id to DockPosition(0, 1),
            docked[2].id to DockPosition(0, 2),
            docked[3].id to DockPosition(0, 3),
        )
        val reorders = mutableListOf<Triple<String, Int, Int>>()
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                dockedApps = docked,
                dockPositions = positions.toMap(),
                dockIconSizeDp = dockIconSizeForSlotCount(411, 4),
            ),
        )
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onReorderDock = { id, row, column ->
                        reorders.add(Triple(id, row, column))
                        val target = DockPosition(row, column)
                        val previous = positions[id]
                        val occupant = positions.entries
                            .firstOrNull { (k, v) -> k != id && v == target }?.key
                        positions[id] = target
                        if (occupant != null && previous != null) {
                            positions[occupant] = previous
                        }
                        val newDockedApps = docked.sortedWith(
                            compareBy(
                                { positions[it.id]?.row ?: 0 },
                                { positions[it.id]?.column ?: 0 },
                            ),
                        )
                        state = state.copy(
                            dockedApps = newDockedApps,
                            dockPositions = positions.toMap(),
                        )
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
        // Three slow `moveBy` events (≈ slot pitch each at 420dpi w411dp)
        // with `waitForIdle()` between them so each intermediate reorder
        // commits and the FlowRow recomposes before the next event lands.
        // Pre-fix the per-icon coroutine was torn down on the recomposition
        // after the first reorder, so only one reorder ever made it to the
        // log; the test asserts ≥ 2 to falsify "only one slot per
        // long-press" without over-specifying which slots the rebase
        // happens to land on.
        val slotPitchPx = 300f
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            moveBy(Offset(slotPitchPx, 0f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            moveBy(Offset(slotPitchPx, 0f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            moveBy(Offset(slotPitchPx, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(
            "expected multi-slot drag to fire ≥ 2 reorders (got ${reorders.size}: $reorders)",
            reorders.size >= 2,
        )
        assertTrue(
            "dragged app should advance past the first neighbour, got ${positions[docked[0].id]}",
            (positions[docked[0].id]?.column ?: 0) >= 2,
        )
    }

    // Regression: extending the in-row continuity fix to multi-row docks.
    // Even with `key()` hoisted over the if/else, a per-row `Row` parent
    // (or an outer-iteration group from a nested for-loop) made the
    // dragged icon's keyed group reparent across the row boundary, so
    // cross-row drags still detached the `pointerInput` after one slot.
    // The structural fix — single `FlowRow(maxItemsInEachRow = columns)`
    // plus a flat `for (slotIndex)` loop — puts row-0 and row-1 keys at
    // the same slot-table depth under one parent, so a row-0 → row-1
    // swap is a sibling move and the gesture coroutine survives.
    @Test
    fun dragReorderingDockedApp_continuesAcrossRowBoundary() {
        val docked = (0 until 8).map { index ->
            fakeApp("App%02d".format(index + 1)).copy(isDocked = true)
        }
        val positions = mutableStateMapOf<String, DockPosition>().apply {
            docked.forEachIndexed { index, app ->
                put(app.id, DockPosition(row = index / 4, column = index % 4))
            }
        }
        val reorders = mutableListOf<Triple<String, Int, Int>>()
        var state by mutableStateOf(
            LauncherUiState(
                filteredApps = emptyList(),
                dockedApps = docked,
                dockPositions = positions.toMap(),
                dockIconSizeDp = dockIconSizeForSlotCount(411, 4),
            ),
        )
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
                    onAppListLayoutChanged = {},
                    onDockVisibleIconCountChanged = {},
                    onAppListSortOrderChanged = {},
                    onReorderDock = { id, row, column ->
                        reorders.add(Triple(id, row, column))
                        val target = DockPosition(row, column)
                        val previous = positions[id]
                        val occupant = positions.entries
                            .firstOrNull { (k, v) -> k != id && v == target }?.key
                        positions[id] = target
                        if (occupant != null && previous != null) {
                            positions[occupant] = previous
                        }
                        val newDockedApps = docked.sortedWith(
                            compareBy(
                                { positions[it.id]?.row ?: 0 },
                                { positions[it.id]?.column ?: 0 },
                            ),
                        )
                        state = state.copy(
                            dockedApps = newDockedApps,
                            dockPositions = positions.toMap(),
                        )
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
        // Long-press App01 at (0, 0), drag down past the row boundary into
        // row 1, settle, then drag right within row 1. Pre-fix the second
        // event would arrive at a freshly-restarted `awaitFirstDown`
        // (because the cross-row move detached the per-icon pointerInput
        // on the first event) and only one reorder would fire.
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            val start = Offset(width / 2f, height / 2f)
            down(start)
            move(longPressMs + 100)
            moveBy(Offset(0f, 280f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            moveBy(Offset(300f, 0f))
            up()
        }
        composeRule.waitForIdle()

        assertTrue(
            "expected cross-row drag to fire ≥ 2 reorders (got ${reorders.size}: $reorders)",
            reorders.size >= 2,
        )
        val finalPosition = positions[docked[0].id]
        assertTrue(
            "dragged app should have left row 0 (got $finalPosition)",
            (finalPosition?.row ?: 0) >= 1,
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
            currentOccupantIds = docked.map { it.id }.toSet(),
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
            currentOccupantIds = docked.map { it.id }.toSet(),
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
    fun dockDragMergesOnCenterDropOntoOccupiedNeighbor() {
        // With folders enabled, dropping on the *center* of an occupied neighbor
        // arms a merge (held to release), never a live swap-reorder.
        val a = fakeApp("App01").copy(isDocked = true)
        val b = fakeApp("App02").copy(isDocked = true)
        val positions = mapOf(a.id to DockPosition(0, 0), b.id to DockPosition(0, 1))
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
        )
        val occupantByPosition = mapOf(
            DockPosition(0, 0) to a.id,
            DockPosition(0, 1) to b.id,
        )
        var reordered = false
        var mergeTarget: String? = null
        var swapTarget: DockPosition? = null

        handleDockDrag(
            // Drag fully onto b's center.
            delta = Offset(100f, 0f),
            draggedAppId = a.id,
            currentOccupantIds = setOf(a.id, b.id),
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, _, _ -> reordered = true },
            currentOffset = Offset.Zero,
            setOffset = { },
            mergeEnabled = true,
            occupantByPosition = occupantByPosition,
            // pitch = 100, so the merge zone is the inner 35px around b's center.
            mergeRadiusPx = 35f,
            onMergeTarget = { mergeTarget = it },
            onSwapTarget = { swapTarget = it },
        )

        assertEquals(b.id, mergeTarget)
        assertNull("a center drop must not arm a swap", swapTarget)
        assertFalse("must not swap-reorder onto an occupied merge target", reordered)
    }

    @Test
    fun dockDragSwapsOnEdgeDropOntoOccupiedNeighbor() {
        // With folders enabled, landing on the *edge* of an occupied neighbor
        // (past the reorder boundary but outside the merge radius) arms a swap,
        // committed on release — not a merge, and not a live reorder.
        val a = fakeApp("App01").copy(isDocked = true)
        val b = fakeApp("App02").copy(isDocked = true)
        val positions = mapOf(a.id to DockPosition(0, 0), b.id to DockPosition(0, 1))
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
        )
        val occupantByPosition = mapOf(
            DockPosition(0, 0) to a.id,
            DockPosition(0, 1) to b.id,
        )
        var reordered = false
        var mergeTarget: String? = null
        var swapTarget: DockPosition? = null

        handleDockDrag(
            // Nudge a to x=105: past the x=100 boundary, 45px from b's center —
            // outside the 35px merge radius, so b's edge, not its center.
            delta = Offset(55f, 0f),
            draggedAppId = a.id,
            currentOccupantIds = setOf(a.id, b.id),
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, _, _ -> reordered = true },
            currentOffset = Offset.Zero,
            setOffset = { },
            mergeEnabled = true,
            occupantByPosition = occupantByPosition,
            mergeRadiusPx = 35f,
            onMergeTarget = { mergeTarget = it },
            onSwapTarget = { swapTarget = it },
        )

        assertEquals(DockPosition(0, 1), swapTarget)
        assertNull("an edge drop must not arm a merge", mergeTarget)
        assertFalse("swap is committed on release, not as a live reorder", reordered)
    }

    @Test
    fun dockSlotPitchIsTheSmallestCenterDistance() {
        val centers = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
            DockPosition(1, 0) to Offset(50f, 170f),
        )
        // Nearest pair is the 100px horizontal step, not the 120px vertical one.
        assertEquals(100f, dockSlotPitch(centers))
        assertFalse(
            "a single slot has no pair to merge against",
            dockSlotPitch(mapOf(DockPosition(0, 0) to Offset.Zero)).isFinite(),
        )
    }

    @Test
    fun dockDragReordersOntoEmptySlotEvenWithMergeEnabled() {
        val a = fakeApp("App01").copy(isDocked = true)
        val positions = mapOf(a.id to DockPosition(0, 0))
        val slotCenters = mapOf(
            DockPosition(0, 0) to Offset(50f, 50f),
            DockPosition(0, 1) to Offset(150f, 50f),
        )
        var movedTo: DockPosition? = null
        var mergeTarget: String? = "unset"

        handleDockDrag(
            delta = Offset(100f, 0f),
            draggedAppId = a.id,
            currentOccupantIds = setOf(a.id),
            currentDockPositions = positions,
            slotCenters = slotCenters,
            onReorder = { _, row, column -> movedTo = DockPosition(row, column) },
            currentOffset = Offset.Zero,
            setOffset = { },
            mergeEnabled = true,
            // (0,1) is empty — not in occupantByPosition.
            occupantByPosition = mapOf(DockPosition(0, 0) to a.id),
            onMergeTarget = { mergeTarget = it },
        )

        assertEquals(DockPosition(0, 1), movedTo)
        assertNull("empty target must not arm a merge", mergeTarget)
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
            currentOccupantIds = docked.map { it.id }.toSet(),
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
