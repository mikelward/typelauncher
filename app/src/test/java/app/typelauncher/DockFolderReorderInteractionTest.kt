package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Interaction coverage for the open-folder member drag-reorder wiring in
 * [DockFolderGrid] — the two failure modes a code review flagged:
 *
 *  - **Gesture identity across reorders.** A live reorder shuffles which member
 *    each cell renders; without `key(app.id)` on the FlowRow children Compose
 *    reuses the cell groups positionally and tears down the dragged tile's
 *    `pointerInput(app.id)` after the first move. The multi-cell drag test fails
 *    (the icon only advances one slot) before the keying fix.
 *  - **Carousel suppression.** The member long-press must signal armed state so
 *    the Home carousel stops claiming the same horizontal motion. The arm test
 *    fails (callback never fires) before the wiring is added.
 *
 * Renders [DockFolderGrid] directly in a stub activity (the folder grid has no
 * popup window, mirroring [DockFolderScreenshotTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class DockFolderReorderInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun member(name: String): InstalledApp {
        val component = ComponentName("com.example.$name", "com.example.$name.Launch")
        return InstalledApp(
            name = name,
            packageName = "com.example.$name",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    // Mirrors DockedAppStore.reorderFolderMember: remove then insert at the
    // clamped target index, so the test's state evolves exactly like production.
    private fun reorder(ids: List<String>, appId: String, targetIndex: Int): List<String> {
        val current = ids.indexOf(appId)
        if (current < 0) return ids
        val clamped = targetIndex.coerceIn(0, ids.size - 1)
        if (clamped == current) return ids
        val out = ids.toMutableList()
        out.removeAt(current)
        out.add(clamped, appId)
        return out
    }

    @Test
    fun longPressArmingAMemberSignalsCarouselSuppression() {
        val apps = listOf("App01", "App02", "App03", "App04").map { member(it) }
        val folder = ResolvedDockFolder(id = DOCK_FOLDER_ID_PREFIX + "t", members = apps)
        // The latch flips back to false when the gesture ends, so record whether
        // it was ever armed rather than its final value.
        var armedEver = false
        composeRule.setContent {
            TypeLauncherTheme {
                DockFolderGrid(
                    folder = folder,
                    dockIconSizeDp = 43,
                    dockIconCount = 4,
                    dockLayout = DockLayout.TitleBelow,
                    modifier = Modifier,
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onRemoveFromFolder = {},
                    onUndockFromFolder = {},
                    onRenameApp = { _, _ -> },
                    onResetRank = {},
                    onSetAppIconOverride = {},
                    onClearAppIconOverride = {},
                    onSetAppBadge = { _, _ -> },
                    onHideApp = {},
                    onMemberDragStateChanged = { if (it) armedEver = true },
                )
            }
        }
        composeRule.waitForIdle()

        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$DOCK_FOLDER_POPUP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            up()
        }
        composeRule.waitForIdle()

        assertEquals("long-press must arm carousel suppression", true, armedEver)
    }

    @Test
    fun draggingAMemberAcrossTwoNeighborsSurvivesTheFirstReorder() {
        val apps = listOf("App01", "App02", "App03", "App04").map { member(it) }
        val byId = apps.associateBy { it.id }
        var order by mutableStateOf(apps.map { it.id })
        composeRule.setContent {
            TypeLauncherTheme {
                DockFolderGrid(
                    folder = ResolvedDockFolder(
                        id = DOCK_FOLDER_ID_PREFIX + "t",
                        members = order.map { byId.getValue(it) },
                    ),
                    dockIconSizeDp = 43,
                    dockIconCount = 4,
                    dockLayout = DockLayout.TitleBelow,
                    modifier = Modifier,
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onRemoveFromFolder = {},
                    onUndockFromFolder = {},
                    onRenameApp = { _, _ -> },
                    onResetRank = {},
                    onSetAppIconOverride = {},
                    onClearAppIconOverride = {},
                    onSetAppBadge = { _, _ -> },
                    onHideApp = {},
                    onReorderFolderMember = { appId, index -> order = reorder(order, appId, index) },
                )
            }
        }
        composeRule.waitForIdle()

        val app01 = apps[0].id
        // One cell pitch at this width (411dp / 4 columns) is ~270px; a 300px step
        // crosses exactly one neighbor's center per event.
        val pitchPx = 300f
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        // Long-press App01 and drag it one pitch right — reorders it past its
        // first neighbor — then leave the finger down.
        composeRule.onNodeWithTag("$DOCK_FOLDER_POPUP_TAG:App01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            moveBy(Offset(pitchPx, 0f))
        }
        // Let the reorder recompose and relay the cells out — the same frame
        // boundary a real drag crosses between move events. The dragged tile's
        // pointerInput must survive this (keyed FlowRow cells); without the key
        // its gesture is torn down here and the continued drag below is lost.
        composeRule.waitForIdle()
        assertEquals("first step should move App01 one slot", 1, order.indexOf(app01))

        // Continue the same (un-lifted) pointer one more pitch right. This only
        // reorders again if the gesture survived the recomposition above.
        composeRule.onRoot().performTouchInput {
            moveBy(Offset(pitchPx, 0f))
            up()
        }
        composeRule.waitForIdle()

        val finalIndex = order.indexOf(app01)
        assertTrue(
            "App01 must keep advancing after the first reorder — its drag gesture " +
                "must survive the relayout (ended at index $finalIndex in $order)",
            finalIndex >= 2,
        )
    }
}
