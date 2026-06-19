package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Resting-state screenshot coverage for the dock folder open styles. Each renders
 * the settled layout (the fly-out animation's final frame, which the default
 * `appearProgress` of 1 produces) so the per-style placement is reviewable:
 * Directional / Bloom / Zoom anchor the apps to a right-half folder cell, and the
 * Overlay card floats sized-to-content. The animation itself isn't snapshotted —
 * it's covered by [DockFolderSlotsTest] and inspection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderOpenStyleScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Distinct tile colors so the Overlay preview reads as real app icons.
    private val PREVIEW_COLORS = intArrayOf(
        0xFF4285F4.toInt(), // blue
        0xFFEA4335.toInt(), // red
        0xFF34A853.toInt(), // green
        0xFFFBBC05.toInt(), // yellow
        0xFFA142F4.toInt(), // purple
    )

    private val iconDp = 43
    private val columns = 6
    // Folder near the right edge: the interesting case where Directional flows
    // right-to-left and Bloom/Zoom hug the cell instead of overflowing.
    private val anchor = DockPosition(0, 4)

    @Test
    fun directional_rightHalfFlowsFromFolderCell() {
        captureGrid("directional", "Directional — flows left from the folder cell", DockFolderOpenStyle.Directional)
    }

    @Test
    fun bloom_claimsNearestCellsAroundFolder() {
        captureGrid("bloom", "Bloom — nearest cells around the folder", DockFolderOpenStyle.Bloom)
    }

    @Test
    fun zoom_unfoldsAroundFolder() {
        captureGrid("zoom", "Zoom — block around the folder", DockFolderOpenStyle.Zoom)
    }

    @Test
    fun overlay_floatsCompactCard() {
        // Unique package names so the injected colored icons below don't leak
        // into other tests' snapshots via the process-wide AppIconLoader cache.
        val folder = sampleFolder(5, name = "Social", packagePrefix = "com.example.overlaypreview")
        composeRule.setContent {
            TypeLauncherTheme {
                // Real screenshots have icon art; give the placeholder apps solid
                // colored tiles so the preview isn't gray icons on a gray card.
                val sizePx = with(LocalDensity.current) { iconDp.dp.roundToPx() }
                remember(sizePx) {
                    folder.members.forEachIndexed { index, app ->
                        AppIconLoader.put(app.iconCacheId, sizePx, solidIcon(sizePx, PREVIEW_COLORS[index]))
                    }
                    0
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Overlay — compact floating card")
                    DockFolderOverlayCard(
                        folder = folder,
                        dockIconSizeDp = iconDp,
                        dockIconCount = columns,
                        dockLayout = DockLayout.TitleBelow,
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
        }
        composeRule.waitForIdle()
        // Taller canvas: the near-square grid makes the card multi-row, so 700px
        // would clip the bottom row's labels.
        captureSnapshot("compose_dock_folder_overlay_robolectric.png", heightPx = 900)
    }

    @Composable
    private fun StyledGrid(style: DockFolderOpenStyle) {
        DockFolderGrid(
            folder = sampleFolder(5),
            dockIconSizeDp = iconDp,
            dockIconCount = columns,
            dockLayout = DockLayout.TitleBelow,
            modifier = Modifier.fillMaxWidth(),
            anchor = anchor,
            dockRows = 2,
            openStyle = style,
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

    private fun captureGrid(name: String, title: String, style: DockFolderOpenStyle) {
        composeRule.setContent {
            TypeLauncherTheme {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(title)
                    Box(modifier = Modifier.fillMaxWidth()) {
                        StyledGrid(style)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("compose_dock_folder_style_${name}_robolectric.png")
    }

    private fun sampleFolder(
        memberCount: Int,
        name: String? = null,
        packagePrefix: String = "com.example.app",
    ): ResolvedDockFolder {
        val members = (0 until memberCount).map { index ->
            installedApp(name = "App${index + 1}", packageName = "$packagePrefix${index + 1}")
        }
        return ResolvedDockFolder(
            id = DOCK_FOLDER_ID_PREFIX + "sample",
            members = members,
            name = name,
        )
    }

    private fun solidIcon(sizePx: Int, color: Int) =
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).drawColor(color)
        }.asImageBitmap()

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

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 700) {
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
