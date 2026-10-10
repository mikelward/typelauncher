package app.typelauncher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How long the kiosk display stays bright after the last motion or touch. */
internal const val KIOSK_DIM_AFTER_MS = 60_000L

/**
 * Window brightness while dimmed: low enough to stop lighting the room, high
 * enough that the widgets stay faintly readable up close. A fraction of the
 * panel's range, as `WindowManager.LayoutParams.screenBrightness` takes it.
 */
internal const val KIOSK_DIM_BRIGHTNESS = 0.02f

/**
 * Tells whether successive camera frames show movement, by comparing a coarse
 * grid of average brightness between frames. Coarse on purpose: it sees a
 * person walking up, not detail, and slow light changes (a cloud, a lamp
 * dimming) move every cell a little where a person moves some cells a lot.
 *
 * Not thread-safe; feed it from one thread (the camera analyzer's).
 */
internal class KioskMotionDetector(
    private val gridColumns: Int = 16,
    private val gridRows: Int = 12,
    // A cell has changed when its average luma moved by more than this (0–255).
    private val cellThreshold: Int = 24,
    // Motion when at least this share of cells changed at once.
    private val changedFraction: Float = 0.04f,
) {
    private var previous: IntArray? = null

    /**
     * Feeds one frame's luma plane: [width]×[height] samples, each row
     * [rowStride] bytes apart. Returns true when it differs enough from the
     * previous frame to count as motion. The first frame only sets a baseline.
     */
    fun onFrame(luma: ByteArray, width: Int, height: Int, rowStride: Int): Boolean {
        val grid = cellAverages(luma, width, height, rowStride)
        val last = previous
        previous = grid
        if (last == null) return false
        val changed = grid.indices.count { abs(grid[it] - last[it]) > cellThreshold }
        return changed >= (grid.size * changedFraction).coerceAtLeast(1f)
    }

    private fun cellAverages(luma: ByteArray, width: Int, height: Int, rowStride: Int): IntArray {
        val sums = LongArray(gridColumns * gridRows)
        val counts = IntArray(gridColumns * gridRows)
        // Every 4th sample in each direction is plenty for cell averages and
        // keeps the per-frame cost tiny.
        for (y in 0 until height step 4) {
            val row = y * gridRows / height
            val base = y * rowStride
            for (x in 0 until width step 4) {
                val cell = row * gridColumns + x * gridColumns / width
                sums[cell] += (luma[base + x].toInt() and 0xFF)
                counts[cell]++
            }
        }
        return IntArray(sums.size) { if (counts[it] == 0) 0 else (sums[it] / counts[it]).toInt() }
    }
}

/**
 * The kiosk display's idle timer: [dimmed] turns true once [dimAfterMs] pass
 * with no [onActivity], and false again the moment there is some. Runs its
 * countdown in [scope], so a test drives it on virtual time.
 */
internal class KioskIdleDimmer(
    private val scope: CoroutineScope,
    private val dimAfterMs: Long = KIOSK_DIM_AFTER_MS,
) {
    private val _dimmed = MutableStateFlow(false)
    val dimmed: StateFlow<Boolean> = _dimmed
    private var countdown: Job? = null

    /** Starts (or restarts) the countdown; call once when the display shows. */
    fun start() = onActivity()

    /** Motion or a touch: brighten now and start the quiet period over. */
    fun onActivity() {
        _dimmed.value = false
        countdown?.cancel()
        countdown = scope.launch {
            delay(dimAfterMs)
            _dimmed.value = true
        }
    }

    fun stop() {
        countdown?.cancel()
        countdown = null
        _dimmed.value = false
    }
}

/** Window brightness while the display is blanked for the night: the lowest the window can ask for. */
internal const val KIOSK_BLANK_BRIGHTNESS = 0f

/** "Blank at night" defaults: from midnight until 6am, as minutes after midnight. */
internal const val KIOSK_BLANK_DEFAULT_START_MINUTES = 0
internal const val KIOSK_BLANK_DEFAULT_END_MINUTES = 6 * 60

internal const val KIOSK_MINUTES_PER_DAY = 24 * 60

/**
 * Whether [minuteOfDay] falls in the night window [startMinutes] (inclusive)
 * to [endMinutes] (exclusive), all minutes after midnight. A window whose end
 * is before its start runs past midnight (22:00–06:00), and one whose ends
 * match is empty, since a whole day of blanking is never what was meant.
 */
internal fun isInKioskBlankWindow(minuteOfDay: Int, startMinutes: Int, endMinutes: Int): Boolean = when {
    startMinutes == endMinutes -> false
    startMinutes < endMinutes -> minuteOfDay in startMinutes until endMinutes
    else -> minuteOfDay >= startMinutes || minuteOfDay < endMinutes
}
