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
