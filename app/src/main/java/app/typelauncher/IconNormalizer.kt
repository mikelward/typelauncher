package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/**
 * Turns any app [Drawable] into a full-bleed square tile bitmap so every icon
 * fills the same rounded-rectangle shape the launcher clips it to (`AppIcon`'s
 * `MaterialTheme.shapes.medium`), mirroring how AOSP Launcher3 normalizes
 * icons into a uniform grid.
 *
 * Without this, apps that ship a logo with transparent margins (or a small
 * colored shape floating on transparency) leave the launcher's plate showing
 * through as a dead gray square, while apps that ship a full-bleed adaptive
 * background fill the tile — so the grid looks inconsistent. Normalization
 * removes that split: adaptive icons are drawn full-bleed, and a padded or
 * sparse icon is re-seated on a plate in its own dominant color so it reads as
 * an intentional tile rather than a logo dumped on gray.
 *
 * Runs off the main thread (called from `AppIconLoader.performLoad` on a
 * background dispatcher) and its result is cached in the icon LRU, so the
 * per-icon analysis pass never touches the render hot path.
 */
internal object IconNormalizer {
    // A tile whose opaque coverage is at or above this is drawn edge-to-edge
    // over the plate (so a multi-color adaptive background gets no mismatched
    // dominant-color ring); below it, the content is scaled to CONTENT_FRACTION
    // and centered. Either way the content sits on a dominant-color plate, so
    // transparent corners (e.g. a rounded square rounded tighter than the
    // launcher's clip) and interior holes show the icon's own color rather than
    // exposing the gray surface plate.
    internal const val FULL_BLEED_COVERAGE = 0.9f

    // Fraction of the tile the normalized content fills on the plate path. The
    // remaining 8% margin is plate-colored; for a colored-shape-with-padding
    // icon the plate matches the shape so the margin is seamless, and it keeps
    // a genuinely sparse logo from butting against the tile edge.
    internal const val CONTENT_FRACTION = 0.92f

    // Adaptive icons are framed with the platform's safe-zone zoom — the 72/108
    // safe-zone viewport fills the tile — exactly as a stock launcher renders
    // them, so a normal logo keeps its intended size and placement.
    private val SAFE_ZONE_SCALE = 1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction()

    // An adaptive background covering at least this much of the tile is treated
    // as the fill; a sparser/transparent background falls back to a
    // dominant-color plate so the tile is still opaque under the logo.
    internal const val BACKGROUND_FILL_COVERAGE = 0.65f

    // Alpha at/above which a pixel counts as opaque for the coverage test and
    // the dominant-color histogram, and the lower threshold at which a pixel
    // counts as "visible" for the content bounds. The gap absorbs the
    // anti-aliased edge of a rounded or circular icon.
    private const val OPAQUE_ALPHA = 200
    private const val VISIBLE_ALPHA = 16

    // RGB quantization for the dominant-color histogram: drop the low 3 bits of
    // each channel so near-identical shades share a bucket and the mode isn't
    // split across a gradient's worth of almost-equal colors.
    private const val COLOR_BUCKET_SHIFT = 3

    /**
     * Rasterizes [drawable] to a full-bleed `sizePx` square tile, opaque to the
     * launcher's rounded clip — the tile carries no margin or ring of its own;
     * the icon's art (or its own background layer) fills it edge-to-edge. The
     * returned bitmap is always `sizePx` × `sizePx`.
     */
    fun normalizeToTile(drawable: Drawable, sizePx: Int): Bitmap {
        // Adaptive icons are composed from their background and foreground
        // layers with the platform framing; legacy icons are re-seated on a
        // dominant-color plate.
        if (drawable is AdaptiveIconDrawable) return normalizeAdaptive(drawable, sizePx)
        return normalizeFlat(drawable, sizePx)
    }

