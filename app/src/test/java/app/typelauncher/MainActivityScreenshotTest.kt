package app.typelauncher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.BAKLAVA])
class MainActivityScreenshotTest {
    private val snapshotName = "main-activity-fullscreen-keyboard-open.png"

    @Test
    fun fullScreenScreenshot_showsBackgroundSearchBarAndKeyboardOpenState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val rootView = activity.findViewById<View>(android.R.id.content)
                rootView.measure(
                    View.MeasureSpec.makeMeasureSpec(SCREENSHOT_WIDTH, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SCREENSHOT_HEIGHT, View.MeasureSpec.EXACTLY),
                )
                rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

                val searchInput = activity.findViewById<EditText>(R.id.app_search_input)
                assertTrue(searchInput.hasFocus())
                val inputMethodManager =
                    activity.getSystemService(InputMethodManager::class.java)
                val shadowInputMethodManager = Shadows.shadowOf(inputMethodManager)
                assertTrue("Expected keyboard to be visible", shadowInputMethodManager.isSoftInputVisible())

                val screenshot = captureBitmap(rootView)
                val actualSnapshotFile = saveActualScreenshot(screenshot, snapshotName)
                val baselineSnapshotFile = saveOrGetBaselineSnapshot(screenshot, snapshotName)
                assertBitmapMatches(
                    baselineSnapshotFile = baselineSnapshotFile,
                    actualBitmap = screenshot,
                    actualSnapshotFile = actualSnapshotFile,
                )

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

    private fun saveActualScreenshot(bitmap: Bitmap, fileName: String): File {
        val outputDirectory = actualSnapshotDirectory().apply { mkdirs() }
        val outputFile = File(outputDirectory, fileName)
        FileOutputStream(outputFile).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        assertTrue("Screenshot was not written", outputFile.exists())
        return outputFile
    }

    private fun saveOrGetBaselineSnapshot(bitmap: Bitmap, fileName: String): File {
        val baselineDirectory = baselineSnapshotDirectory().apply { mkdirs() }
        val baselineFile = File(baselineDirectory, fileName)
        if (shouldRecordSnapshots()) {
            FileOutputStream(baselineFile).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
        }
        assertTrue(
            "Missing baseline snapshot. Run with -Ptypelauncher.screenshot.record=true to record it.",
            baselineFile.exists(),
        )
        return baselineFile
    }

    private fun assertBitmapMatches(
        baselineSnapshotFile: File,
        actualBitmap: Bitmap,
        actualSnapshotFile: File,
    ) {
        val baselineBitmap = BitmapFactory.decodeFile(baselineSnapshotFile.absolutePath)
        assertNotNull("Unable to decode baseline snapshot ${baselineSnapshotFile.absolutePath}", baselineBitmap)
        baselineBitmap!!
        assertEquals(
            "Snapshot width mismatch",
            baselineBitmap.width,
            actualBitmap.width,
        )
        assertEquals(
            "Snapshot height mismatch",
            baselineBitmap.height,
            actualBitmap.height,
        )

        for (y in 0 until actualBitmap.height) {
            for (x in 0 until actualBitmap.width) {
                val baselinePixel = baselineBitmap.getPixel(x, y)
                val actualPixel = actualBitmap.getPixel(x, y)
                if (baselinePixel != actualPixel) {
                    fail(
                        "Snapshot mismatch at ($x, $y). " +
                            "Expected baseline ${baselineSnapshotFile.absolutePath}, " +
                            "actual ${actualSnapshotFile.absolutePath}",
                    )
                }
            }
        }
    }

    private fun baselineSnapshotDirectory(): File =
        File(
            System.getProperty(
                SCREENSHOT_BASELINE_DIR_PROPERTY,
                "src/test/snapshots",
            ) ?: "src/test/snapshots",
        )

    private fun actualSnapshotDirectory(): File =
        File(
            System.getProperty(
                SCREENSHOT_ACTUAL_DIR_PROPERTY,
                "build/reports/screenshot-tests/actual",
            ) ?: "build/reports/screenshot-tests/actual",
        )

    private fun shouldRecordSnapshots(): Boolean =
        System.getProperty(SCREENSHOT_RECORD_PROPERTY, "false").toBoolean()

    companion object {
        private const val SCREENSHOT_WIDTH = 540
        private const val SCREENSHOT_HEIGHT = 1200
        private const val SCREENSHOT_RECORD_PROPERTY = "typelauncher.screenshot.record"
        private const val SCREENSHOT_BASELINE_DIR_PROPERTY = "typelauncher.screenshot.baselineDir"
        private const val SCREENSHOT_ACTUAL_DIR_PROPERTY = "typelauncher.screenshot.actualDir"
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
