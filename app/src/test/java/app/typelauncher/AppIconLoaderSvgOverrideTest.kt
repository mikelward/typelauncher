package app.typelauncher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Process
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A user-supplied SVG icon override that declares an intrinsic size but no
 * `viewBox` — a common exporter output — must fill the tile. AndroidSVG only
 * rescales a document into the render viewport when it carries a viewBox, so
 * without one the paths stay in their original user units and the art renders
 * tiny in the tile corner. `renderSvgToBitmap` seeds a viewBox from the
 * intrinsic size so the art scales edge to edge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppIconLoaderSvgOverrideTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun resetLoader() {
        AppIconLoader.iconTheme = IconTheme.Default
        AppIconLoader.setThemedIconPalette(context, isDark = false)
        AppIconLoader.evictAll()
    }

    @Test
    fun svgWithoutViewBoxFillsTheTile() {
        // A full-bleed red square in 0..24 user units, with an intrinsic size
        // but no viewBox. Before the fix only a ~24px patch in the corner is
        // red and the rest of the 96px tile is transparent.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24">
              <rect x="0" y="0" width="24" height="24" fill="#FF0000"/>
            </svg>
        """.trimIndent()
        val app = appWithSvgOverride("svg.noviewbox", svg)

        val bitmap = runBlocking { AppIconLoader.load(context, app, SIZE_PX) }?.asAndroidBitmap()

        assertNotNull("an SVG override must produce a tile", bitmap)
        // The whole tile is red — center and the far corner, not just near 0,0.
        assertEquals("center must be opaque red", Color.RED, bitmap!!.getPixel(SIZE_PX / 2, SIZE_PX / 2))
        assertEquals("far corner must be opaque red", Color.RED, bitmap.getPixel(SIZE_PX - 3, SIZE_PX - 3))
    }

    private fun appWithSvgOverride(packageName: String, svgContent: String): InstalledApp {
        val file = File(context.cacheDir, "$packageName.svg")
        file.writeText(svgContent)
        return InstalledApp(
            name = packageName,
            packageName = packageName,
            launchIntent = Intent(),
            user = Process.myUserHandle(),
            isWorkApp = false,
            launchWithLauncherApps = false,
            customIconPath = file.absolutePath,
        )
    }

    private companion object {
        const val SIZE_PX = 96
    }
}
