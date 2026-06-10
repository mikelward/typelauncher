package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
    // A tile whose opaque coverage is at or above this is already treated as
    // full-bleed and returned untouched, so a multi-color adaptive background
    // never gets a mismatched dominant-color ring drawn around it. Padded /
    // logo-style icons fall below it and take the plate path.
    internal const val FULL_BLEED_COVERAGE = 0.9f

    // Fraction of the tile the normalized content fills on the plate path. The
    // remaining 8% margin is plate-colored; for a colored-shape-with-padding
    // icon the plate matches the shape so the margin is seamless, and it keeps
    // a genuinely sparse logo from butting against the tile edge.
    internal const val CONTENT_FRACTION = 0.92f

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
     * Rasterizes [drawable] to a `sizePx` square and, if it doesn't already
     * fill the tile, re-seats it on a dominant-color plate. The returned bitmap
     * is always `sizePx` × `sizePx`.
     */
    fun normalizeToTile(drawable: Drawable, sizePx: Int): Bitmap {
        val raw = rasterizeFullBleed(drawable, sizePx)
        val analysis = analyze(raw)
        if (analysis.coverage >= FULL_BLEED_COVERAGE) return raw
        val tile = drawOnPlate(raw, analysis, sizePx)
        raw.recycle()
        return tile
    }

    internal data class IconAnalysis(
        val coverage: Float,
        val bounds: Rect,
        val dominantColor: Int,
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
        val bounds = if (maxX < minX) Rect(0, 0, width, height) else Rect(minX, minY, maxX + 1, maxY + 1)
        val dominant = histogram.maxByOrNull { it.value[0] }?.value?.let { accumulator ->
            val count = accumulator[0]
            Color.rgb(accumulator[1] / count, accumulator[2] / count, accumulator[3] / count)
        } ?: Color.WHITE
        return IconAnalysis(coverage, bounds, dominant)
    }

    /**
     * Draws [drawable] edge-to-edge into a `sizePx` square. Adaptive icons are
     * composited layer-by-layer at full bounds rather than via
     * [AdaptiveIconDrawable.draw], whose direct-draw path oversizes the layers
     * by the safe-zone ring and clips it away — the same faithful full-bleed
     * technique `LauncherIconScreenshotTest` relies on.
     */
    private fun rasterizeFullBleed(drawable: Drawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (drawable is AdaptiveIconDrawable) {
            drawable.background?.apply {
                setBounds(0, 0, sizePx, sizePx)
                draw(canvas)
            }
            drawable.foreground?.apply {
                setBounds(0, 0, sizePx, sizePx)
                draw(canvas)
            }
        } else {
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
        }
        return bitmap
    }

    /**
     * Fills a fresh `sizePx` tile with [IconAnalysis.dominantColor] and draws
     * the visible content of [raw] (its [IconAnalysis.bounds] region) scaled to
     * [CONTENT_FRACTION] of the tile and centered, so a padded colored icon
     * becomes a seamless full-bleed tile and a sparse logo gets an intentional
     * backdrop.
     */
    private fun drawOnPlate(raw: Bitmap, analysis: IconAnalysis, sizePx: Int): Bitmap {
        val tile = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(analysis.dominantColor)

        val contentWidth = analysis.bounds.width().coerceAtLeast(1)
        val contentHeight = analysis.bounds.height().coerceAtLeast(1)
        val target = sizePx * CONTENT_FRACTION
        val scale = target / maxOf(contentWidth, contentHeight)
        val drawWidth = contentWidth * scale
        val drawHeight = contentHeight * scale
        val left = (sizePx - drawWidth) / 2f
        val top = (sizePx - drawHeight) / 2f
        val destination = RectF(left, top, left + drawWidth, top + drawHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        canvas.drawBitmap(raw, analysis.bounds, destination, paint)
        return tile
    }
}
