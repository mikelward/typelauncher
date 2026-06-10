package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconNormalizerTest {

    @Test
    fun fullyOpaqueIconReportsFullCoverageAndItsColor() {
        val analysis = IconNormalizer.analyze(solid(64, Color.RED))

        assertEquals(1f, analysis.coverage, 0.001f)
        assertEquals(Rect(0, 0, 64, 64), analysis.bounds)
        assertColorClose(Color.RED, analysis.dominantColor)
    }

    @Test
    fun paddedIconReportsTightBoundsLowCoverageAndShapeColor() {
        // A red square occupying the center 32x32 of a 64x64 transparent canvas
        // — the "colored shape with transparent padding" case.
        val analysis = IconNormalizer.analyze(squareOnTransparent(64, Rect(16, 16, 48, 48), Color.RED))

        assertTrue("coverage was ${analysis.coverage}", analysis.coverage < 0.3f)
        assertEquals(Rect(16, 16, 48, 48), analysis.bounds)
        assertColorClose(Color.RED, analysis.dominantColor)
    }

    @Test
    fun fullBleedDrawableFillsTileWithNoRing() {
        val tile = IconNormalizer.normalizeToTile(ColorDrawable(Color.GREEN), 64)

        // Drawn edge-to-edge: every pixel, corners included, is the icon's own
        // color — the plate underneath is fully covered, so no ring appears.
        assertColorClose(Color.GREEN, tile.getPixel(0, 0))
        assertColorClose(Color.GREEN, tile.getPixel(63, 63))
        assertColorClose(Color.GREEN, tile.getPixel(32, 32))
    }

    @Test
    fun nearlyFullIconWithTransparentCornersHasCornersPlated() {
        // A rounded square (~95% opaque) hits the full-bleed branch, but its
        // transparent corners — rounded tighter than the launcher's clip — must
        // still be plated so they don't expose the gray surface. Regression for
        // the coverage-only decision that returned such icons raw.
        val drawable = BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            roundedSquareOnTransparent(64, cornerRadius = 16f, color = Color.rgb(0x20, 0x60, 0xC0)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 64)

        // The extreme corner pixel is opaque (plate-filled), not transparent.
        assertEquals(255, Color.alpha(tile.getPixel(0, 0)))
        assertColorClose(Color.rgb(0x20, 0x60, 0xC0), tile.getPixel(0, 0))
    }

    @Test
    fun paddedColoredIconBecomesSeamlessFullBleedTile() {
        val drawable = BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            squareOnTransparent(64, Rect(20, 20, 44, 44), Color.rgb(0xFF, 0x6A, 0x4D)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 64)

        // The transparent corners are now filled with the extracted coral plate
        // — the dead gray can no longer show through.
        assertEquals(255, Color.alpha(tile.getPixel(2, 2)))
        assertColorClose(Color.rgb(0xFF, 0x6A, 0x4D), tile.getPixel(2, 2))
        assertColorClose(Color.rgb(0xFF, 0x6A, 0x4D), tile.getPixel(32, 32))
    }

    @Test
    fun adaptiveForegroundThatAlreadyFillsIsNotShrunk() {
        // A foreground that already fills its bounds (a full red layer) must not
        // be downscaled to FOREGROUND_FRACTION — that would expose a blue
        // background border. The corner stays the foreground color.
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED))

        val tile = IconNormalizer.normalizeToTile(drawable, 64)

        assertColorClose(Color.RED, tile.getPixel(2, 2))
        assertColorClose(Color.RED, tile.getPixel(61, 61))
    }

    @Test
    fun adaptiveEmptyForegroundPlatesWithTheBackgroundColor() {
        // The visible art is a sparse colored background with a fully transparent
        // foreground. The plate must take the background's color, not the white
        // fallback of the empty foreground's analysis.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val green = Color.rgb(0x18, 0x80, 0x38)
        val drawable = AdaptiveIconDrawable(
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.40f, color = green)),
            ColorDrawable(Color.TRANSPARENT),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(green, tile.getPixel(2, 2))
    }

    @Test
    fun adaptivePartiallyTransparentBackgroundIsPlated() {
        // A circular background on transparency (~78% coverage) takes the
        // "background fills" path; its transparent corners must still be plated
        // with the background's color so a squircle/system clip (wider than the
        // circle) doesn't expose the surface underneath.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val blue = Color.rgb(0x20, 0x60, 0xC0)
        val drawable = AdaptiveIconDrawable(
            BitmapDrawable(resources, centeredCircle(100, fraction = 1.0f, color = blue)),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.30f, color = Color.WHITE)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        // The corner is outside the background circle; it must be opaque and the
        // background's color, not transparent.
        assertEquals(255, Color.alpha(tile.getPixel(2, 2)))
        assertColorClose(blue, tile.getPixel(2, 2))
    }

    @Test
    fun adaptiveTranslucentForegroundIsStillDrawn() {
        // A translucent logo (alpha below OPAQUE_ALPHA) has visible content but
        // zero opaque coverage; it must still be composited, not skipped.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(dark),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.60f, color = Color.argb(120, 0xFF, 0xFF, 0xFF))),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        // The center is the dark background lightened by the translucent white
        // logo; if the foreground were skipped it would stay the dark color.
        assertTrue(
            "translucent foreground should lighten the center",
            Color.red(tile.getPixel(50, 50)) > 0x40,
        )
    }

    @Test
    fun adaptiveForegroundIsEnlargedToFillTheTile() {
        // A small (30% of the layer) white logo on a dark background — the
        // GitHub/UniFi case. The foreground must be enlarged toward
        // FOREGROUND_FRACTION instead of floating tiny on the background.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.rgb(0x10, 0x14, 0x1C)),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.30f, color = Color.WHITE)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        // Center is the logo, and a point 35% out from center is now inside the
        // enlarged (~84% diameter) logo — the un-enlarged 30% logo wouldn't
        // reach there. The corner stays the dark background.
        assertColorClose(Color.WHITE, tile.getPixel(50, 50))
        assertColorClose(Color.WHITE, tile.getPixel(85, 50))
        assertColorClose(Color.rgb(0x10, 0x14, 0x1C), tile.getPixel(2, 2))
    }

    private fun solid(size: Int, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(color)
        return bitmap
    }

    private fun centeredCircle(size: Int, fraction: Float, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bitmap).drawCircle(size / 2f, size / 2f, size * fraction / 2f, paint)
        return bitmap
    }

    private fun squareOnTransparent(size: Int, rect: Rect, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { this.color = color }
        Canvas(bitmap).drawRect(rect, paint)
        return bitmap
    }

    private fun roundedSquareOnTransparent(size: Int, cornerRadius: Float, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bitmap).drawRoundRect(
            0f,
            0f,
            size.toFloat(),
            size.toFloat(),
            cornerRadius,
            cornerRadius,
            paint,
        )
        return bitmap
    }

    private fun assertColorClose(expected: Int, actual: Int) {
        val tolerance = 12
        assertTrue(
            "expected #${Integer.toHexString(expected)} but was #${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }
}
