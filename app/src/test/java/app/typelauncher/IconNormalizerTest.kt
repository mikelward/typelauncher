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
    fun darkPlateDiscLogoFillsTheTile() {
        // A solid white disc (30% of the layer, off-center at x=38) on a
        // near-black background — the GitHub case. The dark plate vanishes into
        // the launcher's dark surface, so the disc *is* the visible icon;
        // anything short of full size reads smaller than every bright neighbor
        // whose whole circle is visible. The disc is grown to fill the tile
        // (radius ~50 of 100) and moved to the tile center so the growth can't
        // spill past an edge — both 44px-out points are covered.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.rgb(0x10, 0x14, 0x1C)),
            BitmapDrawable(resources, circleAt(100, centerX = 38f, centerY = 50f, radius = 15f, color = Color.WHITE)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(Color.WHITE, tile.getPixel(50, 50))
        assertColorClose(Color.WHITE, tile.getPixel(6, 50))
        assertColorClose(Color.WHITE, tile.getPixel(94, 50))
        assertColorClose(Color.rgb(0x10, 0x14, 0x1C), tile.getPixel(2, 2))
    }

    @Test
    fun darkPlateRingLogoIsEnlargedOnlyToTheDarkPlateMinimum() {
        // A ring outline (outer radius 15, stroke 3 — the VW case) on the same
        // near-black background is *not* a filled disc (it fills only ~36% of
        // its hull), so it must not be grown to fill the tile — its open shape
        // would slam the clip edge. It gets DARK_PLATE_FOREGROUND_FRACTION
        // (~78%, outer radius ~39, inner ~31): 35px out is on the ring, 25px
        // out is inside the hole, and 45px out must be background again (a
        // disc-fill would paint it white).
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val ring = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(ring).drawCircle(
            50f,
            50f,
            13.5f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f
            },
        )
        val drawable = AdaptiveIconDrawable(ColorDrawable(dark), BitmapDrawable(resources, ring))

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(Color.WHITE, tile.getPixel(85, 50))
        assertColorClose(dark, tile.getPixel(75, 50))
        assertColorClose(dark, tile.getPixel(95, 50))
    }

    @Test
    fun marginlessForegroundIsNotCropped() {
        // Chrome and the Play Store author their foreground with *no* safe-zone
        // margin — the art spans the full layer. The blanket 1.5x zoom would
        // blow it past the tile edge and crop it (a small center in a
        // stretched, flat-cut ball); the fit cap draws it at its authored size
        // instead, so the blue frame at the layer's edge stays visible on the
        // tile. Under the old behavior the frame was cropped away and 3px-in
        // read as the red interior.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val red = Color.rgb(0xC0, 0x28, 0x28)
        val blue = Color.rgb(0x20, 0x40, 0xC0)
        val framed = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.drawColor(blue)
            canvas.drawRect(Rect(8, 8, 92, 92), Paint().apply { color = red })
        }
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, framed),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(red, tile.getPixel(50, 50))
        assertColorClose(blue, tile.getPixel(3, 50))
        assertColorClose(blue, tile.getPixel(97, 50))
    }

    @Test
    fun adaptiveForegroundOnABrightPlateIsEnlargedOnlyToTheMinimum() {
        // A tiny (30% of the layer) dark logo on a white background reads
        // against a visible bright tile, so it only gets
        // MIN_FOREGROUND_FRACTION (~60%, radius 30 of 100) — not the dark-plate
        // compensation. 24px out is inside that size; 36px out must still be
        // the white plate.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.30f, color = dark)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(50, 50))
        assertColorClose(dark, tile.getPixel(74, 50))
        assertColorClose(Color.WHITE, tile.getPixel(86, 50))
    }

    @Test
    fun adaptiveForegroundShadowDoesNotInflateTheSizing() {
        // A crisp 30% logo wrapped in a soft 60% shadow halo (alpha 60, under
        // SIZING_ALPHA). Sizing must measure the crisp art only, so the logo is
        // enlarged to the 60% minimum (radius 30) — the regression was the halo
        // padding the measured box so the bump under-fired and the crisp logo
        // rendered at only ~45% (radius 22.5, leaving 24px out un-covered).
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val foreground = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(foreground)
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0x10, 0x14, 0x1C) }
        canvas.drawCircle(50f, 50f, 30f, haloPaint)
        val crispPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dark }
        canvas.drawCircle(50f, 50f, 15f, crispPaint)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, foreground),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(50, 50))
        assertColorClose(dark, tile.getPixel(74, 50))
    }

    @Test
    fun adaptiveSquareLogoGetsASmallerBumpThanARoundOne() {
        // A solid square reads larger than a circle of the same bounding box, so
        // the perceived-size correction enlarges it less: a 30% square lands at
        // ~53% per side (half-side ~26.6 of 100) instead of the 60% a circle
        // gets. 24px out is inside the square; 29px out must be plate again
        // (a circle-sized bump would still cover it at radius 30).
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, squareOnTransparent(100, Rect(35, 35, 65, 65), dark)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(74, 50))
        assertColorClose(Color.WHITE, tile.getPixel(79, 50))
    }

    @Test
    fun adaptiveEnlargementKeepsAnOffCenterLogoInPlace() {
        // A 20% logo centered at (38, 50) of the layer needs a 3x bump (to the
        // 60% minimum). The extra zoom beyond the safe-zone framing is anchored
        // on the logo's own center — after framing that center is at x=32, and
        // it must stay there (radius 30 covers x=58). A tile-center-anchored
        // zoom would shove the logo's center out to x=14, leaving both asserted
        // points on the plate.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, circleAt(100, centerX = 38f, centerY = 50f, radius = 10f, color = dark)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(50, 50))
        assertColorClose(dark, tile.getPixel(58, 50))
    }

    @Test
    fun adaptiveFullSafeZoneForegroundKeepsThePlatformFraming() {
        // A logo authored to fill the entire safe-zone viewport (2/3 of the
        // layer — a properly-margined Chrome ball) gets exactly the platform
        // framing and fills the tile edge-to-edge; it is never enlarged further
        // (the fit cap sits exactly at the safe-zone zoom for this size).
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.667f, color = dark)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(50, 50))
        assertColorClose(dark, tile.getPixel(50, 6))
        // The circle's edge meets the tile edge at the axes; the corner stays
        // outside it and shows the white plate.
        assertColorClose(Color.WHITE, tile.getPixel(2, 2))
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

    private fun circleAt(size: Int, centerX: Float, centerY: Float, radius: Float, color: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bitmap).drawCircle(centerX, centerY, radius, paint)
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
