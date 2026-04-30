package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRobolectricScreenshotTest {
    @Test
    fun screenshot_keyboardVisible_keepsSearchAndListAboveImeInset() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)
        assertTrue("search hint is visible when empty", search.text.isNullOrEmpty())

        val imeBottomInsetPx = dpToPx(320)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomInsetPx))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        layout(root)

        val screenshot = drawToBitmap(root)
        drawFakeKeyboardOverlay(
            screenshot = screenshot,
            imeTop = root.height - imeBottomInsetPx,
            label = "Keyboard (simulated)",
        )
        val file = screenshotOutputFile("main_activity_keyboard_visible_robolectric.png")
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { output ->
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        val imeTop = root.height - imeBottomInsetPx
        assertTrue("root is measured", root.height > 0)
        assertTrue("list has launcher app rows", list.adapter != null && list.adapter.count >= 4)
        assertTrue("list rendered rows", list.childCount > 0)
        assertTrue("search remains above ime", search.bottom <= imeTop)
        assertTrue("list remains above ime", list.bottom <= imeTop)
        assertTrue("list starts below search", list.top >= search.bottom)
    }

    @Test
    fun screenshot_keyboardHidden_rendersHintAndTypedState() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val root = activity.findViewById<View>(R.id.main_root)
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        assertTrue("search hint is visible when empty", search.text.isNullOrEmpty())
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 0))
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        layout(root)

        val hintVisibleScreenshot = drawToBitmap(root)
        val hintFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_robolectric.png")
        hintFile.parentFile?.mkdirs()
        hintFile.outputStream().buffered().use { output ->
            hintVisibleScreenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val hintCrop = cropAroundSearchField(hintVisibleScreenshot, search)
        val hintCropFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_search_crop_robolectric.png")
        hintCropFile.outputStream().buffered().use { output ->
            hintCrop.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("hint crop has some non-white pixels", hasNonWhitePixels(hintCrop))
        val hintFieldSnapshot = drawToBitmap(search)
        val hintFieldFile = screenshotOutputFile("main_activity_keyboard_hidden_hint_search_field_robolectric.png")
        hintFieldFile.outputStream().buffered().use { output ->
            hintFieldSnapshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }

        search.setText("settings")
        layout(root)
        val typedScreenshot = drawToBitmap(root)
        val typedFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_robolectric.png")
        typedFile.outputStream().buffered().use { output ->
            typedScreenshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        val typedCrop = cropAroundSearchField(typedScreenshot, search)
        val typedCropFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_search_crop_robolectric.png")
        typedCropFile.outputStream().buffered().use { output ->
            typedCrop.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("typed crop has some non-white pixels", hasNonWhitePixels(typedCrop))
        val typedFieldSnapshot = drawToBitmap(search)
        val typedFieldFile = screenshotOutputFile("main_activity_keyboard_hidden_typed_search_field_robolectric.png")
        typedFieldFile.outputStream().buffered().use { output ->
            typedFieldSnapshot.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        assertTrue("hint and typed crop differ", bitmapsDiffer(hintCrop, typedCrop))
        assertTrue("hint and typed field snapshots differ", bitmapsDiffer(hintFieldSnapshot, typedFieldSnapshot))

        assertEquals(root.width, hintVisibleScreenshot.width)
        assertEquals(root.height, hintVisibleScreenshot.height)
        assertEquals(root.width, typedScreenshot.width)
        assertEquals(root.height, typedScreenshot.height)
    }

    @Test
    fun typingInSearch_filtersInstalledAppsByNameSubstring() {
        val activity = buildActivityWithFakeLauncherApps().get()
        val search = activity.findViewById<EditText>(R.id.app_search_input)
        val list = activity.findViewById<ListView>(R.id.installed_apps_list)

        assertEquals(listOf("Browser", "Calculator", "Calendar", "Settings", "Type Launcher"), list.appNames())

        search.setText("settings")
        assertEquals(listOf("Settings"), list.appNames())

        search.setText("ca")
        assertEquals(listOf("Calculator", "Calendar"), list.appNames())

        search.setText("er")
        assertEquals(listOf("Browser", "Type Launcher"), list.appNames())

        search.setText("")
        assertEquals(listOf("Browser", "Calculator", "Calendar", "Settings", "Type Launcher"), list.appNames())
    }

    private fun layout(root: View) {
        val width = 1080
        val height = 2400
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)
        root.measure(widthSpec, heightSpec)
        root.layout(0, 0, width, height)
    }

    private fun drawToBitmap(root: View): Bitmap {
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        return bitmap
    }

    private fun drawFakeKeyboardOverlay(screenshot: Bitmap, imeTop: Int, label: String) {
        val top = imeTop.coerceIn(0, screenshot.height)
        val canvas = Canvas(screenshot)
        val keyboardPaint = Paint().apply {
            color = Color.rgb(232, 234, 237)
            style = Paint.Style.FILL
        }
        canvas.drawRect(
            0f,
            top.toFloat(),
            screenshot.width.toFloat(),
            screenshot.height.toFloat(),
            keyboardPaint,
        )
        val textPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = dpToPx(22).toFloat()
            isAntiAlias = true
        }
        canvas.drawText(
            label,
            dpToPx(24).toFloat(),
            (top + dpToPx(56)).toFloat(),
            textPaint,
        )
    }

    private fun cropAroundSearchField(bitmap: Bitmap, search: View): Bitmap {
        val horizontalPadding = dpToPx(8)
        val top = (search.top - dpToPx(8)).coerceAtLeast(0)
        val bottom = (search.bottom + dpToPx(24)).coerceAtMost(bitmap.height)
        val left = (search.left - horizontalPadding).coerceAtLeast(0)
        val right = (search.right + horizontalPadding).coerceAtMost(bitmap.width)
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    private fun hasNonWhitePixels(bitmap: Bitmap): Boolean {
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                if (!(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)) {
                    return true
                }
                x += 4
            }
            y += 4
        }
        return false
    }

    private fun bitmapsDiffer(first: Bitmap, second: Bitmap): Boolean {
        if (first.width != second.width || first.height != second.height) {
            return true
        }
        var y = 0
        while (y < first.height) {
            var x = 0
            while (x < first.width) {
                if (first.getPixel(x, y) != second.getPixel(x, y)) {
                    return true
                }
                x += 2
            }
            y += 2
        }
        return false
    }

    private fun buildActivityWithFakeLauncherApps(): ActivityController<MainActivity> {
        seedFakeLauncherApps()
        return Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    private fun seedFakeLauncherApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
        val labels = listOf("Browser", "Calculator", "Calendar", "Settings", "Type Launcher")
        labels.forEachIndexed { index, label ->
            val packageName = "app.typelauncher.fake$index"
            val resolveInfo = ResolveInfo().apply {
                nonLocalizedLabel = label
                activityInfo = ActivityInfo().apply {
                    this.packageName = packageName
                    name = "$packageName.LaunchActivity"
                }
            }
            @Suppress("DEPRECATION")
            packageManager.addResolveInfoForIntent(launcherIntent, resolveInfo)
        }
    }

    private fun screenshotOutputFile(name: String): File =
        File("build/reports/robolectric-screenshots/$name")

    private fun dpToPx(dp: Int): Int =
        (dp * 420f / 160f).toInt()

    private fun ListView.appNames(): List<String> {
        @Suppress("UNCHECKED_CAST")
        val appNamesAdapter = adapter as ArrayAdapter<String>
        return (0 until appNamesAdapter.count).map { index -> appNamesAdapter.getItem(index).orEmpty() }
    }

}
