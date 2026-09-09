package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [isKeyboardShowingOrAnimatingIn], which decides whether the
 * keyboard is on screen / animating in. It now drives the reserved-bottom-space
 * collapse: the space stays reserved while the keyboard is showing or animating
 * in (so the layout does not reflow on view open) and only releases once the
 * keyboard is gone.
 *
 * The regression this guards: when the launcher opens and the keyboard grows,
 * `WindowInsets.isImeVisible` can momentarily still report `false` while the
 * keyboard is already animating up — most noticeably under system gesture
 * navigation. A gate that looked at `imeVisible` alone would collapse the
 * reservation for those frames and reflow the layout. The
 * [keyboardAnimatingIn_whileVisibilityFlagStillFalse_countsAsShowing] case
 * pins the fix: an animation target above the nav bar means the keyboard is
 * coming even before the visibility flag flips.
 */
class SecondaryBarsKeyboardGateTest {
    private val navBottomPx = 126

    @Test
    fun keyboardFullyHidden_isNotShowing() {
        // Stable hidden keyboard: visibility flag false, target collapsed to
        // the nav bar. The tray is free to render.
        assertFalse(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = false,
                imeTargetBottomPx = navBottomPx,
                navBottomPx = navBottomPx,
            ),
        )
    }

    @Test
    fun keyboardFullyHidden_zeroTarget_isNotShowing() {
        assertFalse(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = false,
                imeTargetBottomPx = 0,
                navBottomPx = navBottomPx,
            ),
        )
    }

    @Test
    fun keyboardVisible_isShowing() {
        assertTrue(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = true,
                imeTargetBottomPx = 905,
                navBottomPx = navBottomPx,
            ),
        )
    }

    @Test
    fun keyboardAnimatingIn_whileVisibilityFlagStillFalse_countsAsShowing() {
        // The flicker case: the keyboard is growing — its animation target is
        // already the full keyboard height — but the visibility flag has not
        // flipped yet. The gate must treat this as "showing" so the tray stays
        // hidden through the whole grow animation. The old `!imeVisible`-only
        // gate would have rendered the bars here.
        assertTrue(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = false,
                imeTargetBottomPx = 905,
                navBottomPx = navBottomPx,
            ),
        )
    }

    @Test
    fun keyboardAnimatingOut_targetBackBelowNav_isNotShowing() {
        // On the way out the animation target drops below the nav bar on the
        // first frame, so the reserved space is free to collapse as the keyboard
        // vacates it.
        assertFalse(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = false,
                imeTargetBottomPx = navBottomPx,
                navBottomPx = navBottomPx,
            ),
        )
    }
}
