package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for [resolveKeyboardReservation] and the settled-height contract it
 * enforces: Home persists the keyboard height the *visible* IME actually rests
 * at, never a transient animation-target peak.
 *
 * The regression this guards is the dock floating ~30dp too high for ~a second
 * after a quick carousel swipe-back: the keyboard's multi-stage open reported a
 * taller height (905px) than it ultimately settled at (824px), and the old
 * target-keyed growth path latched that peak into the cached reservation. The
 * [multiStageOpenPeak_isNeverPersisted] timeline test pins the fix.
 */
class KeyboardReservationGrowthTest {
    private val config = KeyboardReservationConfig(
        orientation = 1,
        screenWidthDp = 411,
        screenHeightDp = 918,
        densityDpi = 420,
        navBottomPx = 126,
    )

    @Test
    fun growsToTallerSettledHeight() {
        // resolve itself adopts any settled height handed to it — keeping the
        // transient peak out is the caller's debounce job (see the timeline
        // test). A genuinely taller settled keyboard must still grow.
        val current = KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)
        assertEquals(
            KeyboardReservation(905, config, KeyboardReservationSource.VisibleIme),
            resolveKeyboardReservation(settledImeBottomPx = 905, config = config, current = current),
        )
    }

    @Test
    fun shrinksToShorterSettledHeight() {
        val current = KeyboardReservation(905, config, KeyboardReservationSource.VisibleIme)
        assertEquals(
            KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme),
            resolveKeyboardReservation(settledImeBottomPx = 824, config = config, current = current),
        )
    }

    @Test
    fun equalHeightFromVisibleIme_doesNotRewrite() {
        val current = KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)
        assertNull(resolveKeyboardReservation(settledImeBottomPx = 824, config = config, current = current))
    }

    @Test
    fun equalHeightUpgradesAnimationTargetToVisibleIme() {
        // A reservation seeded animation-target-only is ground-truthed once the
        // visible keyboard confirms the same height, so it can later shrink.
        val current = KeyboardReservation(824, config, KeyboardReservationSource.AnimationTarget)
        assertEquals(
            KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme),
            resolveKeyboardReservation(settledImeBottomPx = 824, config = config, current = current),
        )
    }

    @Test
    fun configChangeAlwaysOverwritesStaleReservation() {
        val current = KeyboardReservation(824, config.copy(orientation = 2), KeyboardReservationSource.VisibleIme)
        assertEquals(
            KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme),
            resolveKeyboardReservation(settledImeBottomPx = 824, config = config, current = current),
        )
    }

    @Test
    fun heightAtOrBelowNavInset_isIgnored() {
        val current = KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)
        assertNull(resolveKeyboardReservation(settledImeBottomPx = config.navBottomPx, config = config, current = current))
        assertNull(resolveKeyboardReservation(settledImeBottomPx = config.navBottomPx - 10, config = config, current = current))
    }

    @Test
    fun multiStageOpenPeak_isNeverPersisted() {
        // A quick swipe-back reshow on the bug-report device: the visible IME
        // bottom climbs, brushes a 905px first-stage peak for ~30ms, then
        // settles back to the real 824px height. The cached reservation is
        // already 824, so nothing should be persisted — and crucially the 905
        // peak must not pass the growth debounce.
        val current = KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)
        val reshow = listOf(0L to 200, 8L to 500, 16L to 800, 24L to 905, 54L to 824)
        assertEquals(
            emptyList<KeyboardReservation>(),
            simulatePersistence(reshow, settledHoldMs = 2000, initial = current),
        )
    }

    @Test
    fun firstOpenSettledHeight_persistsOnceAfterTheClimb() {
        // No applicable reservation yet: the climbing intermediate heights each
        // re-key (and cancel) the debounce; only the height the keyboard rests
        // at is persisted, exactly once.
        val current = KeyboardReservation(0, null, KeyboardReservationSource.AnimationTarget)
        val open = listOf(0L to 200, 8L to 500, 16L to 800, 24L to 824)
        assertEquals(
            listOf(KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)),
            simulatePersistence(open, settledHoldMs = 2000, initial = current),
        )
    }

    @Test
    fun settledShrinkBelowDebounce_isNotPersisted() {
        // A shorter height that the keyboard holds for less than the (longer)
        // shrink window is a transient dip, not a real shrink, and must not
        // pull the reservation down.
        val current = KeyboardReservation(824, config, KeyboardReservationSource.VisibleIme)
        // Dips to 768 for 300ms (< SHRINK_MS) then returns to 824.
        val dip = listOf(0L to 768, 300L to 824)
        assertEquals(
            emptyList<KeyboardReservation>(),
            simulatePersistence(dip, settledHoldMs = 2000, initial = current),
        )
    }

    /**
     * Faithful model of `LaunchedEffect(imeBottomPx, …) { delay(window); resolve }`:
     * each distinct visible-IME bottom inset re-keys the effect and cancels any
     * pending delay, so a height becomes authoritative only when the keyboard
     * holds it — no further change — for its debounce window. Growth uses
     * [GROWTH_MS]; a shrink waits the longer [SHRINK_MS]. The per-height
     * decision is the production [resolveKeyboardReservation]; only the
     * cancel-on-change timing is modelled here. The debounce values mirror
     * `IME_GROWTH_DEBOUNCE_MS` / `IME_SHRINK_DEBOUNCE_MS` in `TypeLauncherApp`.
     */
    private fun simulatePersistence(
        changePoints: List<Pair<Long, Int>>,
        settledHoldMs: Long,
        initial: KeyboardReservation,
    ): List<KeyboardReservation> {
        val persisted = mutableListOf<KeyboardReservation>()
        var current = initial
        changePoints.forEachIndexed { index, (timeMs, bottomPx) ->
            val nextChangeMs = changePoints.getOrNull(index + 1)?.first ?: (timeMs + settledHoldMs)
            val shrinking = current.configFingerprint == config && bottomPx < current.bottomPx
            val window = if (shrinking) SHRINK_MS else GROWTH_MS
            if (nextChangeMs - timeMs >= window) {
                resolveKeyboardReservation(bottomPx, config, current)?.let {
                    persisted += it
                    current = it
                }
            }
        }
        return persisted
    }

    private companion object {
        const val GROWTH_MS = 250L
        const val SHRINK_MS = 600L
    }
}
