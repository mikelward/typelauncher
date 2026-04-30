package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.BAKLAVA])
class MainActivityScreenshotTest {
    @Test
    fun fullScreenScreenshot_showsBackgroundSearchBarAndKeyboardOpenState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rootView = activity.findViewById<View>(android.R.id.content)
                rootView.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY),
                )
                rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

                val searchInput = activity.findViewById<EditText>(R.id.app_search_input)
                assertTrue(searchInput.hasFocus())
                val inputMethodManager =
                    activity.getSystemService(InputMethodManager::class.java)
                val shadowInputMethodManager = Shadows.shadowOf(inputMethodManager)
                assertTrue("Expected keyboard to be visible", shadowInputMethodManager.isSoftInputVisible())

                val screenshot = captureBitmap(rootView)
                saveScreenshot(screenshot, "main-activity-fullscreen-keyboard-open.png")

                val centerPixel = screenshot.getPixel(screenshot.width / 2, screenshot.height / 2)
                assertColorClose(
                    expected = Color.BLUE,
                    actual = centerPixel,
                    tolerance = 20,
                    message = "Expected blue background to be visible in screenshot center",
                )

                val searchBarPixel = screenshot.getPixel(
                    searchInput.left + (searchInput.width / 2),
                    searchInput.top + (searchInput.height / 2),
                )
                assertColorClose(
                    expected = Color.WHITE,
                    actual = searchBarPixel,
                    tolerance = 20,
                    message = "Expected white search bar to be visible in screenshot",
                )
            }
        }
    }

    private fun captureBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private fun saveScreenshot(bitmap: Bitmap, fileName: String) {
        val context = RuntimeEnvironment.getApplication()
        val outputDirectory = File(context.cacheDir, "test-screenshots").apply { mkdirs() }
        val outputFile = File(outputDirectory, fileName)
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("Screenshot was not written", outputFile.exists())
    }

    private fun assertColorClose(expected: Int, actual: Int, tolerance: Int, message: String) {
        val expectedRed = Color.red(expected)
        val expectedGreen = Color.green(expected)
        val expectedBlue = Color.blue(expected)

        val actualRed = Color.red(actual)
        val actualGreen = Color.green(actual)
        val actualBlue = Color.blue(actual)

        assertTrue(
            "$message. Expected RGB($expectedRed,$expectedGreen,$expectedBlue), " +
                "but was RGB($actualRed,$actualGreen,$actualBlue)",
            kotlin.math.abs(expectedRed - actualRed) <= tolerance &&
                kotlin.math.abs(expectedGreen - actualGreen) <= tolerance &&
                kotlin.math.abs(expectedBlue - actualBlue) <= tolerance,
        )
    }
}
