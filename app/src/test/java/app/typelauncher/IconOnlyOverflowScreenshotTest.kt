package app.typelauncher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconOnlyOverflowScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun iconOnlyOverflow_preservesIconGridGeometryWithChevronOverlay() {
        val apps = (1..60).map { index -> fakeApp("Overflow Icon %02d".format(index)) }
        composeRule.setContent {
            TypeLauncherTheme {
                TypeLauncherApp(
                    state = LauncherUiState(filteredApps = apps, isAppListIconOnly = true),
                    onQueryChanged = {},
                    onClearQuery = {},
                    onLaunchActiveApp = {},
                    onLaunchApp = {},
                    onOpenAppInfo = {},
                    onToggleDock = { _, _ -> },
                    onResetRank = {},
                    onHideApp = {},
                    onUnhideApp = {},
                    onOpenSettings = {},
                    onCloseSettings = {},
                    onRequestDefaultLauncher = {},
                    onDockEnabledChanged = {},
                    onAppListIconOnlyChanged = {},
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

        composeRule.onNodeWithTag(APPS_LIST_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(APPS_LIST_SCROLL_BOTTOM_CHEVRON_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Overflow Icon 01").assertIsDisplayed()
        composeRule.onNodeWithTag("$APP_ICON_ONLY_BUTTON_TAG:Overflow Icon 12").assertIsDisplayed()

        val firstIconBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_ICON_TAG:Overflow Icon 01").getBoundsInRoot()
        val twelfthIconBounds = composeRule.onNodeWithTag("$APP_ICON_ONLY_ICON_TAG:Overflow Icon 12").getBoundsInRoot()
        assertEquals(72f, (firstIconBounds.right - firstIconBounds.left).value, 1f)
        assertEquals(72f, (firstIconBounds.bottom - firstIconBounds.top).value, 1f)
        assertEquals(72f, (twelfthIconBounds.right - twelfthIconBounds.left).value, 1f)
        assertEquals(72f, (twelfthIconBounds.bottom - twelfthIconBounds.top).value, 1f)

        saveScreenshot("compose_icon_only_overflow_chevron_robolectric.png")
    }

    private fun saveScreenshot(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 1080, 2400)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun fakeApp(name: String): InstalledApp =
        InstalledApp(
            name = name,
            packageName = "app.typelauncher.fake.$name",
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
        )
}
