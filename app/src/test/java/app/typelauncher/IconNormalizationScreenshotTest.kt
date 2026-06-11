package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the normalized full-bleed tiles for the representative icon shapes —
 * a full-bleed adaptive icon, a colored shape with transparent padding, a
 * sparse logo, and a plain colored icon — clipped to
 * the launcher's tile shape, so the PR `roborazzi-screenshots` artifact shows
 * the new treatment. Mirrors `LauncherIconScreenshotTest`'s raw-bitmap capture
 * (no Compose/async-load involved) for a stable golden.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconNormalizationScreenshotTest {

    @Test
    fun normalizedTiles() {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return

        val tile = 144
        val gap = 24
        val cases = normalizationCases()

        val stripWidth = tile * cases.size + gap * (cases.size + 1)
        val stripHeight = tile + gap * 2
        val strip = Bitmap.createBitmap(stripWidth, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(Color.rgb(0x9E, 0x9E, 0x9E))

        var left = gap
        for (drawable in cases) {
            val normalized = IconNormalizer.normalizeToTile(drawable, tile)
            drawCircleTile(canvas, normalized, left.toFloat(), gap.toFloat(), tile.toFloat())
            left += tile + gap
        }

        strip.captureRoboImage(filePath = "src/test/snapshots/images/compose_icon_normalization_robolectric.png")
    }

    @Test
    fun themedTiles() {
        val isRecord = System.getProperty("roborazzi.test.record") == "true"
        val isVerify = System.getProperty("roborazzi.test.verify") == "true"
        if (!isRecord && !isVerify) return

        // The same representative inputs forced monochrome under the Monochrome
        // theme: none of them ships an authored monochrome layer, so every glyph
        // is synthesized from the icon's own art (its foreground/silhouette
        // tinted the glyph color on the accent plate). The grid should read as a
        // uniform two-tone set — including the deliberately rough full-bleed and
        // plain-color cases, which collapse to a solid glyph-color shape.
        val tile = 144
        val gap = 24
        val cases = normalizationCases()
        val themedColors = IconNormalizer.ThemedIconColors(
            plate = Color.rgb(0xE8, 0xEA, 0xED),
            glyph = Color.rgb(0x3C, 0x40, 0x43),
        )

        val stripWidth = tile * cases.size + gap * (cases.size + 1)
        val stripHeight = tile + gap * 2
        val strip = Bitmap.createBitmap(stripWidth, stripHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        canvas.drawColor(Color.rgb(0x9E, 0x9E, 0x9E))

        var left = gap
        for (drawable in cases) {
            val normalized = IconNormalizer.normalizeToTile(drawable, tile, themedColors = themedColors)
            drawCircleTile(canvas, normalized, left.toFloat(), gap.toFloat(), tile.toFloat())
            left += tile + gap
        }

        strip.captureRoboImage(filePath = "src/test/snapshots/images/compose_icon_normalization_themed_robolectric.png")
    }

    private fun normalizationCases(): List<Drawable> = listOf(
        // Full-bleed adaptive icon: blue background, white circle foreground.
        AdaptiveIconDrawable(ColorDrawable(Color.rgb(0x1A, 0x73, 0xE8)), whiteCircleForeground()),
        // Adaptive icon with a small foreground logo on a dark background —
        // the logo is enlarged to fill rather than floating tiny (the
        // GitHub / UniFi case).
        AdaptiveIconDrawable(ColorDrawable(Color.rgb(0x10, 0x14, 0x1C)), smallLogoForeground()),
        // Colored shape with transparent padding — the case that showed gray.
        paddedDrawable(Rect(36, 36, 108, 108), Color.rgb(0xFF, 0x6A, 0x4D)),
        // Sparse dark logo on transparency (documented weak case).
        paddedDrawable(Rect(54, 54, 90, 90), Color.rgb(0x22, 0x26, 0x2B)),
        // Plain colored icon with no transparency.
        ColorDrawable(Color.rgb(0x18, 0x80, 0x38)),
        // Life preserver: a shape defined by COLOR on an opaque field (a red ring
        // on white). The engraving must recover the ring and its hole instead of
        // a solid disc.
        colorRingOnWhite(Color.rgb(0xE5, 0x3A, 0x2B)),
        // Monzo-style logo: a shape defined by TRANSPARENCY (a coral ring on a
        // transparent field). The silhouette already carries it; the engraving
        // must not regress it.
        alphaRingOnTransparent(Color.rgb(0xFF, 0x6A, 0x4D)),
        // Light logo on a bright background: a white circle on orange. The mark
        // is brighter than the field, so the engraving must ink the logo (not
        // invert it into a plate-colored hole).
        AdaptiveIconDrawable(ColorDrawable(Color.rgb(0xFF, 0xA5, 0x00)), smallLogoForeground()),
        // White logo on a gradient background (the Instagram case): the gradient
        // spreads across many brightness bins while the logo piles into one, so
        // the field must come from the densest window of bins or the logo would
        // invert.
        whiteLogoOnGradient(),
    )

    private fun drawCircleTile(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, size: Float) {
        // Matches AppIcon's circular clip.
        val path = Path().apply {
            addCircle(left + size / 2f, top + size / 2f, size / 2f, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(bitmap, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
    }

    private fun whiteCircleForeground(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }

    // A small white logo (30% of the layer) centered on transparency, the kind
    // of adaptive foreground that used to render tiny inside its background.
    private fun smallLogoForeground(): Drawable {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        Canvas(bitmap).drawCircle(72f, 72f, 144 * 0.30f / 2f, paint)
        return BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            bitmap,
        )
    }

    // A full-bleed white tile with a colored ring — shape defined by color, not
    // alpha (the life-preserver case that used to collapse to a solid disc).
    private fun colorRingOnWhite(color: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawCircle(72f, 72f, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            drawCircle(72f, 72f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        }
        return BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            bitmap,
        )
    }

    // A colored ring on transparency — shape defined by alpha (a Monzo-style
    // logo), the case the silhouette already handles well.
    private fun alphaRingOnTransparent(color: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawCircle(72f, 72f, 58f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
            drawCircle(
                72f,
                72f,
                26f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR) },
            )
        }
        return BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            bitmap,
        )
    }

    // A white logo on a diagonal color gradient — the brightness is spread
    // across many histogram bins, so the field must be the densest window of
    // bins (the background) rather than the single bin the solid logo lands in.
    private fun whiteLogoOnGradient(): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val shader = android.graphics.LinearGradient(
            0f, 0f, 144f, 144f,
            Color.rgb(0x83, 0x3A, 0xB4), Color.rgb(0xFC, 0xAF, 0x45),
            android.graphics.Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, 144f, 144f, Paint().apply { this.shader = shader })
        canvas.drawCircle(72f, 72f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            bitmap,
        )
    }

    private fun paddedDrawable(rect: Rect, color: Int): BitmapDrawable {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        Canvas(bitmap).drawRoundRect(RectF(rect), 12f, 12f, paint)
        return BitmapDrawable(
            ApplicationProvider.getApplicationContext<android.content.Context>().resources,
            bitmap,
        )
    }
}
