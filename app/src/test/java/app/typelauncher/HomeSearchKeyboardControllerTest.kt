package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSearchKeyboardControllerTest {
    private var hasFocus = true
    private var hasWindowFocus = true
    private var focusRequests = 0
    private var showRequests = 0
    private val posted = mutableListOf<() -> Unit>()
    private val delayed = mutableListOf<() -> Unit>()
    private val controller = HomeSearchKeyboardController(
        requestSearchFocus = {
            hasFocus = true
            focusRequests++
        },
        searchHasFocus = { hasFocus },
        searchHasWindowFocus = { hasWindowFocus },
        postToSearch = { posted += it },
        postDelayedToSearch = { block, _ -> delayed += block },
        showSearchKeyboard = { showRequests++ },
    )

    @Test
    fun showKeyboard_requestsFocusAndShowsWhenSearchHasWindowFocus() {
        hasFocus = false

        controller.showKeyboard()
        runPosted()

        assertEquals(1, focusRequests)
        assertEquals(1, showRequests)
    }

    @Test
    fun showKeyboard_waitsForWindowFocusBeforeShowingKeyboard() {
        hasWindowFocus = false

        controller.showKeyboard()
        runPosted()

        assertEquals(1, focusRequests)
        assertEquals(0, showRequests)
    }

    @Test
    fun hiddenImeWhileSearchFocused_schedulesSingleKeyboardReshow() {
        controller.onImeVisibilityChanged(imeVisible = false)
        controller.onImeVisibilityChanged(imeVisible = false)

        assertEquals(1, delayed.size)

        runDelayed()
        runPosted()

        assertEquals(1, showRequests)
    }

    @Test
    fun hiddenImeWhileSearchNotFocused_refocusesAndShowsKeyboard() {
        hasFocus = false

        controller.onSearchFocusChanged(hasFocus = false)
        runPosted()

        assertEquals(1, focusRequests)
        assertEquals(1, showRequests)
    }

    @Test
    fun visibleIme_doesNotScheduleKeyboardReshow() {
        controller.onImeVisibilityChanged(imeVisible = true)

        assertEquals(0, delayed.size)
        assertEquals(0, showRequests)
    }

    private fun runPosted() {
        while (posted.isNotEmpty()) {
            posted.removeAt(0).invoke()
        }
    }

    private fun runDelayed() {
        while (delayed.isNotEmpty()) {
            delayed.removeAt(0).invoke()
        }
    }
}
