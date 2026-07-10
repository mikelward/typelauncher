package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pins the work-badge fix: the system briefcase used to be composited into the
 * icon bitmap, which the launcher then clipped to a circle — slicing off the
 * corner of the badge. The badge now renders as a standalone overlay drawn
 * *outside* the circular clip, so it survives at the icon's corner.
 *
 * The test reuses [AppIconLoaderWorkBadgeTest.BadgingShadowPackageManager] to
 * stand in a red disc for the OEM briefcase (Robolectric's PackageManager
 * doesn't badge), then asserts a badge pixel lands in the icon corner that sits
 * *beyond* the inscribed circle — exactly the region the old clip removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = "w411dp-h914dp-420dpi",
    shadows = [AppIconLoaderWorkBadgeTest.BadgingShadowPackageManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconWorkBadgeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val iconSizeDp = 96.dp

    @Before
    fun warmCaches() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(context.packageManager).addActivityIcon(
            WORK_COMPONENT,
            ColorDrawable(Color.rgb(0x1A, 0x73, 0xE8)),
        )
        val density = context.resources.displayMetrics.density
        val sizePx = (iconSizeDp.value * density).roundToInt()
        // Pre-warm both shared caches at the exact pixel size the composable
        // will request, so the icon and its badge overlay render on the first
        // frame instead of racing an async load through the screenshot capture.
        runBlocking {
            AppIconLoader.load(context, workApp(), sizePx)
            AppIconLoader.loadWorkBadge(context, Process.myUserHandle(), sizePx)
        }
    }

    @Test
    fun workBadgeRendersOutsideTheCircularClip() {
        composeRule.setContent {
            TypeLauncherTheme {
                // Pin the circular shape so the "badge survives outside the
                // inscribed circle" assertion is exact regardless of the
                // default (System) icon-shape mask.
                CompositionLocalProvider(LocalAppIconShape provides IconShape.Circle) {
                    Box(modifier = Modifier.padding(8.dp)) {
                        AppIcon(app = workApp(), size = iconSizeDp)
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule
            .onNodeWithTag("$APP_ICON_WORK_BADGE_TAG:Work Mail", useUnmergedTree = true)
            .assertIsDisplayed()

        captureSnapshot("compose_app_icon_work_badge_robolectric.png")
        assertBadgePixelSurvivesOutsideCircle()
    }

    /**
     * Reads the captured icon and asserts a badge pixel exists in the corner
     * region that falls outside the icon's inscribed circle. If the badge were
     * still clipped to the circle (the bug), that corner would be empty.
     */
    private fun assertBadgePixelSurvivesOutsideCircle() {
        val density = composeRule.density.density
        val iconBounds = composeRule.onNodeWithTag(APP_ICON_TAG_FOR_WORK, useUnmergedTree = true)
            .getBoundsInRoot()
        val left = (iconBounds.left.value * density).roundToInt()
        val top = (iconBounds.top.value * density).roundToInt()
        val sizePx = ((iconBounds.right - iconBounds.left).value * density).roundToInt()

        val root = captureRootBitmap()
        val center = sizePx / 2f
        // A point inside the badge disc (centred near 0.8·size, radius 0.2·size)
        // but beyond the inscribed circle's radius (sizePx/2) — the slice the
        // old clip discarded. On the diagonal a point needs fraction > ~0.85 to
        // clear the circle; 0.88 sits safely outside it yet inside the disc.
        val sample = (sizePx * 0.88f).roundToInt()
        val distanceFromCenter = sqrt(
            ((sample - center) * (sample - center) + (sample - center) * (sample - center)).toDouble(),
        )
        assertTrue(
            "sample point must lie outside the inscribed circle to prove the badge isn't clipped",
            distanceFromCenter > center,
        )
        val pixel = root.getPixel(left + sample, top + sample)
        assertTrue(
            "badge must remain visible in the corner outside the circle, was #${Integer.toHexString(pixel)}",
            Color.alpha(pixel) > 200 && Color.red(pixel) > 200 &&
                Color.green(pixel) < 80 && Color.blue(pixel) < 80,
        )
    }

    private fun captureRootBitmap(): Bitmap {
        val root = composeRule.activity.window.decorView.rootView
        val bitmap = Bitmap.createBitmap(
            root.width.coerceAtLeast(1),
            root.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        root.draw(Canvas(bitmap))
        return bitmap
    }

    private fun captureSnapshot(name: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return
        // Icon bitmaps load on background dispatchers; capture only once
        // every composed icon has settled (see awaitAppIconsResolved).
        composeRule.awaitAppIconsResolved()
        captureRootBitmap().captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun workApp() = InstalledApp(
        name = "Work Mail",
        packageName = WORK_PACKAGE,
        launchIntent = Intent.makeMainActivity(WORK_COMPONENT),
        user = Process.myUserHandle(),
        isWorkApp = true,
        launchWithLauncherApps = false,
    )

    private companion object {
        const val WORK_PACKAGE = "app.typelauncher.work.mail"
        val WORK_COMPONENT = ComponentName(WORK_PACKAGE, "$WORK_PACKAGE.LaunchActivity")
        const val APP_ICON_TAG_FOR_WORK = "$APP_ICON_TAG:Work Mail"
    }
}
