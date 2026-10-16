package app.typelauncher

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * How long the Home kiosk mode stepped out to may sit untouched before the
 * display comes back: the same quiet minute that dims or blanks the display.
 */
internal const val KIOSK_RETURN_AFTER_MS = KIOSK_DIM_AFTER_MS

/**
 * Counts the quiet on the Home kiosk mode stepped out to, and calls [onQuiet]
 * once [quietMs] pass with no [onInteraction]. [restart] starts the count as
 * that Home comes on screen; [stop] ends it as it leaves (Settings, editing, an
 * app in front), so time spent elsewhere never counts as quiet. Runs in
 * [scope], so a test drives it on virtual time.
 */
internal class KioskReturnTimer(
    private val scope: CoroutineScope,
    private val quietMs: Long = KIOSK_RETURN_AFTER_MS,
    private val onQuiet: () -> Unit,
) {
    private var countdown: Job? = null

    /** Starts the quiet period over. */
    fun restart() {
        countdown?.cancel()
        countdown = scope.launch {
            delay(quietMs)
            countdown = null
            onQuiet()
        }
    }

    /** A touch or a key: starts the quiet period over, if one is being counted. */
    fun onInteraction() {
        if (countdown != null) restart()
    }

    fun stop() {
        countdown?.cancel()
        countdown = null
    }
}
