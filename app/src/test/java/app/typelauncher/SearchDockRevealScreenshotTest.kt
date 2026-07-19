package app.typelauncher

import android.content.Intent
import android.os.Process
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the search-time dock reveal mid-drag: a query is active (dock slot
 * hidden), a list app has been long-pressed and picked up into the floating
 * drag overlay, and the dock floats at the bottom as the drop target, without
 * the app list above moving. The PR `roborazzi-screenshots` artifact captures
 * visual changes to the revealed dock's placement.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchDockRevealScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun fakeApp(name: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )

    @Test
    fun searchTimeReveal_showsDockUnderFilteredList() {
        val apps = (1..8).map { fakeApp("Calc%02d".format(it)) }
        var state by mutableStateOf(
            LauncherUiState(
                query = "ca",
                filteredApps = apps,
                dockedApps = listOf(fakeApp("Docked01").copy(isDocked = true)),
                // Seed a persisted keyboard reservation so the layout holds a
                // keyboard-height band at the bottom (as it does on a device
                // mid-search) and the revealed dock renders inside that band —
                // without it, Robolectric has no IME, the reservation is zero,
                // and the reveal falls back to the drop-shelf overlap where the
                // dock card is visually indistinguishable from the apps card.
                keyboardReservation = KeyboardReservation(bottomPx = 900),
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state,
                    onQueryChanged = { state = state.copy(query = it) },
                    onClearQuery = { state = state.copy(query = "") },
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

        // Long-press a list app and hold the drag mid-flight (no up) so the
        // reveal stays composed for the capture.
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        composeRule.onNodeWithTag("$APP_ROW_TAG:Calc01").performTouchInput {
            down(Offset(width / 2f, height / 2f))
            move(longPressMs + 100)
            moveBy(Offset(0f, 120f))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(SEARCH_DOCK_REVEAL_TAG).assertExists()

        capture("compose_search_dock_reveal_robolectric.png")
    }

    private fun capture(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
        captureScreenRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
