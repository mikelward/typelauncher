package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Environment
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class KeyboardScreenshotTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private var originalShowImeWithHardKeyboard: String? = null

    @After
    fun restoreKeyboardSetting() {
        originalShowImeWithHardKeyboard?.let {
            device.executeShellCommand("settings put secure show_ime_with_hard_keyboard $it")
        }
    }

    @Test
    fun fullScreenScreenshot_keyboardOpen_showsBackgroundAndSearchBar() {
        showSoftKeyboardEvenWhenHardwareKeyboardIsConnected()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var launchedActivity: MainActivity? = null
            var searchBarBounds = Bounds()

            scenario.onActivity { activity ->
                launchedActivity = activity
                val input = activity.findViewById<EditText>(R.id.app_search_input)
                input.requestFocus()
                activity.getSystemService<InputMethodManager>()
                    ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                searchBarBounds = input.boundsOnScreen()
            }

            val imeTop = waitForIme(requireNotNull(launchedActivity))
            val screenshotFile = screenshotOutputFile()
            assertTrue("full-screen screenshot was captured", device.takeScreenshot(screenshotFile))

            val screenshot = android.graphics.BitmapFactory.decodeFile(screenshotFile.path)
            assertEquals(device.displayWidth, screenshot.width)
            assertEquals(device.displayHeight, screenshot.height)
            assertColorNear("blue background remains visible", Color.BLUE, screenshot.pixelAt(screenshot.width / 2, imeTop - 80))
            assertColorNear("white search bar remains visible", Color.WHITE, screenshot.pixelAt(searchBarBounds.centerX, searchBarBounds.centerY))
            assertTrue("IME covers the bottom of the display", imeTop < screenshot.height)
            assertTrue("search bar is above the IME", searchBarBounds.bottom < imeTop)
        }
    }

    private fun showSoftKeyboardEvenWhenHardwareKeyboardIsConnected() {
        originalShowImeWithHardKeyboard = device.executeShellCommand("settings get secure show_ime_with_hard_keyboard")
            .trim()
        device.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1")
    }

    private fun waitForIme(activity: MainActivity): Int {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < imeTimeoutMillis) {
            var imeTop = 0
            var imeVisible = false
            activity.runOnUiThread {
                val rootView = activity.window.decorView
                val insets = rootView.rootWindowInsets
                imeVisible = insets?.isVisible(WindowInsets.Type.ime()) == true
                if (imeVisible) {
                    imeTop = rootView.height - insets.getInsets(WindowInsets.Type.ime()).bottom
                }
            }
            instrumentation.waitForIdleSync()
            if (imeVisible) {
                return imeTop
            }
            Thread.sleep(100)
        }
        assertTrue("IME became visible", false)
        return 0
    }

    private fun screenshotOutputFile(): File {
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(instrumentation.targetContext.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "screenshots")
        } else {
            File(instrumentation.targetContext.filesDir, "screenshots")
        }
        directory.mkdirs()
        return File(directory, "full_screen_keyboard_open.png")
    }

    private fun EditText.boundsOnScreen(): Bounds {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Bounds(
            left = location[0],
            top = location[1],
            right = location[0] + width,
            bottom = location[1] + height,
        )
    }

    private fun Bitmap.pixelAt(x: Int, y: Int): Int =
        getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))

    private fun assertColorNear(message: String, expected: Int, actual: Int) {
        val tolerance = 12
        assertTrue(
            "$message: expected ${expected.toHex()} but was ${actual.toHex()}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    private fun Int.toHex(): String = "#%08X".format(this)

    private data class Bounds(
        val left: Int = 0,
        val top: Int = 0,
        val right: Int = 0,
        val bottom: Int = 0,
    ) {
        val centerX: Int = (left + right) / 2
        val centerY: Int = (top + bottom) / 2
    }

    private companion object {
        private const val imeTimeoutMillis = 5_000L
    }
}
