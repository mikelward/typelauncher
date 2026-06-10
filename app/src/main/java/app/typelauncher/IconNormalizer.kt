package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.sqrt

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

    // A foreground (logo) is enlarged *beyond* the safe-zone zoom only when it is
    // so small that even after that zoom its perceived size fills less than this
    // fraction of the tile — the "tiny logo floating on a big background" case
    // (Surfshark, UniFi). A normal logo keeps the platform framing untouched, and
    // the enlargement scales about the logo's own center so it is never shifted.
    internal const val MIN_FOREGROUND_FRACTION = 0.60f

    // Minimum perceived foreground fraction when the tile's plate blends into the
    // launcher's dark surface (Monzo, VW). With the plate invisible against the
    // app-list card, the logo is the only art the eye registers, so a
    // keyline-sized logo reads ~30% smaller than a bright-plated neighbor; the
    // larger minimum compensates so dark tiles read the same size as bright ones.
    internal const val DARK_PLATE_FOREGROUND_FRACTION = 0.78f

    // A dark-plated logo that is itself a *filled disc* (GitHub's white cat
    // disc, Onecta's ring-on-disc) is the tile for all visual purposes — the
    // dark plate around it vanishes into the launcher surface, so anything
    // short of the full tile reads smaller than every bright neighbor whose
    // whole circle is visible. Such a disc is grown to fill the tile outright,
    // anchored at the tile center (it behaves as the background). Rings and
    // letterforms (VW, Monzo) don't qualify and keep
    // [DARK_PLATE_FOREGROUND_FRACTION], which keeps their open shapes off the
    // clip edge.
    private const val DISC_LIKE_HULL_TOLERANCE = 0.10f
    private const val DISC_LIKE_MIN_FILL = 0.6f

    // A plate below this relative luminance counts as blending into the launcher's
    // dark theme surface (the app-list card sits around 0.026; GitHub's near-black
    // plate ~0.02, Monzo/VW navy ~0.01, while even a saturated blue like UniFi's
    // is ~0.15 and clearly visible).
    private const val DARK_PLATE_LUMINANCE = 0.05

    // Perceived-size correction for the logo's shape: a solid square reads larger
    // than a circle of the same bounding box, so the bump compares the box
    // fraction scaled by sqrt(hullByRect / CIRCLE_AREA_BY_RECT) — 1.0 for a
    // circle or anything sparser, up to MAX_SHAPE_FACTOR (= sqrt(1 / (π/4))) for
    // a fully solid box. This keeps a chunky square logo from being enlarged to
    // the same width as a circle and reading oversized.
    private const val CIRCLE_AREA_BY_RECT = 0.7853982f // π/4
    private const val MAX_SHAPE_FACTOR = 1.13f

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

    // Alpha at/above which a pixel counts toward the *sizing* bounds used to
    // measure a foreground logo. Soft drop shadows and glows typically sit well
    // under 40% alpha; counting them (as the VISIBLE_ALPHA bounds do) inflates
    // the measured logo so the enlargement under-fires — Brave's lion rendered
    // below the supposed minimum because its shadow padded the box. Deliberate
    // translucent art (e.g. a 50%-alpha glass logo) still clears this bar.
    private const val SIZING_ALPHA = 102

    // The sizing bounds are trusted only when their row-span hull covers at
    // least this fraction of the tile; otherwise (a fully translucent logo, or
    // a stray opaque speck) sizing falls back to the faint-visible bounds.
    private const val SIZING_MIN_HULL_FRACTION = 0.002f

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
    fun normalizeToTile(drawable: Drawable, sizePx: Int, debugLabel: String = ""): Bitmap {
        // Adaptive icons expose their background and foreground separately, so we
        // fill the tile with the background and scale the foreground (the logo)
        // up to a consistent size — a small logo no longer sits tiny inside its
        // background.
        if (drawable is AdaptiveIconDrawable) return normalizeAdaptive(drawable, sizePx, debugLabel)
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
     * Normalizes an adaptive icon: fills the tile with the background layer (or
     * a dominant-color plate when the background is transparent), then frames
     * the foreground with the platform safe-zone zoom — *capped so the
     * foreground's measured art always fits inside the tile*. Apps like Chrome
     * and the Play Store author their foreground with no safe-zone margin; the
     * blanket 1.5x zoom blows that art past the tile edge and crops it (a small
     * blue dot in a stretched, flat-cut ball). The cap draws such art at its
     * authored size instead. A logo that is still small after the zoom is
     * enlarged to a minimum perceived size ([MIN_FOREGROUND_FRACTION], or
     * [DARK_PLATE_FOREGROUND_FRACTION] when the plate blends into the
     * launcher's dark surface), and a dark-plated *filled disc* logo fills the
     * tile outright (see [DISC_LIKE_MIN_FILL]).
     */
    private fun normalizeAdaptive(drawable: AdaptiveIconDrawable, sizePx: Int, debugLabel: String = ""): Bitmap {
        val tile = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)

        val backgroundBitmap = drawable.background?.let { rasterizeLayer(it, sizePx) }
        val foregroundBitmap = drawable.foreground?.let { rasterizeLayer(it, sizePx) }
        val backgroundAnalysis = backgroundBitmap?.let { analyze(it) }
        val foregroundAnalysis = foregroundBitmap?.let { analyze(it) }

        // Always lay down an opaque plate before the background bitmap, so any
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
        if (backgroundBitmap != null) canvas.drawBitmap(backgroundBitmap, 0f, 0f, null)

        val foreground = drawable.foreground
        if (foreground != null && foregroundAnalysis != null && foregroundAnalysis.hasVisibleContent) {
            // Draw whenever the foreground has any visible content — including a
            // translucent logo with no fully-opaque pixels, which must not
            // disappear. Sizing uses the shadow-excluded bounds so a soft drop
            // shadow or glow doesn't inflate the measured logo.
            val bounds = foregroundAnalysis.sizingBounds
            val contentFraction = maxOf(bounds.width(), bounds.height()).coerceAtLeast(1) / sizePx.toFloat()
            val hullByRect = foregroundAnalysis.sizingHullArea /
                (bounds.width().toFloat() * bounds.height()).coerceAtLeast(1f)
            val shapeFactor = sqrt(hullByRect / CIRCLE_AREA_BY_RECT).coerceIn(1f, MAX_SHAPE_FACTOR)
            val perceivedFraction = contentFraction * shapeFactor
            // A plate that vanishes against the dark launcher surface leaves the
            // logo as the entire visible icon, so it gets the larger minimum. A
            // dark-plated logo that is itself a filled disc *is* the visible
            // tile, so it fills the tile outright and is centered like a
            // background (see DISC_LIKE_MIN_FILL).
            val darkPlate = ColorUtils.calculateLuminance(plate) < DARK_PLATE_LUMINANCE
            val opaqueArea = foregroundAnalysis.coverage * sizePx * sizePx
            val discFill = darkPlate &&
                abs(hullByRect - CIRCLE_AREA_BY_RECT) <= DISC_LIKE_HULL_TOLERANCE &&
                opaqueArea / foregroundAnalysis.sizingHullArea.toFloat().coerceAtLeast(1f) >= DISC_LIKE_MIN_FILL
            val minFraction = when {
                discFill -> 1f
                darkPlate -> DARK_PLATE_FOREGROUND_FRACTION
                else -> MIN_FOREGROUND_FRACTION
            }
            // Never let the framing push the measured art past the tile edge:
            // a foreground authored with no safe-zone margin (Chrome, Play
            // Store, contentFraction ~1.0) is drawn at its authored size
            // instead of being zoomed 1.5x and cropped.
            val fitScale = 1f / contentFraction
            val scale = maxOf(SAFE_ZONE_SCALE, minFraction / perceivedFraction)
                .coerceAtMost(maxOf(fitScale, 1f))
            // Diagnostic: record how large each app authored its foreground (the
            // logo) so the minimum fractions can be tuned from real icons rather
            // than guessed. `contentFraction` is the logo's size in the raw layer;
            // `finalFraction` is its size on the tile after scaling.
            if (debugLabel.isNotEmpty()) {
                LauncherDebugLog.event(
                    "adaptiveIconForeground $debugLabel sizePx=$sizePx " +
                        "contentFraction=$contentFraction shapeFactor=$shapeFactor " +
                        "darkPlate=$darkPlate discFill=$discFill scale=$scale " +
                        "finalFraction=${contentFraction * scale} bumped=${scale > SAFE_ZONE_SCALE}",
                )
            }
            drawForegroundScaled(canvas, foreground, sizePx, scale, bounds, centerContent = discFill)
        }

        backgroundBitmap?.recycle()
        foregroundBitmap?.recycle()
        return tile
    }

    /**
     * Draws an adaptive [foreground] with the platform safe-zone zoom applied
     * about the *tile center* — exactly the framing a stock launcher gives the
     * layer. Any enlargement beyond that ([scale] > [SAFE_ZONE_SCALE], computed
     * by [normalizeAdaptive] from the logo's perceived size) is anchored on the
     * logo's own post-framing center instead, so an off-center logo grows in
     * place rather than being pushed further off-center by a tile-anchored
     * zoom; [centerContent] overrides that for a disc-fill logo, which becomes
     * the tile's background and is therefore moved to the tile center so the
     * grown disc can't spill past an edge. A [scale] *below* the safe-zone
     * zoom (the fit cap on margin-less foregrounds) is a uniform un-zoom of
     * the platform framing, also about the tile center.
     */
    private fun drawForegroundScaled(
        canvas: Canvas,
        foreground: Drawable,
        sizePx: Int,
        scale: Float,
        contentBounds: Rect,
        centerContent: Boolean = false,
    ) {
        foreground.setBounds(0, 0, sizePx, sizePx)
        val saved = canvas.save()
        val center = sizePx / 2f
        if (centerContent) {
            canvas.translate(
                (center - contentBounds.exactCenterX()) * scale,
                (center - contentBounds.exactCenterY()) * scale,
            )
            canvas.scale(scale, scale, center, center)
        } else {
            val extra = scale / SAFE_ZONE_SCALE
            if (extra > 1f) {
                val anchorX = center + (contentBounds.exactCenterX() - center) * SAFE_ZONE_SCALE
                val anchorY = center + (contentBounds.exactCenterY() - center) * SAFE_ZONE_SCALE
                canvas.scale(extra, extra, anchorX, anchorY)
            } else if (extra != 1f) {
                canvas.scale(extra, extra, center, center)
            }
            canvas.scale(SAFE_ZONE_SCALE, SAFE_ZONE_SCALE, center, center)
        }
        foreground.draw(canvas)
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
        // Bounds of the meaningfully opaque art (alpha ≥ SIZING_ALPHA), used to
        // measure a logo for enlargement so a soft shadow or glow under that
        // alpha doesn't inflate the box. Falls back to [bounds] when the layer
        // has no usable art at that threshold (fully translucent, or a speck).
        val sizingBounds: Rect,
        // Per-row span area (sum of leftmost→rightmost visible run per row) of
        // the pixels backing [sizingBounds] — a cheap convex-hull stand-in that
        // fills interior holes, so a ring outline (VW) measures like the disc it
        // reads as rather than as its sparse stroke pixels.
        val sizingHullArea: Int,
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
        var sizingMinX = width
        var sizingMinY = height
        var sizingMaxX = -1
        var sizingMaxY = -1
        var visibleHull = 0
        var sizingHull = 0
        // bucket -> [count, redSum, greenSum, blueSum]
        val histogram = HashMap<Int, IntArray>()

        var index = 0
        for (y in 0 until height) {
            var rowVisibleMin = -1
            var rowVisibleMax = -1
            var rowSizingMin = -1
            var rowSizingMax = -1
            for (x in 0 until width) {
                val pixel = pixels[index++]
                val alpha = (pixel ushr 24) and 0xFF
                if (alpha >= VISIBLE_ALPHA) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    if (rowVisibleMin < 0) rowVisibleMin = x
                    rowVisibleMax = x
                }
                if (alpha >= SIZING_ALPHA) {
                    if (x < sizingMinX) sizingMinX = x
                    if (x > sizingMaxX) sizingMaxX = x
                    if (y < sizingMinY) sizingMinY = y
                    if (y > sizingMaxY) sizingMaxY = y
                    if (rowSizingMin < 0) rowSizingMin = x
                    rowSizingMax = x
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
            if (rowVisibleMax >= 0) visibleHull += rowVisibleMax - rowVisibleMin + 1
            if (rowSizingMax >= 0) sizingHull += rowSizingMax - rowSizingMin + 1
        }

        val total = (width * height).coerceAtLeast(1)
        val coverage = opaque.toFloat() / total
        val hasVisibleContent = maxX >= minX
        val bounds = if (!hasVisibleContent) Rect(0, 0, width, height) else Rect(minX, minY, maxX + 1, maxY + 1)
        val sizingUsable = sizingMaxX >= sizingMinX && sizingHull >= total * SIZING_MIN_HULL_FRACTION
        val sizingBounds = if (sizingUsable) {
            Rect(sizingMinX, sizingMinY, sizingMaxX + 1, sizingMaxY + 1)
        } else {
            bounds
        }
        val dominant = histogram.maxByOrNull { it.value[0] }?.value?.let { accumulator ->
            val count = accumulator[0]
            Color.rgb(accumulator[1] / count, accumulator[2] / count, accumulator[3] / count)
        } ?: Color.WHITE
        return IconAnalysis(
            coverage,
            bounds,
            dominant,
            hasVisibleContent,
            sizingBounds,
            if (sizingUsable) sizingHull else visibleHull,
        )
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