    /**
     * Composes a non-adaptive [drawable] onto a dominant-color plate: a
     * full-bleed icon is drawn edge-to-edge (no mismatched ring), a padded or
     * sparse icon is enlarged to [CONTENT_FRACTION] and centered, and the plate
     * fills any transparent corners or interior holes with the icon's own
     * color. The drawable is re-drawn under a scale matrix rather than
     * upscaling its bitmap, so a vector icon stays crisp when enlarged.
     */
    private fun normalizeFlat(drawable: Drawable, sizePx: Int): Bitmap {
        val raw = rasterizeFullBleed(drawable, sizePx)
        val analysis = analyze(raw)
        raw.recycle()
        val fillFraction = if (analysis.coverage >= FULL_BLEED_COVERAGE) 1f else CONTENT_FRACTION
        val content = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        canvas.drawColor(analysis.dominantColor)
        drawDrawableScaled(canvas, drawable, analysis.bounds, sizePx, fillFraction)
        return content
    }

    /**
     * Normalizes an adaptive icon exactly as the platform composes it: an
     * opaque dominant-color plate (a backstop for transparent/sparse
     * background layers), then the background and foreground layers drawn with
     * the safe-zone zoom about the tile center — including its crop, since a
     * full-bleed layer (the Play Store foreground) deliberately puts filler in
     * the outer bleed and relies on the zoom to crop it. No layer is ever
     * measured or resized per icon; what the designer authored is what renders,
     * pixel-for-pixel like a stock launcher.
     */
    private fun normalizeAdaptive(drawable: AdaptiveIconDrawable, sizePx: Int): Bitmap {
        val tile = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)

        val backgroundBitmap = drawable.background?.let { rasterizeLayer(it, sizePx) }
        val foregroundBitmap = drawable.foreground?.let { rasterizeLayer(it, sizePx) }
        val backgroundAnalysis = backgroundBitmap?.let { analyze(it) }
        val foregroundAnalysis = foregroundBitmap?.let { analyze(it) }
        backgroundBitmap?.recycle()
        foregroundBitmap?.recycle()

