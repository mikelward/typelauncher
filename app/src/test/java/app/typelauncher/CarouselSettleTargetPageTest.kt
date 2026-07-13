package app.typelauncher

// LauncherPage, LauncherScreen, CarouselTransitionState, and
// carouselSettleTargetPage all live in the flat `app.typelauncher` package
// (the model/ and ui/ directories are organizational, not subpackages), so
// they need no import from this same-package test.
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the settle-target decision that keeps a launcher-owned horizontal
 * flick from being silently dropped when it lands during an uncommitted
 * snap-back — the one settle window that previously had no queue-and-replay.
 */
class CarouselSettleTargetPageTest {
    private val homePage = LauncherPage(LauncherScreen.Home)

    @Test
    fun idleWithActiveSnapBackQueuesAgainstTheSettlingPage() {
        // The tail of an uncommitted snap-back: transition is Idle but the
        // settle animation is still running back to page 3. A flick here must
        // queue against 3 (and replay when the snap-back finishes), not drop.
        assertEquals(
            3,
            carouselSettleTargetPage(
                transition = CarouselTransitionState.Idle,
                isSettleAnimationActive = true,
                settledPage = 3,
            ),
        )
    }

    @Test
    fun idleWithNoAnimationDropsTheSwipe() {
        // A truly idle carousel: a swipe would have claimed outright rather
        // than reaching this path, so there is nothing to queue.
        assertNull(
            carouselSettleTargetPage(
                transition = CarouselTransitionState.Idle,
                isSettleAnimationActive = false,
                settledPage = 3,
            ),
        )
    }

    @Test
    fun userAnimatingReturnsItsInFlightTarget() {
        assertEquals(
            5,
            carouselSettleTargetPage(
                transition = CarouselTransitionState.UserAnimating(targetPage = 5, targetLauncherPage = homePage),
                isSettleAnimationActive = true,
                settledPage = 3,
            ),
        )
    }

    @Test
    fun externalAnimatingReturnsItsInFlightTarget() {
        assertEquals(
            2,
            carouselSettleTargetPage(
                transition = CarouselTransitionState.ExternalAnimating(targetPage = 2, targetLauncherPage = homePage),
                isSettleAnimationActive = true,
                settledPage = 3,
            ),
        )
    }

    @Test
    fun awaitingAckReturnsItsSettledPage() {
        assertEquals(
            7,
            carouselSettleTargetPage(
                transition = CarouselTransitionState.AwaitingAck(settledPage = 7, expectedPage = homePage),
                isSettleAnimationActive = true,
                settledPage = 3,
            ),
        )
    }
}
