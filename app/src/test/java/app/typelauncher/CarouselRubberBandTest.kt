package app.typelauncher

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarScrollReservationTest {
    private val strip = Rect(left = 0f, top = 100f, right = 400f, bottom = 160f)

    private fun region(canScroll: (Float) -> Boolean) = BarScrollRegion(
        boundsInRoot = strip,
        canScrollInDirection = canScroll,
    )

    @Test
    fun dragOnStripThatCanScrollIsReservedForBar() {
        assertTrue(
            shouldReserveGestureForBar(
                isBarOpen = true,
                region = region { true },
                downPosition = Offset(200f, 130f),
                rawDragX = -40f,
            ),
        )
    }

    @Test
    fun dragOutsideStripPagesEvenWhenBarCanScroll() {
        // Above the strip (the card's top padding) is page territory.
        assertFalse(
            shouldReserveGestureForBar(
                isBarOpen = true,
                region = region { true },
                downPosition = Offset(200f, 80f),
                rawDragX = -40f,
            ),
        )
    }

    @Test
    fun dragOnStripAtEdgeInThatDirectionPages() {
        // canScrollInDirection returns false once the strip is at its edge for
        // the finger's direction, so the swipe falls through to paging.
        assertFalse(
            shouldReserveGestureForBar(
                isBarOpen = true,
                region = region { false },
                downPosition = Offset(200f, 130f),
                rawDragX = -40f,
            ),
        )
    }

    @Test
    fun noReservationWhenBarClosed() {
        assertFalse(
            shouldReserveGestureForBar(
                isBarOpen = false,
                region = region { true },
                downPosition = Offset(200f, 130f),
                rawDragX = -40f,
            ),
        )
    }

    @Test
    fun noReservationWhenRegionMissing() {
        assertFalse(
            shouldReserveGestureForBar(
                isBarOpen = true,
                region = null,
                downPosition = Offset(200f, 130f),
                rawDragX = -40f,
            ),
        )
    }
}

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
