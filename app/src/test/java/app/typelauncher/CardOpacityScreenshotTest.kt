package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
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
 * Renders [SectionCard] at a couple of [LocalCardAlpha] levels over a bright
 * gradient (standing in for the wallpaper Robolectric can't composite) so the
 * PR `roborazzi-screenshots` artifact shows the "Card opacity" behavior: the
 * card's fill fades and the gradient shows through, while the card's text stays
 * fully opaque and legible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardOpacityScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sectionCard_fadesFillButKeepsContentOpaque() {
        composeRule.setContent {
            TypeLauncherTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(listOf(Color(0xFF1E88E5), Color(0xFFF4511E))),
                        ),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CompositionLocalProvider(LocalCardAlpha provides 1f) {
                            SectionCard(modifier = Modifier.width(280.dp)) {
                                Text("Fully opaque card")
                            }
                        }
                        CompositionLocalProvider(LocalCardAlpha provides 0.6f) {
                            SectionCard(modifier = Modifier.width(280.dp)) {
                                Text("60% card — wallpaper shows through")
                            }
                        }
                        CompositionLocalProvider(LocalCardAlpha provides 0.4f) {
                            SectionCard(modifier = Modifier.width(280.dp)) {
                                Text("40% card — text still legible")
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("60% card — wallpaper shows through").assertExists()

        capture("compose_card_opacity_robolectric.png")
    }

    private fun capture(name: String, widthPx: Int = 720, heightPx: Int = 700) {
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
