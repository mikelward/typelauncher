package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A landscape dock reflow (`LandscapeDockMode.Zip` / `Reading` / `Split`)
 * renders a single-row *view* of the portrait grid, so its column coordinates
 * don't match the persisted portrait grid. Drag-to-reorder is therefore
 * disabled in those modes — a drag would otherwise emit landscape columns that
 * `reorderDockedApps` would write back into the narrower portrait grid and
 * corrupt the saved arrangement. `Same` (and all of portrait) keeps drag on,
 * since the rendered grid *is* the portrait grid.
 *
 * The tablet-landscape window keeps the dock in the `Full` tier so it actually
 * renders. Eight docked apps at four icons per row form a two-row portrait grid.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w1280dp-h900dp-420dpi")
class LandscapeDockDragTest {
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

    private val reorders = mutableListOf<Triple<String, Int, Int>>()

    private fun setLauncherContent(mode: LandscapeDockMode) {
        reorders.clear()
        val docked = (1..8).map { index -> fakeApp("App%02d".format(index)).copy(isDocked = true) }
        // Empty persisted positions resolve to a contiguous 4-wide, two-row grid.
        val state = LauncherUiState(
            filteredApps = emptyList(),
            dockedApps = docked,
            dockIconCount = 4,
            landscapeDockMode = mode,
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
                    onReorderDock = { id, row, column -> reorders += Triple(id, row, column) },
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
    }

    private fun longPressAndDragFirstIcon() {
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_APP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            // Well past the 8 dp slop and across at least one neighbouring slot.
            moveBy(Offset(400f, 0f))
            up()
        }
        composeRule.waitForIdle()
    }

    @Test
    fun flattenModeDisablesDragReorder() {
        setLauncherContent(LandscapeDockMode.Split)

        longPressAndDragFirstIcon()

        assertTrue(
            "a landscape flatten is read-only; dragging must not reorder (got $reorders)",
            reorders.isEmpty(),
        )
    }

    @Test
    fun sameModeKeepsDragReorder() {
        // Positive control: proves the gesture harness actually drives a reorder
        // drag, so the flatten-mode assertion above isn't passing trivially.
        setLauncherContent(LandscapeDockMode.Same)

        longPressAndDragFirstIcon()

        assertTrue(
            "Same mode keeps the portrait grid, so dragging should still reorder",
            reorders.isNotEmpty(),
        )
        // And the move lands in real portrait coordinates (first row, a later column).
        assertEquals(0, reorders.last().second)
    }
}
