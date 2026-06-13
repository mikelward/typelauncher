package app.typelauncher

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coverage for folding the work dock into the personal dock when "Show work
 * dock" is off. Apps left in the work store (docked while the setting was on)
 * must still render — merged into the personal dock — instead of being stranded
 * with an "Undock" menu but no visible dock entry. The merge is non-destructive:
 * re-enabling the work dock restores the apps to their own card.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelWorkDockMergeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    private val workPackage = "com.example.workchat"

    @After
    fun clearPrefs() {
        listOf(
            "docked_apps",
            "work_docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun workDockOffMergesStrandedWorkAppIntoPersonalDock() {
        val viewModel = newViewModel()
        idle()
        dockWorkAppWithWorkDockOn(viewModel)

        // Sanity: with the work dock on, the app lives in the work card.
        assertTrue(viewModel.uiState.value.workDockedApps.any { it.packageName == workPackage })
        assertFalse(viewModel.uiState.value.mergeWorkIntoPersonal)

        viewModel.setWorkDockEnabled(false)
        idle()

        val state = viewModel.uiState.value
        assertTrue("Work dock off must merge into personal", state.mergeWorkIntoPersonal)
        assertTrue(
            "Stranded work app must render in the personal dock",
            state.dockedApps.any { it.packageName == workPackage },
        )
        assertTrue("Work card must be empty when merged", state.workDockedApps.isEmpty())
        // The merged work app keeps its work-docked flag, so its menu still
        // reads "Undock" (the label keys off isDocked || isWorkDocked).
        assertTrue(
            "Merged work app keeps its work-docked flag",
            state.dockedApps.first { it.packageName == workPackage }.isWorkDocked,
        )
    }

    @Test
    fun reEnablingWorkDockRestoresItsOwnCardNonDestructively() {
        val viewModel = newViewModel()
        idle()
        dockWorkAppWithWorkDockOn(viewModel)
        viewModel.setWorkDockEnabled(false)
        idle()
        assertTrue(viewModel.uiState.value.mergeWorkIntoPersonal)

        viewModel.setWorkDockEnabled(true)
        idle()

        val state = viewModel.uiState.value
        assertFalse(state.mergeWorkIntoPersonal)
        assertTrue(
            "Work app returns to its own card",
            state.workDockedApps.any { it.packageName == workPackage },
        )
        assertFalse(
            "Work app no longer rides the personal dock",
            state.dockedApps.any { it.packageName == workPackage },
        )
    }

    @Test
    fun disablingTheWholeDockDoesNotMerge() {
        val viewModel = newViewModel()
        idle()
        dockWorkAppWithWorkDockOn(viewModel)
        viewModel.setWorkDockEnabled(false)
        idle()
        assertTrue(viewModel.uiState.value.mergeWorkIntoPersonal)

        // No personal dock surface to host the work apps → no merge.
        viewModel.setDockEnabled(false)
        idle()

        assertFalse(viewModel.uiState.value.mergeWorkIntoPersonal)
    }

    @Test
    fun undockingAMergedWorkAppRemovesItFromTheWorkStoreOnly() {
        val viewModel = newViewModel()
        idle()
        dockWorkAppWithWorkDockOn(viewModel)
        viewModel.setWorkDockEnabled(false)
        idle()

        val merged = viewModel.uiState.value.dockedApps.first { it.packageName == workPackage }
        viewModel.toggleDock(merged, maxDockedApps = 4)
        idle()

        val state = viewModel.uiState.value
        assertFalse(
            "Undock removes the merged work app from the dock",
            state.dockedApps.any { it.packageName == workPackage },
        )
        assertTrue("Work store is now empty", state.workDockedApps.isEmpty())
        // With the work store empty there is nothing to merge any more.
        assertFalse(state.mergeWorkIntoPersonal)
    }

    @Test
    fun undockingADualPinnedMergedAppClearsBothStores() {
        // A work app pinned to the personal dock manually AND copied into the
        // work dock renders as one merged icon. "Undock" on that single icon
        // must clear it from both stores, or it would re-merge from the
        // surviving work entry and appear to ignore the undock.
        val viewModel = newViewModel()
        idle()
        // Capture the app reference once: once it's docked it is deduped out of
        // the app list (Show docked apps defaults off), so re-querying
        // `filteredApps` for it would fail. The captured (undocked) snapshot is
        // all `toggleDock` / `toggleWorkDock` need — they key off `app.id`.
        val workApp = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        // Dock to the personal store (work dock off routes there), then also
        // into the work store, producing the dual-pin state.
        viewModel.toggleDock(workApp, maxDockedApps = 4)
        viewModel.setWorkDockEnabled(true)
        idle()
        viewModel.toggleWorkDock(workApp, maxDockedApps = 4)
        viewModel.setWorkDockEnabled(false)
        idle()

        // One merged icon, flagged as both personal- and work-docked.
        val merged = viewModel.uiState.value.dockedApps.first { it.packageName == workPackage }
        assertTrue("Precondition: app is dual-pinned", merged.isDocked && merged.isWorkDocked)
        assertEquals(1, viewModel.uiState.value.dockedApps.count { it.packageName == workPackage })

        // Undock from the merged icon (toggleDock is merge-aware).
        viewModel.toggleDock(merged, maxDockedApps = 4)
        idle()

        val state = viewModel.uiState.value
        assertFalse(
            "Undock clears the dual-pinned app from the dock for good",
            state.dockedApps.any { it.packageName == workPackage },
        )
        assertTrue("Personal store cleared", state.dockedApps.none { it.packageName == workPackage })
        assertTrue("Work store cleared", state.workDockedApps.isEmpty())
        assertFalse("Nothing left to merge", state.mergeWorkIntoPersonal)
    }

    @Test
    fun reorderingAMergedWorkAppPersistsAcrossRefreshWithoutSnapBack() {
        // Personal dock holds one app; the work store holds two. With the work
        // dock off they merge as [personal, work1, work2]. Dragging work2 ahead
        // of work1 must stick after the next refresh, not snap back.
        seedApp("Personal", "com.example.personal")
        val viewModel = newViewModel()
        idle()
        val personal = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.personal" }
        viewModel.toggleDock(personal, maxDockedApps = 4)

        viewModel.setWorkDockEnabled(true)
        idle()
        val work1 = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        val work2 = viewModel.uiState.value.filteredApps.first { it.packageName == "$workPackage.two" }
        viewModel.toggleDock(work1, maxDockedApps = 4)
        viewModel.toggleDock(work2, maxDockedApps = 4)
        idle()

        viewModel.setWorkDockEnabled(false)
        idle()
        val mergedOrder = viewModel.uiState.value.dockedApps.map { it.packageName }
        assertEquals(
            listOf("com.example.personal", workPackage, "$workPackage.two"),
            mergedOrder,
        )

        // Drag work2 onto work1's slot (merged index 1). It should land ahead of
        // work1 in the work block.
        val work2Merged = viewModel.uiState.value.dockedApps.first { it.packageName == "$workPackage.two" }
        viewModel.reorderMergedDock(work2Merged.id, row = 0, column = 1)
        idle()

        val afterReorder = viewModel.uiState.value.dockedApps.map { it.packageName }
        assertEquals(
            "work2 must sit ahead of work1 and not snap back",
            listOf("com.example.personal", "$workPackage.two", workPackage),
            afterReorder,
        )
    }

    @Test
    fun reorderingAMergedWorkAppForwardOntoTheNextSticks() {
        // A forward drag (work1 onto work2's slot) must swap them, not no-op:
        // [personal, work1, work2] → drag work1 to index 2 → [personal, work2, work1].
        seedApp("Personal", "com.example.personal")
        val viewModel = newViewModel()
        idle()
        val personal = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.personal" }
        viewModel.toggleDock(personal, maxDockedApps = 4)
        viewModel.setWorkDockEnabled(true)
        idle()
        val work1 = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        val work2 = viewModel.uiState.value.filteredApps.first { it.packageName == "$workPackage.two" }
        viewModel.toggleDock(work1, maxDockedApps = 4)
        viewModel.toggleDock(work2, maxDockedApps = 4)
        idle()
        viewModel.setWorkDockEnabled(false)
        idle()
        assertEquals(
            listOf("com.example.personal", workPackage, "$workPackage.two"),
            viewModel.uiState.value.dockedApps.map { it.packageName },
        )

        // Drag work1 forward onto work2's slot (merged index 2).
        val work1Merged = viewModel.uiState.value.dockedApps.first { it.packageName == workPackage }
        viewModel.reorderMergedDock(work1Merged.id, row = 0, column = 2)
        idle()

        assertEquals(
            "Forward drag swaps work1 past work2",
            listOf("com.example.personal", "$workPackage.two", workPackage),
            viewModel.uiState.value.dockedApps.map { it.packageName },
        )
    }

    @Test
    fun changingDockColumnsRecomputesMergedPositions() {
        // The merged positions are precomputed under `dockIconCount`, so moving
        // the icons-per-row slider must rerun the merge — otherwise the baked
        // work coordinates resolve wrong under the new column count.
        seedApp("Personal", "com.example.personal")
        val viewModel = newViewModel()
        idle()
        val personal = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.personal" }
        viewModel.toggleDock(personal, maxDockedApps = 8)
        viewModel.setWorkDockEnabled(true)
        idle()
        val work1 = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        val work2 = viewModel.uiState.value.filteredApps.first { it.packageName == "$workPackage.two" }
        viewModel.toggleDock(work1, maxDockedApps = 8)
        viewModel.toggleDock(work2, maxDockedApps = 8)
        idle()
        viewModel.setWorkDockEnabled(false)
        idle()
        // At the default 4 columns all three sit on row 0.
        assertEquals(DockPosition(0, 2), viewModel.uiState.value.dockPositions[work2.id])

        viewModel.setDockVisibleIconCount(2)
        idle()

        // At 2 columns the merge is recomputed: work2 wraps to row 1.
        assertEquals(DockPosition(0, 1), viewModel.uiState.value.dockPositions[work1.id])
        assertEquals(DockPosition(1, 0), viewModel.uiState.value.dockPositions[work2.id])
    }

    @Test
    fun hidingAMergedDockEntryDoesNotReserveItsCell() {
        // A hidden / stale docked id must not reserve a slot in the merged
        // layout: the visible work app should pack into the freed cell instead
        // of leaving a gap (or forcing an extra row) where the hidden app sat.
        seedApp("Personal A", "com.example.persa")
        val viewModel = newViewModel()
        idle()
        // Personal app at (0,0), then a work app into the work store, then merge.
        val personalA = viewModel.uiState.value.filteredApps.first { it.packageName == "com.example.persa" }
        viewModel.toggleDock(personalA, maxDockedApps = 4)
        viewModel.setWorkDockEnabled(true)
        idle()
        val workApp = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        viewModel.toggleWorkDock(workApp, maxDockedApps = 4)
        viewModel.setWorkDockEnabled(false)
        idle()
        // Merged: personalA at (0,0), workApp packed at (0,1).
        assertEquals(DockPosition(0, 1), viewModel.uiState.value.dockPositions[workApp.id])

        viewModel.hideApp(personalA)
        idle()

        val state = viewModel.uiState.value
        assertTrue("Work app still merges", state.mergeWorkIntoPersonal)
        assertEquals(
            "Only the visible work app renders in the dock",
            listOf(workPackage),
            state.dockedApps.map { it.packageName },
        )
        assertEquals(
            "Work app packs into the freed cell, no reserved gap",
            DockPosition(0, 0),
            state.dockPositions[workApp.id],
        )
    }

    @Test
    fun mergePreservesWorkDockVisualOrderUnderReversedSort() {
        // The standalone work dock renders icons at their fixed coordinates, so
        // its left-to-right order never flips with a reversed sort. The merge
        // must reproduce that visual order — not the bottom-right-first id order
        // a reversed sort produces — or disabling the work dock would mirror the
        // work icons.
        val viewModel = newViewModel()
        idle()
        viewModel.setWorkDockEnabled(true)
        idle()
        val workA = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        val workB = viewModel.uiState.value.filteredApps.first { it.packageName == "$workPackage.two" }
        viewModel.toggleWorkDock(workA, maxDockedApps = 4)
        viewModel.toggleWorkDock(workB, maxDockedApps = 4)
        idle()
        viewModel.setAppListSortOrder(AppListSortOrder.UsageReversed)
        viewModel.setWorkDockEnabled(false)
        idle()

        assertTrue(viewModel.uiState.value.mergeWorkIntoPersonal)
        assertEquals(
            "Merged work order matches the work dock's visual order, not the reversed sort",
            listOf(workPackage, "$workPackage.two"),
            viewModel.uiState.value.dockedApps.map { it.packageName },
        )
    }

    /** Enables the work dock and docks one work app into it. */
    private fun dockWorkAppWithWorkDockOn(viewModel: LauncherViewModel) {
        viewModel.setWorkDockEnabled(true)
        idle()
        val workApp = viewModel.uiState.value.filteredApps.first { it.packageName == workPackage }
        viewModel.toggleDock(workApp, maxDockedApps = 4)
        idle()
    }

    private fun newViewModel(): LauncherViewModel {
        seedApp("Work Chat", workPackage)
        seedApp("Work Chat Two", "$workPackage.two")
        return LauncherViewModel(
            app = ApplicationProvider.getApplicationContext(),
            workPackages = setOf(workPackage, "$workPackage.two"),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun seedApp(label: String, packageName: String) {
        val resolveInfo = ResolveInfo().apply {
            nonLocalizedLabel = label
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.LaunchActivity"
            }
        }
        @Suppress("DEPRECATION")
        shadowOf(context.packageManager).addResolveInfoForIntent(launcherIntent, resolveInfo)
    }
}
