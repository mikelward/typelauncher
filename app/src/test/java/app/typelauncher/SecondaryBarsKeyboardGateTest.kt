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
    fun secondaryBarsHiddenByDefault_onHomeEntryBeforeKeyboardSeen() {
        // The core requirement: with auto-show on, the tray does not render on a
        // Home entry (cold start / resume / carousel return) until the user has
        // had the keyboard and dismissed it. Nothing forced open, keyboard not
        // yet seen this presence → hidden.
        assertFalse(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = false,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = false,
                isForcedOpen = false,
            ),
        )
    }

    @Test
    fun secondaryBarsVisible_afterKeyboardSeenAndDismissed() {
        // User had the keyboard this Home presence and dismissed it (not showing
        // now) → the tray fills the freed slot.
        assertTrue(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = false,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = true,
                isForcedOpen = false,
            ),
        )
    }

    @Test
    fun secondaryBarsVisible_whenForcedOpen_evenBeforeKeyboardSeen() {
        // A drag-opened bar shows immediately, regardless of the keyboard wait.
        assertTrue(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = false,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = false,
                isForcedOpen = true,
            ),
        )
    }

    @Test
    fun secondaryBarsHidden_whileKeyboardShowing_evenAfterSeen() {
        assertFalse(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = true,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = true,
                isForcedOpen = false,
            ),
        )
    }

    @Test
    fun secondaryBarsHidden_whileKeyboardShowing_evenWhenForcedOpen() {
        assertFalse(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = true,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = true,
                isForcedOpen = true,
            ),
        )
    }

    @Test
    fun secondaryBarsHidden_whileCarouselTransitioning() {
        assertFalse(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = true,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = false,
                isCarouselTransitioning = true,
                keyboardSeenThisHomePresence = true,
                isForcedOpen = false,
            ),
        )
    }

    @Test
    fun secondaryBarsHidden_whenNotRoutedToKeyboardSlot() {
        // Auto-show off ⇒ no reserved keyboard slot ⇒ tray never renders.
        assertFalse(
            areSecondaryBarsVisible(
                secondaryBarsRouteToKeyboardTray = false,
                isHomeDestination = true,
                isKeyboardShowingOrAnimatingIn = false,
                isCarouselTransitioning = false,
                keyboardSeenThisHomePresence = true,
                isForcedOpen = true,
            ),
        )
    }
}