        // Always lay down an opaque plate before the background layer, so any
        // transparent area of the background — e.g. a circular background on
        // transparency whose corners fall inside a squircle/system clip — is
        // filled instead of exposing the surface plate. A fully opaque
        // background simply covers it. The plate takes the background's own
        // dominant color when the background is the fill, and the foreground's
        // (the logo's) color when the background is transparent/sparse.
        val backgroundFills = backgroundAnalysis != null &&
            backgroundAnalysis.coverage >= BACKGROUND_FILL_COVERAGE
        val plate = when {
            backgroundFills -> backgroundAnalysis!!.dominantColor
            // Only use the foreground's color when it actually has opaque art —
            // an empty/transparent foreground's dominant color is a meaningless
            // white fallback, so prefer the (sparse) background's color instead.
            foregroundAnalysis != null && foregroundAnalysis.coverage > 0f -> foregroundAnalysis.dominantColor
            backgroundAnalysis != null && backgroundAnalysis.coverage > 0f -> backgroundAnalysis.dominantColor
            else -> Color.WHITE
        }
        canvas.drawColor(plate)
        drawable.background?.let { drawLayerFramed(canvas, it, sizePx) }
        drawable.foreground?.let { drawLayerFramed(canvas, it, sizePx) }
        return tile
    }

    /**
     * Draws an adaptive [layer] with the platform safe-zone zoom applied about
     * the tile center — exactly the framing a stock launcher gives it. The
     * layer is re-drawn under a `Canvas` scale matrix rather than by upscaling
     * a rasterized bitmap, so a `VectorDrawable` layer stays crisp.
     */
    private fun drawLayerFramed(canvas: Canvas, layer: Drawable, sizePx: Int) {
        layer.setBounds(0, 0, sizePx, sizePx)
        val saved = canvas.save()
        val center = sizePx / 2f
        canvas.scale(SAFE_ZONE_SCALE, SAFE_ZONE_SCALE, center, center)
        layer.draw(canvas)
        canvas.restoreToCount(saved)
    }

    /** Draws an adaptive [layer] into a `sizePx` square at full bounds. */
    private fun rasterizeLayer(layer: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        layer.setBounds(0, 0, sizePx, sizePx)
        layer.draw(Canvas(bitmap))
        return bitmap
    }

    internal data class IconAnalysis(
        val coverage: Float,
        val bounds: Rect,
        val dominantColor: Int,
        // True when any pixel is at least faintly visible (alpha ≥ VISIBLE_ALPHA),
        // even if none is opaque — distinguishes a translucent logo (draw it)
        // from a fully transparent layer (skip it). `coverage` only counts
        // opaque pixels and so can't tell the two apart.
        val hasVisibleContent: Boolean,
    )

    /**
     * Single pass over [bitmap] computing opaque [IconAnalysis.coverage], the
     * tight visible [IconAnalysis.bounds], and the [IconAnalysis.dominantColor]
     * (modal quantized color of the opaque pixels, returned as the true average
     * of that bucket rather than the bucket center). Visible for unit tests.
     */
    internal fun analyze(bitmap: Bitmap): IconAnalysis {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var opaque = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        // bucket -> [count, redSum, greenSum, blueSum]
        val histogram = HashMap<Int, IntArray>()

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = pixels[index++]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha >= VISIBLE_ALPHA) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                if (alpha >= OPAQUE_ALPHA) {
                    opaque++
                    val red = (pixel ushr 16) and 0xFF
                    val green = (pixel ushr 8) and 0xFF
                    val blue = pixel and 0xFF
                    val bucket = ((red shr COLOR_BUCKET_SHIFT) shl 10) or
                        ((green shr COLOR_BUCKET_SHIFT) shl 5) or
                        (blue shr COLOR_BUCKET_SHIFT)
                    val accumulator = histogram.getOrPut(bucket) { IntArray(4) }
                    accumulator[0]++
                    accumulator[1] += red
                    accumulator[2] += green
                    accumulator[3] += blue
                }
            }
        }

        val total = (width * height).coerceAtLeast(1)
        val coverage = opaque.toFloat() / total
        val hasVisibleContent = maxX >= minX
        val bounds = if (!hasVisibleContent) Rect(0, 0, width, height) else Rect(minX, minY, maxX + 1, maxY + 1)
        val dominant = histogram.maxByOrNull { it.value[0] }?.value?.let { accumulator ->
            val count = accumulator[0]
            Color.rgb(accumulator[1] / count, accumulator[2] / count, accumulator[3] / count)
        } ?: Color.WHITE
        return IconAnalysis(coverage, bounds, dominant, hasVisibleContent)
    }

    /**
     * Draws a non-adaptive [drawable] into a `sizePx` square at full bounds.
     * (Adaptive icons take the [normalizeAdaptive] path instead.)
     */
    private fun rasterizeFullBleed(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /**
     * Fills a fresh `sizePx` tile with [IconAnalysis.dominantColor] and draws
     * the visible content of [raw] (its [IconAnalysis.bounds] region) scaled so
     * its larger side spans [fillFraction] of the tile and centered, so a
     * full-bleed icon (`fillFraction == 1`) covers the tile while a padded
     * colored icon or sparse logo sits on an intentional dominant-color backdrop.
     */
    /**
     * Draws [drawable] so the visible content currently at [contentBounds] (in a
     * full-bounds `sizePx` rasterization) is scaled to span [fillFraction] of the
     * tile and centered. The drawable is rendered under a `Canvas` scale matrix
     * rather than by upscaling a pre-rasterized bitmap, so a vector source
     * (`VectorDrawable`, the common adaptive foreground) re-rasterizes sharply at
     * the enlarged size; a raster source falls back to filtered sampling.
     */
    private fun drawDrawableScaled(
        canvas: Canvas,
        drawable: Drawable,
        contentBounds: Rect,
        sizePx: Int,
        fillFraction: Float,
    ) {
        val contentMax = maxOf(contentBounds.width(), contentBounds.height()).coerceAtLeast(1)
        // Enlarge undersized content up to the target, but never shrink content
        // that already fills its bounds — shrinking would expose a background
        // border around foreground art that was meant to be full-size.
        val scale = ((sizePx * fillFraction) / contentMax).coerceAtLeast(1f)
        val centerX = contentBounds.exactCenterX()
        val centerY = contentBounds.exactCenterY()
        drawable.setBounds(0, 0, sizePx, sizePx)
        val saved = canvas.save()
        canvas.translate(sizePx / 2f, sizePx / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-centerX, -centerY)
        drawable.draw(canvas)
        canvas.restoreToCount(saved)
    }
}
