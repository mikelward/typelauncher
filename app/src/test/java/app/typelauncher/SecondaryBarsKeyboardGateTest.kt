package app.typelauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [isKeyboardShowingOrAnimatingIn], the gate that keeps the
 * recents / notifications tray from rendering while the keyboard is on screen
 * or animating into view.
 *
 * The regression this guards: when the launcher opens and the keyboard grows,
 * `WindowInsets.isImeVisible` can momentarily still report `false` while the
 * keyboard is already animating up — most noticeably under system gesture
 * navigation. The old gate looked at `imeVisible` alone, so it rendered the
 * tray for those frames and flickered the bars in. The
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
        // first frame, so the tray is free to fill the space the keyboard is
        // vacating.
        assertFalse(
            isKeyboardShowingOrAnimatingIn(
                imeVisible = false,
                imeTargetBottomPx = navBottomPx,
                navBottomPx = navBottomPx,
            ),
        )
    }

    @Test
    fun reArmedGeneration_isWaitingForKeyboard() {
        // The regression: returning to the launcher bumps the generation past the
        // last resolved one. The wait must turn back on so the tray stays hidden
        // until the re-shown keyboard appears, instead of flashing into the slot.
        assertTrue(
            isWaitingForAutoKeyboard(
                secondaryBarsRouteToKeyboardTray = true,
                isKeyboardAutoShown = true,
                resolvedGeneration = 0,
                currentGeneration = 1,
            ),
        )
    }

    @Test
    fun resolvedGenerationCaughtUp_isNotWaiting() {
        // Keyboard seen (or timed out) for the current generation — the tray is free
        // to fill the slot once the user dismisses the keyboard.
        assertFalse(
            isWaitingForAutoKeyboard(
                secondaryBarsRouteToKeyboardTray = true,
                isKeyboardAutoShown = true,
                resolvedGeneration = 1,
                currentGeneration = 1,
            ),
        )
    }

    @Test
    fun autoShowOff_isNotWaiting() {
        assertFalse(
            isWaitingForAutoKeyboard(
                secondaryBarsRouteToKeyboardTray = true,
                isKeyboardAutoShown = false,
                resolvedGeneration = 0,
                currentGeneration = 1,
            ),
        )
    }

    @Test
    fun trayNotRoutedToKeyboardSlot_isNotWaiting() {
        assertFalse(
            isWaitingForAutoKeyboard(
                secondaryBarsRouteToKeyboardTray = false,
                isKeyboardAutoShown = true,
                resolvedGeneration = 0,
                currentGeneration = 1,
            ),
        )
    }
}
