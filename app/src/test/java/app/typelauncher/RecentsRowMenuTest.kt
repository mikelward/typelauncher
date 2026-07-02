package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers per-app state identity in the recents bar. Each recent app's
 * long-press menu state lives in its own composable; the row must key those
 * children by app id so a background reload that mutates the recents list
 * (package event, work-profile pause, hidden-app change) can't positionally
 * hand one app's open menu to whichever app slides into its slot — before the
 * `key(app.id)` fix, "Dismiss" then acted on the wrong app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class RecentsRowMenuTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun app(name: String): InstalledApp {
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

    @Test
    fun openMenuStaysOnItsAppWhenAnEarlierRecentIsRemoved() {
        val appA = app("AppA")
        val appB = app("AppB")
        val appC = app("AppC")
        var recents by mutableStateOf(listOf(appA, appB, appC))
        val dismissed = mutableListOf<InstalledApp>()
        composeRule.setContent {
            TypeLauncherTheme {
                RecentsRow(
                    recentApps = recents,
                    dockIconSizeDp = 43,
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onDismissRecent = { dismissed += it },
                )
            }
        }
        composeRule.waitForIdle()

        // Open AppB's long-press menu…
        composeRule.onNodeWithTag("$DOCK_RECENTS_APP_TAG:AppB")
            .performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:AppB").assertExists()

        // …then a background reload drops AppA from the list while the menu is
        // still open. The open menu must stay AppB's, not migrate to AppC.
        recents = listOf(appB, appC)
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:AppB").assertExists()
        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:AppC").assertDoesNotExist()

        // And its actions must target AppB.
        composeRule.onNodeWithTag("$DISMISS_RECENT_ACTION_TAG:AppB").performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(appB), dismissed)
    }
}
