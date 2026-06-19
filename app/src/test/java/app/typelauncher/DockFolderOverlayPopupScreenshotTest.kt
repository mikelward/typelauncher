package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Screenshot coverage for the [DockFolderOpenStyle.Overlay] card *through the live
 * positioned popup path* — the production [Popup] +
 * [dockFolderOverlayPositionProvider] that [DockFolderOpenStyleScreenshotTest]
 * deliberately skips (it renders the card alone so a decor-view capture can see
 * it). Here a stand-in dock card hosts the folder cell, the real popup floats the
 * overlay above it, and `captureScreenRoboImage` grabs every window so the
 * no-gap overlay/dock seam and the on-screen placement are reviewable.
 *
 * The Rectangle case also feeds the stand-in dock card's measured width/left into
 * the overlay (as `DockCard` does in production), so the capture shows the
 * full-width card matching the dock card's bounds rather than the popup window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DockFolderOverlayPopupScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val PREVIEW_COLORS = intArrayOf(
        0xFF4285F4.toInt(),
        0xFFEA4335.toInt(),
        0xFF34A853.toInt(),
        0xFFFBBC05.toInt(),
        0xFFA142F4.toInt(),
    )

    private val iconDp = 43

    @Test
    fun overlay_compactPopupSitsFlushAboveTheFolderCell() {
        captureOverlayPopup(
            name = "compose_dock_folder_overlay_popup_robolectric.png",
            shape = DockFolderOverlayShape.Compact,
            packagePrefix = "com.example.overlaypopup",
        )
    }

    @Test
    fun overlay_rectanglePopupMatchesTheDockCardWidth() {
        captureOverlayPopup(
            name = "compose_dock_folder_overlay_popup_rectangle_robolectric.png",
            shape = DockFolderOverlayShape.Rectangle,
            packagePrefix = "com.example.overlaypopuprect",
        )
    }

    private fun captureOverlayPopup(
        name: String,
        shape: DockFolderOverlayShape,
        packagePrefix: String,
    ) {
        val folder = sampleFolder(4, name = "Social", packagePrefix = packagePrefix)
        composeRule.setContent {
            TypeLauncherTheme {
                val density = LocalDensity.current
                val sizePx = with(density) { iconDp.dp.roundToPx() }
                remember(sizePx) {
                    folder.members.forEachIndexed { index, app ->
                        AppIconLoader.put(app.iconCacheId, sizePx, solidIcon(sizePx, PREVIEW_COLORS[index]))
                    }
                    0
                }
                // Mirror DockCard: capture the dock card's window bounds so the
                // Rectangle overlay matches its width/left edge.
                var dockCardLeftPx by remember { mutableStateOf<Int?>(null) }
                var dockCardWidthDp by remember { mutableStateOf<Int?>(null) }
                val positionProvider = remember(shape) {
                    dockFolderOverlayPositionProvider(
                        fullWidth = shape == DockFolderOverlayShape.Rectangle,
                        dockCardLeftPx = { dockCardLeftPx },
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    // Push the stand-in dock down the screen so the popup, which
                    // floats above its anchor, has room to land un-clamped.
                    Column(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.weight(1f))
                        SectionCard(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                dockCardLeftPx = coords.positionInWindow().x.roundToInt()
                                dockCardWidthDp = with(density) { coords.size.width.toDp().value.roundToInt() }
                            },
                        ) {
                            androidx.compose.foundation.layout.Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                // Two plain neighbor cells, then the open folder cell.
                                repeat(2) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height((iconDp + 8).dp)
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    // The folder cell becomes the close button and
                                    // anchors the floating overlay popup, exactly as
                                    // production does.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height((iconDp + 8).dp)
                                            .background(MaterialTheme.colorScheme.secondaryContainer),
                                    )
                                    Popup(
                                        popupPositionProvider = positionProvider,
                                        properties = PopupProperties(focusable = false),
                                    ) {
                                        DockFolderOverlayCard(
                                            folder = folder,
                                            dockIconSizeDp = iconDp,
                                            dockIconCount = 5,
                                            dockLayout = DockLayout.TitleBelow,
                                            shape = shape,
                                            dockCardWidthDp = dockCardWidthDp,
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
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        captureScreen(name)
    }

    private fun captureScreen(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        captureScreenRoboImage(filePath = "src/test/snapshots/images/$name")
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
}
