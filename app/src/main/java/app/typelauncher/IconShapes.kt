package app.typelauncher

import android.graphics.Matrix
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import android.graphics.Path as AndroidPath

/**
 * Resolves an [IconShape] preference to the Compose [Shape] `AppIcon` clips its
 * tile to. Because `IconNormalizer` already produces a full-bleed square, the
 * shape only has to clip — no per-shape rendering changes are needed.
 */
internal fun IconShape.toComposeShape(): Shape = when (this) {
    IconShape.System -> SystemIconShape
    IconShape.Circle -> CircleShape
    IconShape.Squircle -> SquircleShape
}

/**
 * A squircle (superellipse) — the iOS / Samsung-style rounded square with
 * continuous-curvature corners, distinct from a plain rounded rectangle.
 */
internal val SquircleShape: Shape = GenericShape { size, _ ->
    addPath(squircleAndroidPath(size.width, size.height).asComposePath())
}

/**
 * The device's own adaptive-icon mask (a circle on Pixel, a squircle on
 * Samsung, etc.), read once from [AdaptiveIconDrawable] and scaled to the tile.
 * Falls back to a circle when the mask can't be read.
 */
internal val SystemIconShape: Shape = GenericShape { size, _ ->
    val mask = systemIconMaskAndroidPath(size.width, size.height)
    if (mask != null) addPath(mask.asComposePath()) else addOval(Rect(0f, 0f, size.width, size.height))
}

/**
 * The device adaptive-icon mask scaled to [width] x [height], or null when the
 * platform exposes no usable mask. Shared by [SystemIconShape] and the
 * screenshot test.
 */
internal fun systemIconMaskAndroidPath(width: Float, height: Float): AndroidPath? {
    val unit = DeviceIconMask.unitPath ?: return null
    val scaled = AndroidPath(unit)
    scaled.transform(Matrix().apply { setScale(width / MASK_UNIT, height / MASK_UNIT) })
    return scaled
}

// The adaptive-icon mask is authored in a fixed 100x100 viewport; we read it
// once at that size and scale per tile.
private const val MASK_UNIT = 100f

// Superellipse exponent. ~5 matches the iOS/Samsung squircle; lower is rounder
// (closer to a circle), higher is squarer.
private const val SQUIRCLE_EXPONENT = 5.0

// Number of segments used to approximate the superellipse outline. 96 is smooth
// enough that the straight segments are invisible at icon sizes.
private const val SQUIRCLE_SEGMENTS = 96

private object DeviceIconMask {
    // Read the framework's `config_icon_mask` once. AdaptiveIconDrawable builds
    // its mask from system resources, so no app Context is needed. Null when the
    // platform doesn't expose a usable mask (then SystemIconShape uses a circle).
    val unitPath: AndroidPath? by lazy {
        runCatching {
            val drawable = AdaptiveIconDrawable(null, null)
            drawable.setBounds(0, 0, MASK_UNIT.toInt(), MASK_UNIT.toInt())
            AndroidPath(drawable.iconMask)
        }.getOrNull()?.takeIf { !it.isEmpty }
    }
}

/**
 * Builds a superellipse path filling [width] x [height]. Shared by
 * [SquircleShape] and the screenshot test so both draw the exact same outline.
 */
internal fun squircleAndroidPath(width: Float, height: Float): AndroidPath {
    val path = AndroidPath()
    val halfWidth = width / 2.0
    val halfHeight = height / 2.0
    val exponent = 2.0 / SQUIRCLE_EXPONENT
    for (segment in 0..SQUIRCLE_SEGMENTS) {
        val theta = -PI + (2.0 * PI * segment) / SQUIRCLE_SEGMENTS
        val cosTheta = cos(theta)
        val sinTheta = sin(theta)
        val x = halfWidth + halfWidth * cosTheta.sign * cosTheta.absoluteValue.pow(exponent)
        val y = halfHeight + halfHeight * sinTheta.sign * sinTheta.absoluteValue.pow(exponent)
        if (segment == 0) path.moveTo(x.toFloat(), y.toFloat()) else path.lineTo(x.toFloat(), y.toFloat())
    }
    path.close()
    return path
}
