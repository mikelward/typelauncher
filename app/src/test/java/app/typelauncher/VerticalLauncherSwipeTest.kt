package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalLauncherSwipeTest {
    private fun swipe(
        isUp: Boolean,
        isRecentsOpen: Boolean = false,
        isEditingHomeWidgets: Boolean = false,
        isHome: Boolean = true,
    ) = resolveVerticalLauncherSwipe(
        isHome = isHome,
        isRecentsOpen = isRecentsOpen,
        isEditingHomeWidgets = isEditingHomeWidgets,
        isUp = isUp,
    )

    @Test
    fun homeBarStateMachine() {
        assertEquals(VerticalLauncherSwipe.OpenRecents, swipe(isUp = true))
        assertEquals(VerticalLauncherSwipe.ShowKeyboard, swipe(isUp = true, isRecentsOpen = true))
        assertEquals(VerticalLauncherSwipe.SystemShade, swipe(isUp = false))
        assertEquals(VerticalLauncherSwipe.CloseRecents, swipe(isUp = false, isRecentsOpen = true))
    }

    @Test
    fun offHomePullUpDoesNothingAndPullDownOpensTheShade() {
        assertEquals(VerticalLauncherSwipe.None, swipe(isUp = true, isHome = false))
        assertEquals(VerticalLauncherSwipe.SystemShade, swipe(isUp = false, isHome = false, isRecentsOpen = true))
    }

    @Test
    fun editingHomeWidgetsLeavesTheHiddenRecentsBarAlone() {
        // The bar is hidden while editing, so a swipe must not open or close it
        // behind the user's back: Done brings it back exactly as it was.
        for (open in listOf(false, true)) {
            assertEquals(
                VerticalLauncherSwipe.None,
                swipe(isUp = true, isRecentsOpen = open, isEditingHomeWidgets = true),
            )
            assertEquals(
                VerticalLauncherSwipe.SystemShade,
                swipe(isUp = false, isRecentsOpen = open, isEditingHomeWidgets = true),
            )
        }
    }
}
