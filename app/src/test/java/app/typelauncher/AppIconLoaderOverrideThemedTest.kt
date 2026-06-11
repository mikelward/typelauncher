package app.typelauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Covers user-supplied custom icon overrides being forced monochrome under the
 * Monochrome icon theme, so an app the user edited matches the rest of the
 * forced-monochrome grid instead of staying full-color. Under the Default theme
 * the override is returned as the user chose it, untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconLoaderOverrideThemedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun resetLoader() {
        // AppIconLoader is process-wide and Robolectric doesn't reset it between
        // methods, so restore the production cold-start defaults sibling tests rely on.
        AppIconLoader.iconTheme = IconTheme.Default
        AppIconLoader.setThemedIconPalette(context, isDark = false)
        AppIconLoader.evictAll()
    }

    @Test
    fun customOverrideIsForcedMonochromeUnderMonochromeTheme() {
        val app = appWithOverride("override.themed", centeredSquarePng("override_themed"))
        AppIconLoader.iconTheme = IconTheme.Monochrome
        AppIconLoader.setThemedIconPalette(
            IconNormalizer.ThemedIconColors(plate = PLATE, glyph = GLYPH),
            isDark = false,
        )

        val bitmap = runBlocking { AppIconLoader.load(context, app, SIZE_PX) }?.asAndroidBitmap()

        assertNotNull("override must still produce a tile under Monochrome", bitmap)
        // The override's white square silhouette becomes the glyph on the plate
        // fill — never its original white art.
        assertColorClose(GLYPH, bitmap!!.getPixel(SIZE_PX / 2, SIZE_PX / 2))
        assertColorClose(PLATE, bitmap.getPixel(2, 2))
    }

    @Test
    fun customOverrideKeepsChosenArtUnderDefaultTheme() {
        val app = appWithOverride("override.default", centeredSquarePng("override_default"))
        AppIconLoader.iconTheme = IconTheme.Default

        val bitmap = runBlocking { AppIconLoader.load(context, app, SIZE_PX) }?.asAndroidBitmap()

        assertNotNull(bitmap)
        // The user's art is returned untouched: the center stays white and is
        // never recolored to a glyph/plate.
        assertColorClose(Color.WHITE, bitmap!!.getPixel(SIZE_PX / 2, SIZE_PX / 2))
    }

    private fun appWithOverride(packageName: String, iconPath: String): InstalledApp = InstalledApp(
        name = packageName,
        packageName = packageName,
        launchIntent = Intent(),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = false,
        customIconPath = iconPath,
    )

    /**
     * Writes a centered white square on transparency to a PNG in the cache dir and
     * returns its path — a custom-icon override whose silhouette is meaningful (the
     * center is opaque, the corners transparent) so themed vs untouched rendering
     * is distinguishable by pixel.
     */
    private fun centeredSquarePng(name: String): String {
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val inset = SIZE_PX / 4f
        Canvas(bitmap).drawRect(inset, inset, SIZE_PX - inset, SIZE_PX - inset, paint)
        val file = File(context.cacheDir, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    private fun assertColorClose(expected: Int, actual: Int) {
        val tolerance = 12
        assertTrue(
            "expected #${Integer.toHexString(expected)} but was #${Integer.toHexString(actual)}",
            kotlin.math.abs(Color.alpha(expected) - Color.alpha(actual)) <= tolerance &&
                kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
                kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
                kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance,
        )
    }

    private companion object {
        const val SIZE_PX = 100
        val PLATE = 0xFF112233.toInt()
        val GLYPH = 0xFF445566.toInt()
    }
}
