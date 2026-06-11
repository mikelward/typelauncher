package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

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
     * Colors for the themed-icon rendering path: an opaque [plate] the tile is
     * filled with and the [glyph] tint applied to the app's monochrome layer.
     */
    internal data class ThemedIconColors(val plate: Int, val glyph: Int)

    /**
     * Rasterizes [drawable] to a full-bleed `sizePx` square tile, opaque to the
     * launcher's rounded clip — the tile carries no margin or ring of its own;
     * the icon's art (or its own background layer) fills it edge-to-edge. The
     * returned bitmap is always `sizePx` × `sizePx`.
     *
     * When [themedColors] is non-null (the Monochrome icon theme on API 33+),
     * every app renders as a themed two-tone tile — the glyph tinted
     * [ThemedIconColors.glyph] on a [ThemedIconColors.plate] fill. An app that
     * ships an adaptive monochrome layer uses it directly; an app without one
     * has a glyph synthesized from its existing art (the adaptive foreground
     * logo, or the whole drawable for a legacy icon), so the grid is uniformly
     * monochrome rather than a mix of themed and full-color icons. The
     * synthesized glyph is engraved from the icon's own brightness (see
     * [engraveThemedGlyph]) so its shape, internal lines, and art survive as a
     * single-color mark instead of flattening to a solid disc.
     */
    fun normalizeToTile(
        drawable: Drawable,
        sizePx: Int,
        debugLabel: String = "",
        themedColors: ThemedIconColors? = null,
    ): Bitmap {
        // Adaptive icons are composed from their background and foreground
        // layers with the platform framing; legacy icons are re-seated on a
        // dominant-color plate.
        if (drawable is AdaptiveIconDrawable) {
            if (themedColors != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawable.monochrome?.let { mono ->
                    return normalizeThemed(mono, sizePx, themedColors, debugLabel)
                }
                // No authored monochrome layer: engrave a glyph from the app's
                // own composed art (background + foreground) so its shape and
                // internal detail survive as a single-color mark on the plate.
                val art = rasterizeComposedAdaptive(drawable, sizePx)
                val tile = engraveThemedGlyph(art, sizePx, themedColors, debugLabel)
                art.recycle()
                return tile
            }
            return normalizeAdaptive(drawable, sizePx, debugLabel)
        }
        // A legacy (non-adaptive) icon — and a user-supplied override, which
        // arrives here wrapped in a BitmapDrawable — has no monochrome layer; the
        // themed request engraves a glyph from its whole art. themedColors is only
        // non-null on API 33+ (gated by the caller), so no SDK check here.
        if (themedColors != null) {
            val art = rasterizeFullBleed(drawable, sizePx)
            val tile = engraveThemedGlyph(art, sizePx, themedColors, debugLabel)
            art.recycle()
            return tile
        }
        return normalizeFlat(drawable, sizePx, debugLabel)
    }

    /**
     * Rasterizes an adaptive icon's composed art — background then foreground,
     * with the platform safe-zone framing — into a transparent-backed `sizePx`
     * tile, the source [engraveThemedGlyph] reads brightness from. No plate is
     * laid down first: transparent areas must stay transparent so the engraving
     * maps them to the plate rather than to a phantom mark.
     */
    private fun rasterizeComposedAdaptive(drawable: AdaptiveIconDrawable, sizePx: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.background?.let { drawLayerFramed(canvas, it, sizePx) }
        drawable.foreground?.let { drawLayerFramed(canvas, it, sizePx) }
        return bitmap
    }

    // Below this peak ink level the icon carries no usable color variation to
    // engrave (a flat single-color tile), so the glyph falls back to the icon's
    // alpha silhouette instead of washing out to a near-empty plate. In
    // normalized color-distance units (0..1).
    private const val DETAIL_EPSILON = 0.04f

    // Largest possible RGB color distance (black↔white), sqrt(3)·255, used to
    // normalize the per-pixel color distance into 0..1.
    private const val MAX_COLOR_DISTANCE = 441.673f

    // Deadzone applied to the normalized ink (color distance / peak): a pixel
    // below INK_KNEE_LOW of the peak is treated as field (plate); from there it
    // ramps to full glyph by INK_KNEE_HIGH. This stops a multicolor/gradient
    // background — whose hues sit a little off the single modal field color —
    // from being washed with a sheen of glyph color, and crispens every glyph
    // edge. A smoothstep ramp (not a hard step) keeps anti-aliased mark edges
    // smooth between the two knees.
    private const val INK_KNEE_LOW = 0.22f
    private const val INK_KNEE_HIGH = 0.70f

    private fun inkResponse(t: Float): Float {
        val x = ((t - INK_KNEE_LOW) / (INK_KNEE_HIGH - INK_KNEE_LOW)).coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    // Brightness-histogram resolution used to find the icon's dominant luminance
    // band. 32 bins separate a logo from its background at a useful granularity.
    private const val LUMINANCE_BINS = 32

    // Half-width (in bins) of the window summed when choosing the dominant
    // brightness, so a gradient background spread across contiguous bins outvotes
    // a solid logo concentrated in a single bin. 2 ⇒ a 5-bin (~16% luminance)
    // band.
    private const val LUMINANCE_FIELD_WINDOW = 2

    /**
     * Engraves [art] (the app's own rasterized icon) into a single-color glyph on
     * the [ThemedIconColors.plate], deriving each pixel's glyph coverage from how
     * far its *color* is from the icon's background color — so a shape set apart
     * by color (a red life-ring on white, or a logo that differs from its
     * background only in hue at the same brightness) keeps its form, where an
     * alpha-only silhouette would flatten the whole opaque tile to a solid disc.
     *
     * The field is the modal color within the icon's dominant luminance band (the
     * densest window of a brightness histogram); pixels whose color is far from it
     * become the [ThemedIconColors.glyph]. Locating the band by windowed
     * brightness keeps a gradient/photographic background from letting a solid
     * logo be mistaken for the field, and taking the color within it adds hue
     * sensitivity. Coverage is gated by the source alpha, and a
     * transparency-weighted silhouette floor keeps a logo that sits on
     * transparency (its shape is in the alpha — the Monzo "M" case) from washing
     * out. An icon with no color variation at all has nothing to engrave, so it
     * falls back to its alpha silhouette.
     */
    private fun engraveThemedGlyph(
        art: Bitmap,
        sizePx: Int,
        themedColors: ThemedIconColors,
        debugLabel: String = "",
    ): Bitmap {
        val count = sizePx * sizePx
        val pixels = IntArray(count)
        art.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)

        val alpha = FloatArray(count)
        val luminance = FloatArray(count)
        // Brightness histogram over opaque pixels, used only to locate the icon's
        // dominant luminance band (the background); the field *color* is taken
        // from within that band below.
        val binCount = IntArray(LUMINANCE_BINS)
        val opaqueThreshold = OPAQUE_ALPHA / 255f
        var opaque = 0
        for (i in 0 until count) {
            val pixel = pixels[i]
            val a = (pixel ushr 24) and 0xFF
            alpha[i] = a / 255f
            val l = relativeLuminance(pixel)
            luminance[i] = l
            if (a >= OPAQUE_ALPHA) {
                opaque++
                binCount[(l * (LUMINANCE_BINS - 1)).roundToInt().coerceIn(0, LUMINANCE_BINS - 1)]++
            }
        }

        val tile = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(themedColors.plate)
        val glyphRgb = themedColors.glyph and 0x00FFFFFF
        if (opaque == 0) {
            // No fully-opaque pixels to read brightness from, but the art may
            // still be visible — a uniformly translucent logo. Fall back to its
            // alpha silhouette so the icon doesn't vanish to a bare plate; a
            // genuinely empty drawable draws nothing and stays plate.
            for (i in 0 until count) {
                pixels[i] = ((alpha[i] * 255f).roundToInt() shl 24) or glyphRgb
            }
            val silhouette = Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(silhouette, 0f, 0f, null)
            silhouette.recycle()
            return tile
        }

        // Locate the icon's dominant luminance band — the densest *window* of
        // bins, not a single bin, so a gradient or photographic background spread
        // across many bins outvotes a solid logo concentrated in one. (A lone-bin
        // choice could pick the logo as the field and invert the mark.)
        var bestBin = 0
        var bestScore = -1
        for (b in 0 until LUMINANCE_BINS) {
            var score = 0
            for (w in -LUMINANCE_FIELD_WINDOW..LUMINANCE_FIELD_WINDOW) {
                val bin = b + w
                if (bin in 0 until LUMINANCE_BINS) score += binCount[bin]
            }
            if (score > bestScore) {
                bestScore = score
                bestBin = b
            }
        }

        // The field color is the modal color *within* that band — the background.
        // Restricting the color vote to the band keeps a gradient's spread-out
        // colors from letting a solid logo win it, and keeps a hue-only mark that
        // shares the field's brightness from being taken for the field.
        val bandLo = (bestBin - LUMINANCE_FIELD_WINDOW).coerceAtLeast(0)
        val bandHi = (bestBin + LUMINANCE_FIELD_WINDOW).coerceAtMost(LUMINANCE_BINS - 1)
        val colorBuckets = HashMap<Int, IntArray>() // bucket -> [count, rSum, gSum, bSum]
        for (i in 0 until count) {
            if (alpha[i] < opaqueThreshold) continue
            val bin = (luminance[i] * (LUMINANCE_BINS - 1)).roundToInt().coerceIn(0, LUMINANCE_BINS - 1)
            if (bin < bandLo || bin > bandHi) continue
            val pixel = pixels[i]
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val bucket = ((r shr COLOR_BUCKET_SHIFT) shl 10) or
                ((g shr COLOR_BUCKET_SHIFT) shl 5) or (b shr COLOR_BUCKET_SHIFT)
            val acc = colorBuckets.getOrPut(bucket) { IntArray(4) }
            acc[0]++
            acc[1] += r
            acc[2] += g
            acc[3] += b
        }
        val field = colorBuckets.maxByOrNull { it.value[0] }!!.value
        val fieldR = field[1] / field[0]
        val fieldG = field[2] / field[0]
        val fieldB = field[3] / field[0]

        // Ink is each pixel's color distance from the field color — hue *and*
        // brightness — so a mark set apart only by hue at the same brightness is
        // still inked, where a luminance-only metric would miss it. Measured only
        // over opaque pixels; a transparent background reads as black (RGB 0) and
        // would otherwise count as a strong mark, so it is gated out by alpha.
        var maxInk = 0f
        val ink = FloatArray(count)
        for (i in 0 until count) {
            if (alpha[i] < opaqueThreshold) continue
            val pixel = pixels[i]
            val dr = ((pixel ushr 16) and 0xFF) - fieldR
            val dg = ((pixel ushr 8) and 0xFF) - fieldG
            val db = (pixel and 0xFF) - fieldB
            val v = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat()) / MAX_COLOR_DISTANCE
            ink[i] = v
            if (v > maxInk) maxInk = v
        }

        val silhouetteWeight = (1f - opaque.toFloat() / count).coerceIn(0f, 1f)
        val hasDetail = maxInk >= DETAIL_EPSILON
        for (i in 0 until count) {
            val coverage = if (!hasDetail) {
                alpha[i]
            } else {
                // A deadzone on the normalized ink: pixels whose color barely
                // departs from the field (a multicolor or gradient background,
                // each hue a little off the modal field color) snap to the plate
                // instead of being faintly inked, while a true mark — at or near
                // the peak ink — still fills. This keeps a gradient/photographic
                // background clean rather than washing it with a sheen of glyph
                // color, and sharpens every glyph's edge against the plate.
                alpha[i] * (silhouetteWeight + (1f - silhouetteWeight) * inkResponse(ink[i] / maxInk))
            }
            val a = (coverage.coerceIn(0f, 1f) * 255f).roundToInt()
            pixels[i] = (a shl 24) or glyphRgb
        }
        val glyph = Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(glyph, 0f, 0f, null)
        glyph.recycle()

        if (debugLabel.isNotEmpty()) {
            LauncherDebugLog.event(
                "iconTile $debugLabel sizePx=$sizePx themed synthesized engrave " +
                    "field=${hexColor(Color.rgb(fieldR, fieldG, fieldB))} maxInk=${"%.2f".format(maxInk)} " +
                    "silhouetteW=${"%.2f".format(silhouetteWeight)} detail=$hasDetail " +
                    "plate=${hexColor(themedColors.plate)} glyph=${hexColor(themedColors.glyph)}",
            )
        }
        return tile
    }

    /** Perceptual brightness (Rec. 709, no gamma) of a pixel's RGB, in 0..1. */
    private fun relativeLuminance(pixel: Int): Float {
        val r = (pixel ushr 16) and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = pixel and 0xFF
        return (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
    }

    /**
     * Composes the themed tile for an app's *authored* monochrome layer: a
     * [ThemedIconColors.plate] fill under the [mono] glyph tinted
     * [ThemedIconColors.glyph], drawn with the same platform safe-zone framing as
     * any adaptive layer — the monochrome glyph spec is standardized, so no
     * per-icon sizing is needed. (Apps without a monochrome layer take the
     * [engraveThemedGlyph] path instead.)
     */
    private fun normalizeThemed(
        mono: Drawable,
        sizePx: Int,
        themedColors: ThemedIconColors,
        debugLabel: String = "",
    ): Bitmap {
        val tile = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(themedColors.plate)
        val glyph = mono.mutate()
        glyph.setTint(themedColors.glyph)
        drawLayerFramed(canvas, glyph, sizePx)
        if (debugLabel.isNotEmpty()) {
            LauncherDebugLog.event(
                "iconTile $debugLabel sizePx=$sizePx themed mono=${mono.javaClass.simpleName} " +
                    "plate=${hexColor(themedColors.plate)} glyph=${hexColor(themedColors.glyph)}",
            )
        }
        return tile
    }

    /**
     * Composes a non-adaptive [drawable] onto a dominant-color plate: a
     * full-bleed icon is drawn edge-to-edge (no mismatched ring), a padded or
     * sparse icon is enlarged to [CONTENT_FRACTION] and centered, and the plate
     * fills any transparent corners or interior holes with the icon's own
     * color. The drawable is re-drawn under a scale matrix rather than
     * upscaling its bitmap, so a vector icon stays crisp when enlarged.
     */
    private fun normalizeFlat(drawable: Drawable, sizePx: Int, debugLabel: String = ""): Bitmap {
        val raw = rasterizeFullBleed(drawable, sizePx)
        val analysis = analyze(raw)
        raw.recycle()
        val fillFraction = if (analysis.coverage >= FULL_BLEED_COVERAGE) 1f else CONTENT_FRACTION
        if (debugLabel.isNotEmpty()) {
            LauncherDebugLog.event(
                "iconTile $debugLabel sizePx=$sizePx flat ${drawable.javaClass.simpleName} " +
                    "${analysis.describe(sizePx)} fillFraction=$fillFraction",
            )
        }
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
    private fun normalizeAdaptive(drawable: AdaptiveIconDrawable, sizePx: Int, debugLabel: String = ""): Bitmap {
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
        if (debugLabel.isNotEmpty()) {
            // Structural diagnostic only — what the icon's layers actually are
            // and what the analysis saw, so a misrendered icon can be diagnosed
            // from `adb logcat -s TypeLauncherDebug` instead of guessed at.
            // `mono` reports whether the app ships a themed-icon monochrome
            // glyph (API 33+) — the prerequisite for any future glyph-style
            // rendering — and `edgeLum` is the mean luminance of the composed
            // tile's outer ring, i.e. the boundary the eye looks for against
            // the launcher surface.
            val mono = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                drawable.monochrome?.javaClass?.simpleName ?: "null"
            } else {
                "unsupported"
            }
            LauncherDebugLog.event(
                "iconTile $debugLabel sizePx=$sizePx adaptive " +
                    "bg=${describe(drawable.background, backgroundAnalysis, sizePx)} " +
                    "fg=${describe(drawable.foreground, foregroundAnalysis, sizePx)} " +
                    "mono=$mono plate=${hexColor(plate)} edgeLum=${"%.3f".format(edgeLuminance(tile))}",
            )
        }
        return tile
    }

    /**
     * Mean relative luminance of the tile's outer ring (64 samples at 93% of
     * the inscribed-circle radius) — the boundary the eye sizes an icon by.
     * Diagnostic only.
     */
    private fun edgeLuminance(tile: Bitmap): Float {
        val center = tile.width / 2f
        val radius = center * 0.93f
        var sum = 0.0
        for (i in 0 until 64) {
            val angle = i * (2 * Math.PI / 64)
            val x = (center + radius * kotlin.math.cos(angle)).toInt().coerceIn(0, tile.width - 1)
            val y = (center + radius * kotlin.math.sin(angle)).toInt().coerceIn(0, tile.height - 1)
            sum += ColorUtils.calculateLuminance(tile.getPixel(x, y))
        }
        return (sum / 64).toFloat()
    }

    /** One-line factual description of an adaptive layer for the diagnostic log. */
    private fun describe(layer: Drawable?, analysis: IconAnalysis?, sizePx: Int): String {
        if (layer == null) return "null"
        return "${layer.javaClass.simpleName}${analysis?.let { "(${it.describe(sizePx)})" } ?: ""}"
    }

    private fun IconAnalysis.describe(sizePx: Int): String =
        "cov=%.2f boundsFraction=%.2f dom=%s".format(
            coverage,
            maxOf(bounds.width(), bounds.height()) / sizePx.toFloat(),
            hexColor(dominantColor),
        )

    private fun hexColor(color: Int): String = "#%08X".format(color)

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
