package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
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

    @Test
    fun debugIcon() {
        // The tester build's icon. It shares the DEV badge's bar and colors and
        // differs only in the word, and "DEBUG" is the longer word in the same
        // bar — so this is where unreadable or clipped lettering would show.
        captureIcon(R.mipmap.ic_launcher_debug, "ic_launcher_debug_robolectric.png")
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
        // Render the adaptive icon the way a launcher ships it, so the golden
        // reflects what actually reaches the home screen: only the central 72dp of
        // the 108dp art survives (the outer 18dp is the safe-zone ring every mask
        // crops), and the result is clipped to a circle — the most aggressive of
        // the standard Pixel-style masks.
        //
        // We composite the layers ourselves instead of calling
        // AdaptiveIconDrawable.draw() because that path's exact oversizing shifted
        // between Robolectric 4.16 and 4.16.1. Oversizing each layer to 1.5x the
        // bounds maps art coordinates 18..90 onto 0..size — i.e. drops the
        // safe-zone ring — and the circular clip removes the corners. A "DEV"
        // banner placed in the cropped ring (the original bug) renders blank here;
        // one that lives inside the safe zone survives, exactly as on the device.
        if (drawable is AdaptiveIconDrawable) {
            val inset = size / 4
            val circle = Path().apply {
                addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(circle)
            drawable.background?.apply {
                setBounds(-inset, -inset, size + inset, size + inset)
                draw(canvas)
            }
            drawable.foreground?.apply {
                setBounds(-inset, -inset, size + inset, size + inset)
                draw(canvas)
            }
            canvas.restore()
        } else {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
        }
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$filename")
    }
}
