package app.typelauncher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the kiosk display holds each pixel-shift position. */
internal const val KIOSK_PIXEL_SHIFT_INTERVAL_MS = 3 * 60_000L

/**
 * The positions the kiosk display steps through to spread wear across an
 * OLED's pixels: the ring of a 3×3 grid of 4 dp steps around where the layout
 * put it, walked in order so every move is a single 4 dp step on one axis (a
 * jump across the grid would catch the eye), the last one included. The 16 dp
 * margin around the display leaves room for the 4 dp at either edge.
 */
internal val KIOSK_PIXEL_SHIFT_OFFSETS: List<DpOffset> = listOf(
    4 to 0, 4 to 4, 0 to 4, -4 to 4, -4 to 0, -4 to -4, 0 to -4, 4 to -4,
).map { (x, y) -> DpOffset(x.dp, y.dp) }

/**
 * Moves the content to the next [KIOSK_PIXEL_SHIFT_OFFSETS] position every
 * [KIOSK_PIXEL_SHIFT_INTERVAL_MS]. A graphics-layer translation, so a move
 * re-measures nothing — the hosted widgets keep their size, and no size hint
 * goes to their providers — and touches follow the content where it is drawn.
 */
@Composable
internal fun Modifier.kioskPixelShift(): Modifier {
    var step by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(KIOSK_PIXEL_SHIFT_INTERVAL_MS)
            step = (step + 1) % KIOSK_PIXEL_SHIFT_OFFSETS.size
        }
    }
    return graphicsLayer {
        // Read here, in the layer block, so a move redraws without recomposing.
        val offset = KIOSK_PIXEL_SHIFT_OFFSETS[step]
        translationX = offset.x.toPx()
        translationY = offset.y.toPx()
    }
}
