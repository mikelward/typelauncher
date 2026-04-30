package app.typelauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.view.View
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
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
        assertColorNear(
            message = "blue background remains visible above ime",
            expected = Color.BLUE,
            actual = screenshot.getPixel(screenshot.width / 2, (imeTop - dpToPx(8)).coerceAtLeast(0)),
        )
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

        assertEquals(root.width, hintVisibleScreenshot.width)
        assertEquals(root.height, hintVisibleScreenshot.height)
        assertEquals(root.width, typedScreenshot.width)
        assertEquals(root.height, typedScreenshot.height)
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

    private fun buildActivityWithFakeLauncherApps(): ActivityController<MainActivity> {
        seedFakeLauncherApps()
        return Robolectric.buildActivity(MainActivity::class.java).setup()
    }

    private fun seedFakeLauncherApps() {
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packageManager = shadowOf(org.robolectric.RuntimeEnvironment.getApplication().packageManager)
        val labels = listOf("Browser", "Calculator", "Calendar", "Settings")
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

    private fun assertColorNear(message: String, expected: Int, actual: Int) {
        val tolerance = 16
        assertTrue(
            "$message: expected ${expected.toHex()} but was ${actual.toHex()}",
            kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    private fun Int.toHex(): String = "#%08X".format(this)
}
