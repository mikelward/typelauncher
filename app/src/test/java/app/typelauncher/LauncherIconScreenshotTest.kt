package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconScreenshotTest {

    @Test
    fun releaseIcon() {
        captureIcon(R.mipmap.ic_launcher, "ic_launcher_release_robolectric.png")
    }

    @Test
    fun localIcon() {
        captureIcon(R.mipmap.ic_launcher_local, "ic_launcher_local_robolectric.png")
    }

    private fun captureIcon(resId: Int, filename: String) {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val size = 432
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val drawable = context.getDrawable(resId)!!
        // Draw the adaptive icon's background and foreground layers directly at
        // full bounds rather than letting AdaptiveIconDrawable.draw() composite
        // them. Its direct-draw path oversizes the layers by the 18dp safe-zone
        // ring and clips it away, which hides the local build's "DEV" banner
        // (it sits in that outer ring) and made the local and release goldens
        // render identically. The exact amount of oversizing also shifted
        // between Robolectric 4.16 and 4.16.1, so the layer-by-layer 1:1 draw is
        // both faithful to the icon art and stable across tooling bumps.
        if (drawable is AdaptiveIconDrawable) {
            drawable.background?.apply {
                setBounds(0, 0, size, size)
                draw(canvas)
            }
            drawable.foreground?.apply {
                setBounds(0, 0, size, size)
                draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$filename")
    }
}
