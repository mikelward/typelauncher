package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Renders the "Name below" app-list layout — the icon grid with each app's name
 * centered beneath its icon ([IconOnlyAppGrid] with `showLabel = true`) — so the
 * PR `roborazzi-screenshots` artifact captures the labeled tiles, including a
 * long name truncating to a single line with an ellipsis. Also asserts the
 * labels actually render (the icon-only grid draws no labels).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NameBelowGridScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val apps = listOf(
        "Calculator",
        "Camera",
        "Calendar",
        "Photos",
        "Settings",
        "A Very Long Application Name",
    ).map { installedApp(it) }

    @Test
    fun nameBelowGrid_rendersNamesUnderIcons() {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(208.dp)
                        .height(280.dp)
                        .background(MaterialBackground),
                ) {
                    IconOnlyAppGrid(
                        apps = apps,
                        dockLimit = Int.MAX_VALUE,
                        iconSizeDp = 43,
                        highlightFirst = false,
                        state = rememberLazyGridState(),
                        showLabel = true,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Calculator", useUnmergedTree = true).assertExists()

        capture("compose_name_below_grid_robolectric.png")
    }

    @Test
    fun nameBelowGrid_workProfileAppShowsWorkPrefix() {
        // Mixed personal + work-profile list: the work copy carries the
        // formatted "Work <name>" displayBase so its label disambiguates from
        // its personal twin in both the rendered grid and the typed-search
        // filter. The screenshot doubles as the spacing check — the longer
        // "Work Calendar" / "Work Photos" labels must wrap or ellipse without
        // pushing the icon row off the 4dp grid.
        val workMixedApps = listOf(
            installedApp("Calendar"),
            workInstalledApp("Calendar"),
            installedApp("Photos"),
            workInstalledApp("Photos"),
            installedApp("Settings"),
        )
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .width(208.dp)
                        .height(280.dp)
                        .background(MaterialBackground),
                ) {
                    IconOnlyAppGrid(
                        apps = workMixedApps,
                        dockLimit = Int.MAX_VALUE,
                        iconSizeDp = 43,
                        highlightFirst = false,
                        state = rememberLazyGridState(),
                        showLabel = true,
                        onLaunchApp = {},
                        onOpenAppInfo = {},
                        onToggleDock = { _, _ -> },
                        onResetRank = {},
                        onRenameApp = { _, _ -> },
                        onHideApp = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Calendar", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("Work Calendar", useUnmergedTree = true).assertExists()

        capture("compose_name_below_grid_work_prefix_robolectric.png")
    }

    private fun installedApp(name: String): InstalledApp {
        val component = ComponentName("com.example.${name.filter { it.isLetter() }}", "Main")
        return InstalledApp(
            name = name,
            packageName = "com.example.${name.filter { it.isLetter() }}",
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = true,
        )
    }

    // Same-package work-profile clone. Component name differs from
    // [installedApp]'s "Main" so InstalledApp.id stays distinct.
    private fun workInstalledApp(name: String): InstalledApp {
        val pkg = "com.example.${name.filter { it.isLetter() }}"
        val component = ComponentName(pkg, "WorkMain")
        return InstalledApp(
            name = name,
            packageName = pkg,
            launchIntent = Intent.makeMainActivity(component),
            user = Process.myUserHandle(),
            isWorkApp = true,
            launchWithLauncherApps = true,
            displayBase = "Work $name",
        )
    }

    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 980) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
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

    private companion object {
        val MaterialBackground = Color(0xFFEFEFEF)
    }
}
