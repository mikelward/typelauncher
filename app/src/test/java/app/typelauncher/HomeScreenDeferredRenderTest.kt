package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class HomeScreenDeferredRenderTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeScreen_rendersSearchFieldBeforeAppList_onFirstFrame() {
        val apps = listOf(installedApp("Calculator"))

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            TypeLauncherTheme {
                HomeScreen(
                    state = LauncherUiState(filteredApps = apps, isDockEnabled = false),
                    innerPadding = PaddingValues(),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onOpenSettings = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()

        // First frame: SearchCard is composed but the apps grid is held back so
        // SearchCard's LaunchedEffect can fire keyboard.show() before the main
        // thread is busy measuring app rows.
        composeRule.onNodeWithTag(SEARCH_FIELD_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Calculator").assertDoesNotExist()

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()

        // Next frame: the deferred LaunchedEffect resumes, bodyReady flips,
        // and the apps grid lands.
        composeRule.onNodeWithText("Calculator").assertIsDisplayed()
    }

    private fun installedApp(name: String): InstalledApp {
        val packageName = "app.${name.lowercase().replace(' ', '.')}"
        val component = ComponentName(packageName, "$packageName.LaunchActivity")
        return InstalledApp(
            name = name,
            packageName = packageName,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }
}
