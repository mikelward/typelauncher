package app.typelauncher

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Height of the fade at the bottom edge of a scrollable page. */
internal val SCROLL_FADE_HEIGHT: Dp = 48.dp

/**
 * How strongly the bottom edge should be faded, given the current scroll
 * position and the fade's own height in pixels.
 *
 * Returns 1 while more than a fade's worth of content is still below the
 * viewport, then tapers to 0 over the final [fadeHeightPx] so the fade
 * dissolves as the last row arrives instead of popping off at the end of the
 * scroll. 0 for a page that doesn't scroll at all — an unscrollable page must
 * not wear the affordance, which is the whole point of the treatment.
 */
internal fun bottomScrollFadeStrength(
    scrollValue: Int,
    maxScrollValue: Int,
    fadeHeightPx: Float,
): Float {
    if (fadeHeightPx <= 0f) return 0f
    // maxValue is Int.MAX_VALUE until the scroll container has been measured;
    // the coerce below keeps that from reading as an absurd remaining distance
    // rather than a full-strength fade.
    val remaining = (maxScrollValue.toLong() - scrollValue.toLong()).toFloat()
    if (remaining <= 0f) return 0f
    return (remaining / fadeHeightPx).coerceIn(0f, 1f)
}

/**
 * Fades the bottom edge of a vertically scrolling page out while there is more
 * content below it, so the page reads as scrollable at a glance rather than
 * ending in a hard cut that looks like the end of the content.
 *
 * Apply this *outside* the `verticalScroll` modifier so the faded region is the
 * viewport's bottom edge rather than the scrolling content's.
 *
 * The fade masks the content's own alpha (`BlendMode.DstIn` inside an offscreen
 * layer) rather than washing the bottom in the page's background color, because
 * Settings' background is transparent while "Show wallpaper" is on — a colored
 * scrim would lay a band of solid color over the wallpaper the page is
 * deliberately letting through. Masking inside the layer is not the
 * window-level `BlendMode.Clear` cutout that failed on-device (see
 * `SettingsScreen`): nothing here erases window pixels, the layer is composited
 * over whatever is behind the page in the ordinary way.
 *
 * Reads the scroll position in the draw phase only, so scrolling retires the
 * fade without recomposing.
 */
internal fun Modifier.bottomScrollFade(
    scrollState: ScrollState,
    fadeHeight: Dp = SCROLL_FADE_HEIGHT,
): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadeHeightPx = fadeHeight.toPx().coerceAtMost(size.height)
        val strength = bottomScrollFadeStrength(
            scrollValue = scrollState.value,
            maxScrollValue = scrollState.maxValue,
            fadeHeightPx = fadeHeightPx,
        )
        if (strength <= 0f) return@drawWithContent
        drawRect(
            // Opaque above the fade (the gradient clamps outside its own band,
            // so the rest of the page keeps its alpha untouched) down to
            // `1 - strength` at the very bottom edge.
            brush = Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - strength)),
                startY = size.height - fadeHeightPx,
                endY = size.height,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
