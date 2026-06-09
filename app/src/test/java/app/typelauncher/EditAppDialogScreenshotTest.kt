package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-only test for [EditAppDialog]. The dialog can't be exercised
 * through the running launcher window in
 * [MainActivityRobolectricScreenshotTest] because opening it from a
 * [androidx.compose.material3.DropdownMenu] item triggers a Compose
 * composition loop under Robolectric — the field's focus / IME interactions
 * cascade with Home's keyboard-reservation recompute and never settle within
 * the 60-second idle timeout. Hosting the dialog in a stub
 * [ComponentActivity] (which has none of the launcher's IME plumbing)
 * sidesteps that loop and exercises the same view layer the production code
 * uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditAppDialogScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun editDialogWithoutOverride_showsFieldOriginalNameAndPackageButHidesRestore() {
        composeRule.setContent {
            TypeLauncherTheme {
                EditAppDialogContent(
                    app = installedApp(name = "ChatGPT", packageName = "com.openai.chatgpt"),
                    onSave = {},
                    onRestoreDefaults = {},
                    onPickIcon = {},
                    onClearIcon = {},
                    onSetBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_APP_DIALOG_FIELD_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EDIT_APP_DIALOG_PACKAGE_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EDIT_APP_DIALOG_SAVE_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(EDIT_APP_DIALOG_CANCEL_TAG, useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag(EDIT_APP_DIALOG_RESTORE_TAG, useUnmergedTree = true)
            .assertDoesNotExist()

        captureSnapshot("compose_edit_app_dialog_no_override_robolectric.png")
    }

    @Test
    fun editDialogWithOverride_alsoShowsRestoreDefaultsButton() {
        composeRule.setContent {
            TypeLauncherTheme {
                EditAppDialogContent(
                    app = installedApp(name = "ChatGPT", packageName = "com.openai.chatgpt")
                        .copy(customName = "Codex"),
                    onSave = {},
                    onRestoreDefaults = {},
                    onPickIcon = {},
                    onClearIcon = {},
                    onSetBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(EDIT_APP_DIALOG_RESTORE_TAG, useUnmergedTree = true).assertIsDisplayed()

        captureSnapshot("compose_edit_app_dialog_with_override_robolectric.png")
    }

    @Test
    fun editDialogAlwaysShowsChooseIconButton() {
        composeRule.setContent {
            TypeLauncherTheme {
                EditAppDialogContent(
                    app = installedApp(name = "ChatGPT", packageName = "com.openai.chatgpt"),
                    onSave = {},
                    onRestoreDefaults = {},
                    onPickIcon = {},
                    onClearIcon = {},
                    onSetBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Choose-icon is always visible; the clear-icon row only appears when an
        // override is currently set.
        composeRule
            .onNodeWithTag(EDIT_APP_DIALOG_CHOOSE_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithTag(EDIT_APP_DIALOG_CLEAR_ICON_TAG, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun editDialogWithIconOverride_alsoShowsClearIconButton() {
        composeRule.setContent {
            TypeLauncherTheme {
                EditAppDialogContent(
                    app = installedApp(name = "ChatGPT", packageName = "com.openai.chatgpt")
                        .copy(customIconPath = "/data/example/override.svg", customIconVersion = 42L),
                    onSave = {},
                    onRestoreDefaults = {},
                    onPickIcon = {},
                    onClearIcon = {},
                    onSetBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag(EDIT_APP_DIALOG_CLEAR_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()

        captureSnapshot("compose_edit_app_dialog_with_icon_override_robolectric.png")
    }

    @Test
    fun renameFieldKeepsTypedTextAcrossConfigurationChange() {
        // Rotating (or any configuration change) while the rename dialog is open
        // must not throw away the user's half-typed name. The field state is
        // `rememberSaveable`, so emulating a saved-instance-state restore should
        // bring the typed text back — a plain `remember` would reset it to the
        // app's current name.
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            TypeLauncherTheme {
                EditAppDialogContent(
                    app = installedApp(name = "ChatGPT", packageName = "com.openai.chatgpt"),
                    onSave = {},
                    onRestoreDefaults = {},
                    onPickIcon = {},
                    onClearIcon = {},
                    onSetBadge = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.onNodeWithTag(EDIT_APP_DIALOG_FIELD_TAG).performTextReplacement("My Custom Name")
        composeRule.waitForIdle()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithTag(EDIT_APP_DIALOG_FIELD_TAG).assertTextContains("My Custom Name")
    }

    private fun installedApp(name: String, packageName: String): InstalledApp {
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

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(heightPx, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
