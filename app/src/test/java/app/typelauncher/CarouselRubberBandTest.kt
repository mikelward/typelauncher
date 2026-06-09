package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class LauncherGestureOwnerTest {
    @Test
    fun staysUndecidedUntilTouchSlopClears() {
        assertEquals(
            LauncherGestureOwner.Undecided,
            resolveLauncherGestureOwner(
                rawDragX = 8f,
                rawDragY = 0f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun horizontalDragWithoutChildConsumptionBelongsToCarousel() {
        assertEquals(
            LauncherGestureOwner.HorizontalLauncher,
            resolveLauncherGestureOwner(
                rawDragX = -20f,
                rawDragY = 4f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun horizontalDragConsumedAtStartBelongsToChildScrollable() {
        assertEquals(
            LauncherGestureOwner.ChildScrollable,
            resolveLauncherGestureOwner(
                rawDragX = -20f,
                rawDragY = 4f,
                consumedDragX = -8f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun horizontalDragWithinChildScrollWindowStaysUndecided() {
        // Past one touch slop but within the launcher's two-slop claim window,
        // a horizontal drag that hasn't yet seen child consumption must stay
        // Undecided so a nested horizontalScroll (the recents bar) has time to
        // clear its own slop and claim the gesture. Before the fix this
        // immediately resolved to HorizontalLauncher, stealing the drag from
        // the bar.
        assertEquals(
            LauncherGestureOwner.Undecided,
            resolveLauncherGestureOwner(
                rawDragX = -12f,
                rawDragY = 0f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun horizontalDragWithChildConsumptionWithinWindowBelongsToChildScrollable() {
        // Once the nested row reports consumption past childClaimSlop while
        // still inside the launcher's claim window, the child owns the gesture.
        assertEquals(
            LauncherGestureOwner.ChildScrollable,
            resolveLauncherGestureOwner(
                rawDragX = -14f,
                rawDragY = 0f,
                consumedDragX = -6f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun verticalDragWithinChildScrollWindowStaysUndecided() {
        // Same window on the vertical axis: a pull that hasn't yet seen child
        // consumption stays Undecided until it clears the two-slop threshold,
        // giving a vertically scrollable child (app list, widget) time to claim.
        assertEquals(
            LauncherGestureOwner.Undecided,
            resolveLauncherGestureOwner(
                rawDragX = 0f,
                rawDragY = -12f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun verticalDragWithoutChildConsumptionBelongsToLauncherPull() {
        assertEquals(
            LauncherGestureOwner.VerticalLauncher,
            resolveLauncherGestureOwner(
                rawDragX = 4f,
                rawDragY = 20f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun verticalDragConsumedAtStartBelongsToChildScrollable() {
        assertEquals(
            LauncherGestureOwner.ChildScrollable,
            resolveLauncherGestureOwner(
                rawDragX = 4f,
                rawDragY = -20f,
                consumedDragX = 0f,
                consumedDragY = -8f,
                touchSlopPx = 8f,
            ),
        )
    }

    @Test
    fun verticalDominantDiagonalDoesNotBelongToCarousel() {
        assertEquals(
            LauncherGestureOwner.VerticalLauncher,
            resolveLauncherGestureOwner(
                rawDragX = 20f,
                rawDragY = 80f,
                consumedDragX = 0f,
                consumedDragY = 0f,
                touchSlopPx = 8f,
            ),
        )
    }
}
