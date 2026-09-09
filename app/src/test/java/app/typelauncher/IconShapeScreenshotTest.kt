package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Path as AndroidPath

/**
 * Renders a representative app-icon tile clipped to each selectable
 * [IconShape] (System / Circle / Squircle), using the real production
 * `toComposeShape()` outlines, so the PR `roborazzi-screenshots` artifact shows
 * the shape options. Mirrors `IconNormalizationScreenshotTest`'s raw-bitmap
 * capture for a stable golden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconShapeScreenshotTest {

    @Test
    fun iconShapes() {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return

        val tile = 144
        val gap = 24
        val shapes = listOf(IconShape.System, IconShape.Circle, IconShape.Squircle)

        // A solid colored tile so each shape's outline reads clearly against the
        // strip background.
        val sample = IconNormalizer.normalizeToTile(ColorDrawable(Color.rgb(0x1A, 0x73, 0xE8)), tile)

        val width = tile * shapes.size + gap * (shapes.size + 1)
        val height = tile + gap * 2
        val strip = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(Color.WHITE)

        var left = gap
        for (shape in shapes) {
            val path = shapeAndroidPath(shape, tile.toFloat()).apply { offset(left.toFloat(), gap.toFloat()) }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(sample, left.toFloat(), gap.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            left += tile + gap
        }

        strip.captureRoboImage(filePath = "src/test/snapshots/images/compose_icon_shapes_robolectric.png")
    }

    // Uses the same path builders production resolves each shape to (a circle for
    // System when the test device exposes no mask).
    private fun shapeAndroidPath(shape: IconShape, size: Float): AndroidPath = when (shape) {
        IconShape.System -> systemIconMaskAndroidPath(size, size) ?: circlePath(size)
        IconShape.Circle -> circlePath(size)
        IconShape.Squircle -> squircleAndroidPath(size, size)
    }

    private fun circlePath(size: Float): AndroidPath =
        AndroidPath().apply { addCircle(size / 2f, size / 2f, size / 2f, AndroidPath.Direction.CW) }
}
