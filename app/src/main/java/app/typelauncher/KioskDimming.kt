package app.typelauncher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/** How long the kiosk display stays bright after the last motion or touch. */
internal const val KIOSK_DIM_AFTER_MS = 60_000L

/**
 * After the camera sees motion it turns off for this long: half the dim
 * timeout, so it is back watching well before the display would dim, and the
 * camera is off about half the time while someone is around.
 */
internal const val KIOSK_MOTION_REST_MS = KIOSK_DIM_AFTER_MS / 2

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
    // Frames ignored after the camera starts, while its auto-exposure settles:
    // the brightness swings then would read as motion, and since the camera
    // restarts after every rest, that would keep the display awake for good.
    private val warmupFrames: Int = 5,
) {
    private var previous: IntArray? = null
    private var framesSeen = 0

    /**
     * Feeds one frame's luma plane: [width]×[height] samples, each row
     * [rowStride] bytes apart. Returns true when it differs enough from the
     * previous frame to count as motion. The first [warmupFrames] frames are
     * skipped and the next one only sets a baseline.
     */
    fun onFrame(luma: ByteArray, width: Int, height: Int, rowStride: Int): Boolean {
        if (framesSeen < warmupFrames) {
            framesSeen++
            return false
        }
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

/** "Wake up using light level"'s level, in lux, until the user sets one: above a dark room, below a lit one. */
internal const val KIOSK_LIGHT_WAKE_DEFAULT_LUX = 20f

/** The range the light level can be set across, in lux: from near-dark to a bright room. */
internal const val KIOSK_LIGHT_WAKE_MIN_LUX = 1f
internal const val KIOSK_LIGHT_WAKE_MAX_LUX = 1000f

/** A light level brought into range and to a whole lux, as the setting stores and shows it. */
internal fun kioskLightWakeLux(lux: Float): Float =
    if (lux.isNaN()) KIOSK_LIGHT_WAKE_DEFAULT_LUX
    else lux.coerceIn(KIOSK_LIGHT_WAKE_MIN_LUX, KIOSK_LIGHT_WAKE_MAX_LUX).roundToInt().toFloat()

/**
 * The light-level slider's position (0–1) for [lux]. Logarithmic, since light
 * is: a dark room and a lamp-lit one differ by a few lux, a lit room and
 * daylight by hundreds, and a linear slider would crowd the first into a sliver.
 */
internal fun kioskLightWakeSliderPosition(lux: Float): Float {
    val low = log10(KIOSK_LIGHT_WAKE_MIN_LUX)
    val high = log10(KIOSK_LIGHT_WAKE_MAX_LUX)
    return (log10(kioskLightWakeLux(lux)) - low) / (high - low)
}

/** The light level, in whole lux, at slider [position]; the inverse of [kioskLightWakeSliderPosition]. */
internal fun kioskLightWakeLuxAt(position: Float): Float {
    val low = log10(KIOSK_LIGHT_WAKE_MIN_LUX)
    val high = log10(KIOSK_LIGHT_WAKE_MAX_LUX)
    return kioskLightWakeLux(10f.pow(low + position.coerceIn(0f, 1f) * (high - low)))
}

/**
 * Tells whether the room is lit, by the light level [thresholdLux]: lit from a
 * reading at or above it, and lit until a reading falls below [darkFraction]
 * of it, so a reading hovering around the level doesn't flip the display
 * between awake and dimming. The first reading sets where the room starts; one
 * between the two marks counts as dark.
 *
 * Not thread-safe; feed it from one thread (the main one, as the sensor's
 * readings are delivered).
 */
internal class KioskRoomLightTracker(
    private val thresholdLux: Float,
    private val darkFraction: Float = 0.7f,
) {
    // Null until the first reading.
    private var lit: Boolean? = null

    /**
     * Feeds one reading, in lux. Returns whether the room is lit when that is
     * news (the first reading, or a change), and null when nothing changed.
     */
    fun onReading(lux: Float): Boolean? {
        val nowLit = when {
            lux >= thresholdLux -> true
            lux < thresholdLux * darkFraction -> false
            else -> lit ?: false
        }
        if (nowLit == lit) return null
        lit = nowLit
        return nowLit
    }

    /**
     * The sensor can no longer be read: the room counts as dark until it
     * reports again, and that next reading is news whatever it says. Returns
     * false when the room was lit, so the hold it set is released, and null
     * otherwise.
     */
    fun onUnavailable(): Boolean? {
        val wasLit = lit == true
        lit = null
        return if (wasLit) false else null
    }
}

/**
 * The kiosk display's idle timer: [dimmed] turns true once [dimAfterMs] pass
 * with no [onActivity], and false again the moment there is some. While
 * [setHeld] holds it (a lit room), it never counts down; releasing it starts
 * the quiet period over. Runs its countdown in [scope], so a test drives it on
 * virtual time.
 */
internal class KioskIdleDimmer(
    private val scope: CoroutineScope,
    private val dimAfterMs: Long = KIOSK_DIM_AFTER_MS,
) {
    private val _dimmed = MutableStateFlow(false)
    val dimmed: StateFlow<Boolean> = _dimmed
    private var countdown: Job? = null
    private var held = false

    /** Starts (or restarts) the countdown; call once when the display shows. */
    fun start() = onActivity()

    /** Motion or a touch: brighten now and start the quiet period over. */
    fun onActivity() {
        _dimmed.value = false
        countdown?.cancel()
        countdown = if (held) {
            null
        } else {
            scope.launch {
                delay(dimAfterMs)
                _dimmed.value = true
            }
        }
    }

    /**
     * Keeps the display awake for as long as [held] is true, as if there were
     * activity all along; on release the quiet period starts from then.
     */
    fun setHeld(held: Boolean) {
        if (this.held == held) return
        this.held = held
        onActivity()
    }

    fun stop() {
        countdown?.cancel()
        countdown = null
        held = false
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
