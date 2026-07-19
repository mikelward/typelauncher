package app.typelauncher

import android.content.Intent
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Lives in its own class (a fresh Robolectric sandbox) rather than alongside
// SwipeUpRecentsTest: that class is deliberately calibrated to stay under an
// accumulated-composition threshold in Robolectric's shared Compose runtime
// (see its header comment), and adding another full home render there tips a
// later test's activity-launch renderer over the edge ("lightZ must be a finite
// positive"). A single-test class keeps this render isolated.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class WallpaperPullGestureTest {
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

    @Test
    fun enablingWallpaper_clearsStaleAppListBounds_soPullUpOverWallpaperOpensRecents() {
        // Regression: the wallpaper slot replaces AppsCard, which leaves
        // composition without emitting a final bounds clear. If the last
        // app-list bounds linger, the carousel treats a pull-up that starts
        // over the wallpaper (the region the list used to occupy) as an in-list
        // scroll and returns before dispatching — recents never opens. Render
        // the list first so it publishes real bounds, then flip wallpaper on
        // and pull up from the middle of the (now wallpaper) slot.
        var recentsTarget: Boolean? = null
        val state = mutableStateOf(
            LauncherUiState(
                dockIconSizeDp = dockIconSizeForSlotCount(411, 4),
                filteredApps = (1..12).map { i -> fakeApp(name = "App%02d".format(i)) },
                isWallpaperShown = false,
            ),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = state.value,
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
                    onShowAgenda = {},
                    onShowWidgets = {},
                    onShowHome = {},
                    onSetRecentsOpen = { recentsTarget = it },
                    appWidgetHost = null,
                    appWidgetManager = null,
                    onAddWidget = {},
                    onDismissWidgetPicker = {},
                    onSelectWidget = {},
                    onRemoveWidget = {},
                    onRequestCalendarPermission = {},
                    onOpenAgendaEvent = {},
                    onSwipeDown = {},
                )
            }
        }
        composeRule.waitForIdle()
        // The list is up and has published its bounds.
        composeRule.onNodeWithTag(APPS_CARD_TAG).assertIsDisplayed()

        state.value = state.value.copy(isWallpaperShown = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(HOME_WALLPAPER_TAG).assertIsDisplayed()

        // A pull-up starting in the middle of the wallpaper slot must still open
        // recents — the stale app-list reservation has to be gone.
        composeRule.onNodeWithTag(CAROUSEL_TAG).performTouchInput {
            swipeUp(startY = center.y, endY = center.y - 500f)
        }
        composeRule.waitForIdle()

        assertEquals(true, recentsTarget)
    }
}
