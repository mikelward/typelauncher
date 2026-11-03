package app.typelauncher

import android.content.res.Configuration
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The keyboard stuck partway up after a quick swipe away and back.
 *
 * Leaving while the keyboard is still animating open leaves the window
 * requesting it, so the show request on the next return is skipped. Leaving
 * the launcher must withdraw the request so the next show is a real one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class MainActivityKeyboardStopTest {

    // Hidden API on the platform's InsetsController; public on the class,
    // so reflection reaches it without the stub jar exposing it.
    private fun WindowInsetsController.requestsKeyboard(): Boolean {
        val types = javaClass.getMethod("getRequestedVisibleTypes").invoke(this) as Int
        return types and WindowInsets.Type.ime() != 0
    }

    @Test
    fun leavingWithdrawsTheKeyboardRequestSoTheNextShowIsReal() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val insets = controller.get().window.insetsController!!
            insets.show(WindowInsets.Type.ime())
            assertTrue("precondition: the keyboard is requested", insets.requestsKeyboard())

            controller.pause().stop()

            assertFalse(
                "a stopped launcher must not keep requesting the keyboard",
                insets.requestsKeyboard(),
            )

            controller.restart().start().resume()
            insets.show(WindowInsets.Type.ime())
            assertTrue("the show on return must take effect", insets.requestsKeyboard())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun aConfigurationChangeKeepsTheKeyboardRequested() {
        // Rotating mid-search recreates the activity while it stays on
        // screen; hiding there would flicker the keyboard.
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val insets = controller.get().window.insetsController!!
            insets.show(WindowInsets.Type.ime())

            controller.configurationChange(
                Configuration(controller.get().resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_LANDSCAPE
                },
            )

            assertTrue(
                "a recreate must not withdraw the keyboard request",
                insets.requestsKeyboard(),
            )
        } finally {
            controller.destroy()
        }
    }
}
