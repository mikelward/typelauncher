package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
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
    fun adaptiveTransparentBackgroundPlatesWhiteNotTheLogosColor() {
        // The Google Cloud case: an empty background layer with a multicolor logo
        // floating on it. The plate must be white — taking the foreground's
        // dominant color painted the mark's largest hue behind the whole logo and
        // turned the tile solid blue.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.TRANSPARENT),
            BitmapDrawable(resources, fourColorRing(100)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        // The corner is opaque white, not the mark's dominant blue.
        assertEquals(255, Color.alpha(tile.getPixel(2, 2)))
        assertColorClose(Color.WHITE, tile.getPixel(2, 2))
        // The ring's hole is plate too — the backstop fills interior holes, and
        // this is where a foreground-colored plate was most obviously wrong.
        assertColorClose(Color.WHITE, tile.getPixel(50, 50))
        // The mark itself is untouched: its arcs still carry their own colors.
        // The ring spans radius 16.5..36 about the center after the safe-zone
        // zoom, so each sample sits mid-band on its own arc.
        assertColorClose(RING_BLUE, tile.getPixel(76, 50))
        assertColorClose(RING_YELLOW, tile.getPixel(24, 50))
    }

    @Test
    fun flatSparseMarkOnTransparencyPlatesWhiteNotItsOwnColor() {
        // The real Google Cloud icon: a *non-adaptive* BitmapDrawable whose
        // four-color mark spans almost the whole tile but leaves most of it
        // transparent. Its own diagnostic line on a device reads
        //   flat BitmapDrawable cov=0.38 boundsFraction=0.98 dom=#FF4284F3
        // and this fixture reproduces those numbers, so the plate used to flood
        // the tile with the mark's blue. Because the bounds already span the tile
        // the art is drawn at its authored size, leaving the plate covering 62% of
        // the tile — a backdrop, so it must be white.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val drawable = BitmapDrawable(resources, fourColorRing(100, arcRadius = 0.4176f, stroke = 0.1448f))

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertEquals(255, Color.alpha(tile.getPixel(2, 2)))
        assertColorClose(Color.WHITE, tile.getPixel(2, 2))
        // The mark's own gaps are plate as well — the ring's hole is where the
        // blue flood was most visible.
        assertColorClose(Color.WHITE, tile.getPixel(50, 50))
        // The arcs keep their colors: the band spans radius 34.5..49 and is drawn
        // unscaled, so a sample 42 out sits mid-band.
        assertColorClose(RING_BLUE, tile.getPixel(92, 50))
        assertColorClose(RING_YELLOW, tile.getPixel(8, 50))
    }

    @Test
    fun flatPaddedShapeKeepsItsOwnColorAsPlate() {
        // The counterpart that must NOT flip: a solid shape with transparent
        // padding is enlarged to CONTENT_FRACTION, so the plate is left filling
        // only the ~15% the shape's rounded corners leave — a backstop, where the
        // shape's own color is what makes the tile read as one deliberate shape
        // rather than a logo floating on white.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val coral = Color.rgb(0xFF, 0x6A, 0x4D)
        val drawable = BitmapDrawable(resources, squareOnTransparent(100, Rect(30, 30, 70, 70), coral))

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(coral, tile.getPixel(2, 2))
        assertColorClose(coral, tile.getPixel(50, 50))
    }

    @Test
    fun flatPaddedShapeWithFaintShadowKeepsItsOwnColorAsPlate() {
        // The old-style legacy icon: a colored body over a soft drop shadow that
        // reaches the canvas edges. The shadow inflates the *bounds* to the whole
        // tile, so no enlargement happens, while contributing nothing to *opaque*
        // coverage — a plate estimate built on opaque coverage therefore reported a
        // mostly-plate tile and whitened a shape that should stay a seamless colored
        // one. Weighted by alpha the shadow counts for 40/255 of each pixel it
        // covers, putting the plate at 0.43 of the tile: still a backstop.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val teal = Color.rgb(0x00, 0x89, 0x7B)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            // A faint shadow over the whole canvas (alpha above VISIBLE_ALPHA but
            // far below OPAQUE_ALPHA), then an opaque body over 70% of it.
            drawColor(Color.argb(40, 0, 0, 0))
            drawRect(15f, 15f, 85f, 85f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal })
        }
        val drawable = BitmapDrawable(resources, bitmap)

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        // Inside the body the tile is the icon's own color.
        assertColorClose(teal, tile.getPixel(50, 50))
        // Outside it the plate is that same teal — but the icon's shadow is drawn
        // *over* the plate, so the corner reads as teal multiplied by the shadow's
        // alpha: 1 - 40/255 = 0.843, taking 0x89 -> 0x73 and 0x7B -> 0x67. The
        // point is that the corner is still the icon's color at all; the bug being
        // guarded against made this a white plate (green channel 0xFF, not 0x73).
        assertColorClose(Color.rgb(0x00, 0x73, 0x67), tile.getPixel(2, 2))
    }

    @Test
    fun flatSparseMarkUnderAFaintMatteStillPlatesWhite() {
        // The counterpart to the shadowed *body* above, and the case a yes/no
        // visibility test gets wrong: the same sparse mark, but with a faint matte
        // over the whole bitmap. Counting every faintly-visible pixel as fully
        // covered would report no exposed plate and flood the tile with the mark's
        // blue — the exact bug this change exists to remove. Weighted by alpha the
        // matte adds only 0.157 per uncovered pixel, leaving the plate at 0.52 of
        // the tile, so the mark still gets a neutral backdrop.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val ring = fourColorRing(100, arcRadius = 0.4176f, stroke = 0.1448f)
        val matted = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(matted).apply {
            drawColor(Color.argb(40, 0, 0, 0))
            drawBitmap(ring, 0f, 0f, null)
        }

        val tile = IconNormalizer.normalizeToTile(BitmapDrawable(resources, matted), 100)

        // White plate seen through the icon's own matte: 255 * (1 - 40/255) = 215.
        // Blue here would mean the mark is sitting on its own dominant color again.
        assertColorClose(Color.rgb(215, 215, 215), tile.getPixel(50, 50))
        assertColorClose(Color.rgb(215, 215, 215), tile.getPixel(2, 2))
    }

    @Test
    fun alphaCoverageWeightsTranslucentPixelsRatherThanCountingThemWhole() {
        // A faint full-canvas shadow is invisible to `analyze`'s opaque coverage and
        // would be the whole image under a yes/no visibility test. Alpha weighting
        // puts it between: the shadow contributes 40/255 per pixel it covers.
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.argb(40, 0, 0, 0))
            drawRect(30f, 30f, 70f, 70f, Paint().apply { color = Color.RED })
        }

        assertEquals(0.16f, IconNormalizer.analyze(bitmap).coverage, 0.02f)
        // 0.16 opaque body + 0.84 of the canvas at 40/255.
        assertEquals(0.16f + 0.84f * (40f / 255f), IconNormalizer.alphaCoverage(bitmap), 0.02f)
    }

    @Test
    fun flatCompactMarkUnderASubVisibleMatteStillPlatesWhite() {
        // A compact sparse mark over a matte fainter than VISIBLE_ALPHA, so the matte
        // is excluded from the content bounds but is still most of the source
        // bitmap's alpha. Estimating the plate's share from the source raster and
        // scaling it by the enlargement factor amplifies matte alpha that the tile
        // simply clips away: it reported 0.24 plated (dominant blue) where the tile
        // actually produced is 0.67 plated. Measuring the composed tile is what makes
        // this exact, so the mark keeps a neutral backdrop.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val ring = fourColorRing(100, arcRadius = 0.14f, stroke = 0.04f)
        val matted = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(matted).apply {
            drawColor(Color.argb(15, 0, 0, 0))
            drawBitmap(ring, 0f, 0f, null)
        }

        val tile = IconNormalizer.normalizeToTile(BitmapDrawable(resources, matted), 100)

        // White plate seen through the matte: 255 * (1 - 15/255) = 240. Blue here
        // would mean the mark is back on its own dominant color.
        assertColorClose(Color.rgb(240, 240, 240), tile.getPixel(50, 50))
        assertColorClose(Color.rgb(240, 240, 240), tile.getPixel(2, 2))
        // The mark itself is enlarged to span radius 34.5..46, so 40 out is mid-band.
        assertColorClose(RING_BLUE, tile.getPixel(90, 50))
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
    fun adaptiveSmallForegroundKeepsItsAuthoredSize() {
        // A 30% logo renders at exactly the platform framing (30% x 1.5 =
        // radius 22.5 of 100) — never measured, never enlarged. The point 20px
        // out is inside the authored size; 26px out must be the plate. (The
        // retired sizing engine would have grown this logo to a "minimum" and
        // covered both points — that whole idea is deliberately gone.)
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dark = Color.rgb(0x10, 0x14, 0x1C)
        val drawable = AdaptiveIconDrawable(
            ColorDrawable(Color.WHITE),
            BitmapDrawable(resources, centeredCircle(100, fraction = 0.30f, color = dark)),
        )

        val tile = IconNormalizer.normalizeToTile(drawable, 100)

        assertColorClose(dark, tile.getPixel(50, 50))
        assertColorClose(dark, tile.getPixel(70, 50))
        assertColorClose(Color.WHITE, tile.getPixel(76, 50))
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

    @Test
    fun themedColorsRenderMonochromeGlyphOnPlate() {
        // An adaptive icon with a monochrome layer (a white centered circle —
        // BitmapDrawable, which honors setTint) takes the themed path: the
        // glyph is tinted the glyph color and drawn with the platform framing
        // (0.5 fraction x 1.5 safe-zone zoom = radius 0.375 of the tile), and
        // everything outside it is the plate fill — the bg/fg layers are not
        // drawn at all.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val mono = BitmapDrawable(resources, centeredCircle(100, fraction = 0.5f, color = Color.WHITE))
        val drawable = object : AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED)) {
            override fun getMonochrome(): Drawable = mono
        }
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
    }

    @Test
    fun themedColorsSynthesizeGlyphWhenNoMonochromeLayer() {
        // No authored monochrome layer: the themed request must synthesize a
        // glyph from the icon's own art instead of keeping the full-color tile.
        // The composed art is a white foreground circle over a darker blue
        // background; the bright circle engraves to the glyph and the blue field
        // maps to the plate, so the center is the glyph color and the corner is
        // the plate, never the original red/blue layers.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val foreground = BitmapDrawable(resources, centeredCircle(100, fraction = 0.5f, color = Color.WHITE))
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), foreground)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
    }

    @Test
    fun themedColorsEngraveLightLogoOnBrightBackground() {
        // A white logo on a bright (orange) background: the logo sits above the
        // icon's mean brightness, so a single mark direction from the mean would
        // treat the logo as field and ink the darker background instead —
        // inverting the mark into a plate-colored hole. Measuring deviation from
        // the dominant (orange) brightness must instead make the white logo the
        // glyph and the orange background the plate.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val foreground = BitmapDrawable(resources, centeredCircle(100, fraction = 0.4f, color = Color.WHITE))
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.rgb(0xFF, 0xA5, 0x00)), foreground)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
    }

    @Test
    fun themedColorsEngraveLightLogoOnGradientBackground() {
        // A white logo on a gradient background (the Instagram case): the
        // gradient spreads its brightness across many histogram bins while the
        // solid white logo piles into one, so a single-bin field choice would
        // pick the logo as the field and invert it. Choosing the densest *window*
        // of bins keeps the gradient as the field, so the logo is inked.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        for (y in 0 until 100) {
            val v = 30 + y * 90 / 100 // L ramps ~0.12..0.47, spread across many bins
            canvas.drawRect(0f, y.toFloat(), 100f, (y + 1).toFloat(), Paint().apply { color = Color.rgb(v, v, v) })
        }
        canvas.drawCircle(50f, 50f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        val drawable = BitmapDrawable(resources, bitmap)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(50, 50))
    }

    @Test
    fun themedColorsEngraveHueOnlyMarkIntoGlyph() {
        // A mark set apart from its background only by HUE at the same brightness
        // (a red ring on an equal-luminance teal field). A brightness-only metric
        // sees no contrast and flattens the whole opaque tile to a solid disc;
        // measuring color distance keeps the ring (and its hole).
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val teal = Color.rgb(40, 131, 160) // L ~0.446
        val red = Color.rgb(200, 90, 90) // L ~0.445 — same brightness, different hue
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(teal)
            drawCircle(50f, 50f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = red })
            drawCircle(50f, 50f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = teal })
        }
        val drawable = BitmapDrawable(resources, bitmap)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        // The red ring band is the glyph; the teal hole and the teal corner are
        // the plate (a brightness-only metric would make the whole tile glyph).
        assertColorClose(glyph, tile.getPixel(50, 21))
        assertColorClose(plate, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
    }

    @Test
    fun themedColorsEngraveDeadzoneKeepsMulticolorBackgroundOnPlate() {
        // A multicolor background (two close blues) behind a strong white logo.
        // Each background hue sits a little off the single modal field color, so
        // without the ink deadzone it would be faintly washed with glyph color;
        // the deadzone snaps those low-distance pixels back to the plate while
        // the logo (the peak-ink mark) still inks.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val blue = Color.rgb(60, 90, 200)
        val offBlue = Color.rgb(60, 90, 170) // ~0.07 normalized distance from blue
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(blue)
            drawRect(50f, 0f, 100f, 100f, Paint().apply { color = offBlue })
            drawCircle(50f, 50f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val drawable = BitmapDrawable(resources, bitmap)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        // Both background hues stay on the plate (no glyph-color sheen); the
        // white logo is the glyph.
        assertColorClose(plate, tile.getPixel(20, 10))
        assertColorClose(plate, tile.getPixel(80, 10))
        assertColorClose(glyph, tile.getPixel(50, 50))
    }

    @Test
    fun themedColorsEngraveColorDefinedShapeIntoGlyph() {
        // The life-preserver case: a shape defined by COLOR (a red ring on an
        // opaque white field), not by transparency. The alpha silhouette would
        // flatten the whole opaque tile to a solid glyph disc; the brightness
        // engraving instead maps the darker red ring to the glyph and the
        // brighter white field — including the ring's hole — to the plate, so the
        // ring and its hole survive.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawCircle(50f, 50f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
            drawCircle(50f, 50f, 18f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        }
        val drawable = BitmapDrawable(resources, bitmap)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        // A point on the red ring band (29px above center, between r=18 and r=40)
        // is the glyph; the white hole and the white corner are the plate.
        assertColorClose(glyph, tile.getPixel(50, 21))
        assertColorClose(plate, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
    }

    @Test
    fun themedColorsEngraveTranslucentArtStaysVisible() {
        // An icon whose art is visible but entirely translucent (every pixel
        // below the opaque threshold) has no fully-opaque pixels to read
        // brightness from. It must still render from its alpha silhouette rather
        // than vanishing to a bare plate.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.argb(128, 200, 200, 200))
        val drawable = BitmapDrawable(resources, bitmap)
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            64,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        // ~50% glyph over the plate — distinctly not the bare plate.
        assertColorClose(Color.rgb(43, 60, 77), tile.getPixel(32, 32))
    }

    @Test
    fun themedColorsEngraveDoesNotVanishForUniformColorIcon() {
        // An adaptive icon whose composed art is a single flat color (here a
        // transparent foreground over a solid red background) has no brightness
        // variation to engrave, so the glyph falls back to the alpha silhouette
        // rather than washing out to a blank plate — the icon stays visible.
        val drawable = AdaptiveIconDrawable(ColorDrawable(Color.RED), ColorDrawable(Color.TRANSPARENT))
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            64,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(32, 32))
    }

    @Test
    fun themedColorsSynthesizeGlyphForLegacyIcon() {
        // A legacy (non-adaptive) icon has no monochrome layer either; under the
        // themed request a glyph is engraved from its art. A uniform white square
        // padded on transparency has no brightness variation, so it falls back to
        // its silhouette: the square becomes the glyph and the transparent corner
        // fills to the plate.
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val drawable = BitmapDrawable(resources, squareOnTransparent(100, Rect(30, 30, 70, 70), Color.WHITE))
        val plate = 0xFF112233.toInt()
        val glyph = 0xFF445566.toInt()

        val tile = IconNormalizer.normalizeToTile(
            drawable,
            100,
            themedColors = IconNormalizer.ThemedIconColors(plate = plate, glyph = glyph),
        )

        assertColorClose(glyph, tile.getPixel(50, 50))
        assertColorClose(plate, tile.getPixel(2, 2))
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

    /**
     * A four-color ring on transparency, blue-dominant: blue sweeps the right
     * half and the other three share the left. Stands in for a real multicolor
     * logo shipped with an empty adaptive background layer — the shape is a ring
     * so the plate has an interior hole to fill as well as the corners, and the
     * blue majority is what a foreground-derived plate used to pick up.
     *
     * Geometry (in layer coordinates, before the safe-zone zoom): centered, with
     * the stroke centered on `arcRadius * size` with width `stroke * size`.
     */
    private fun fourColorRing(size: Int, arcRadius: Float = 0.175f, stroke: Float = 0.13f): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val center = size / 2f
        val radius = size * arcRadius
        val oval = RectF(center - radius, center - radius, center + radius, center + radius)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = size * stroke
        }
        val canvas = Canvas(bitmap)
        // Angles are clockwise from 3 o'clock, so blue covers 12→6 (the right
        // half) and the remaining three arcs fill 6→12 counter-clockwise.
        canvas.drawArc(oval, -90f, 180f, false, paint.apply { color = RING_BLUE })
        canvas.drawArc(oval, 90f, 60f, false, paint.apply { color = RING_RED })
        canvas.drawArc(oval, 150f, 60f, false, paint.apply { color = RING_YELLOW })
        canvas.drawArc(oval, 210f, 60f, false, paint.apply { color = RING_GREEN })
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

    private companion object {
        // The four arcs of [fourColorRing], shared with its call sites' assertions.
        val RING_BLUE = Color.rgb(0x42, 0x85, 0xF4)
        val RING_RED = Color.rgb(0xEA, 0x43, 0x35)
        val RING_YELLOW = Color.rgb(0xFB, 0xBC, 0x05)
        val RING_GREEN = Color.rgb(0x34, 0xA8, 0x53)
    }
}
