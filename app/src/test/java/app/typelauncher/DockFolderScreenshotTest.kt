package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Compose-only screenshot coverage for dock folders. Renders the internal
 * folder composables directly in a stub [ComponentActivity] — the folder popup
 * has no `TextField`, so it does not hit the Robolectric Dialog-idle cascade,
 * but composing the content body without the popup window keeps the render
 * deterministic anyway (the [EditAppDialogContent] precedent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun folderMiniIcon_rendersTwoByTwoCorners() {
        composeRule.setContent {
            TypeLauncherTheme {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    DockFolderMiniIcon(folder = sampleFolder(memberCount = 4), dockIconSizeDp = 43)
                    DockFolderMiniIcon(
                        folder = sampleFolder(memberCount = 4),
                        dockIconSizeDp = 43,
                        emphasized = true,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        captureSnapshot("compose_dock_folder_mini_icon_robolectric.png")
    }

    @Test
    fun folderGrid_rendersInDockLayout() {
        composeRule.setContent {
            TypeLauncherTheme {
                // The open folder reuses the dock's grid: five members across a
                // four-column dock wrap to a second row, with the trailing cell
                // padded so each tile keeps its 1/columns width.
                DockFolderGrid(
                    folder = sampleFolder(memberCount = 5, name = "Social"),
                    dockIconSizeDp = 43,
                    dockIconCount = 4,
                    dockLayout = DockLayout.TitleBelow,
                    modifier = Modifier.padding(16.dp),
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
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DOCK_FOLDER_POPUP_TAG, useUnmergedTree = true).assertExists()
        captureSnapshot("compose_dock_folder_popup_robolectric.png")
    }

    @Test
    fun folderActionsMenu_showsOpenFolderAndUngroup() {
        // The folder long-press menu's body (Open folder / Ungroup), rendered
        // without the LauncherDropdownMenu popup wrapper so Robolectric can
        // capture it — the EditAppDialogContent precedent. A fixed width stands
        // in for the popup surface the items would otherwise size against.
        composeRule.setContent {
            TypeLauncherTheme {
                Surface(
                    tonalElevation = 3.dp,
                    modifier = Modifier.padding(24.dp).width(220.dp),
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        DockFolderActionsMenuContent(
                            onOpenFolder = {},
                            onExplodeFolder = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Open folder", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Ungroup", useUnmergedTree = true).assertExists()
        captureSnapshot("compose_dock_folder_actions_menu_robolectric.png")
    }

    private fun sampleFolder(memberCount: Int, name: String? = null): ResolvedDockFolder {
        val members = (0 until memberCount).map { index ->
            installedApp(name = "App${index + 1}", packageName = "com.example.app${index + 1}")
        }
        return ResolvedDockFolder(
            id = DOCK_FOLDER_ID_PREFIX + "sample",
            members = members,
            name = name,
        )
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
