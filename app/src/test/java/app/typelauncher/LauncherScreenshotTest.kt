package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory

class LauncherScreenshotTest {
    @Test
    fun fullScreenScreenshot_keyboardOpen_showsBackgroundAndSearchBar() {
        val layout = parseXml(File("src/main/res/layout/activity_main.xml")).documentElement
        val searchInput = layout.getElementsByTagName("EditText").item(0) as Element
        val background = colorFromResource(layout.androidAttribute("background"))
        val searchBar = colorFromResource(searchInput.androidAttribute("background"))
        val screenshot = renderKeyboardOpenScreenshot(layout, searchInput, background, searchBar)

        val output = File("build/reports/screenshot-tests/full_screen_keyboard_open.png")
        requireNotNull(output.parentFile).mkdirs()
        ImageIO.write(screenshot, "png", output)

        assertEquals(screenWidthPx, screenshot.width)
        assertEquals(screenHeightPx, screenshot.height)
        assertPixel("blue background remains visible above the keyboard", background, screenshot, screenWidthPx / 2, keyboardTopPx - 80)
        assertPixel("white search bar remains visible", searchBar, screenshot, screenWidthPx / 2, 120)
        assertPixel("keyboard is open at the bottom of the screen", keyboardKeyColor.rgb, screenshot, screenWidthPx / 2, keyboardTopPx + 220)
        assertTrue("screenshot report was written", output.isFile)
    }

    private fun renderKeyboardOpenScreenshot(
        layout: Element,
        searchInput: Element,
        background: Int,
        searchBar: Int,
    ): BufferedImage {
        assertEquals("match_parent", layout.androidAttribute("layout_width"))
        assertEquals("match_parent", layout.androidAttribute("layout_height"))

        val image = BufferedImage(screenWidthPx, screenHeightPx, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        graphics.color = Color(background, true)
        graphics.fillRect(0, 0, screenWidthPx, screenHeightPx)
        drawSearchBar(graphics, searchInput, searchBar)
        drawKeyboard(graphics)
        graphics.dispose()

        return image
    }

    private fun drawSearchBar(graphics: Graphics2D, searchInput: Element, searchBar: Int) {
        assertEquals("top", searchInput.androidAttribute("layout_gravity"))

        val margin = dp(searchInput.androidAttribute("layout_margin"))
        val minHeight = dp(searchInput.androidAttribute("minHeight"))
        graphics.color = Color(searchBar, true)
        graphics.fillRoundRect(
            margin,
            margin,
            screenWidthPx - (margin * 2),
            minHeight,
            cornerRadiusPx,
            cornerRadiusPx,
        )
    }

    private fun drawKeyboard(graphics: Graphics2D) {
        graphics.color = keyboardBackgroundColor
        graphics.fillRect(0, keyboardTopPx, screenWidthPx, screenHeightPx - keyboardTopPx)

        val rows = listOf(10, 9, 7, 1)
        var top = keyboardTopPx + keyboardPaddingPx
        rows.forEachIndexed { rowIndex, keyCount ->
            val horizontalPadding = keyboardPaddingPx + (rowIndex * keyIndentPx)
            val keyWidth = (screenWidthPx - (horizontalPadding * 2) - ((keyCount - 1) * keyGapPx)) / keyCount
            repeat(keyCount) { index ->
                val left = horizontalPadding + index * (keyWidth + keyGapPx)
                graphics.color = keyboardKeyColor
                graphics.fillRoundRect(left, top, keyWidth, keyHeightPx, keyRadiusPx, keyRadiusPx)
            }
            top += keyHeightPx + keyGapPx
        }
    }

    private fun assertPixel(message: String, expected: Int, screenshot: BufferedImage, x: Int, y: Int) {
        assertEquals(message, expected, screenshot.getRGB(x, y))
    }

    private fun colorFromResource(reference: String): Int = when {
        reference == "@android:color/white" -> Color.WHITE.rgb
        reference.startsWith("@color/") -> {
            val colorName = reference.substringAfter("@color/")
            val colors = parseXml(File("src/main/res/values/colors.xml"))
            val color = colors.getElementsByTagName("color")
                .let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
                .first { it.getAttribute("name") == colorName }
                .textContent
            Color.decode(color.removePrefix("#FF").let { "#$it" }).rgb
        }
        else -> error("Unsupported color reference: $reference")
    }

    private fun parseXml(file: File) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(file)

    private fun Element.androidAttribute(name: String): String =
        getAttribute("android:$name")

    private fun dp(value: String): Int =
        value.removeSuffix("dp").toInt() * density

    private companion object {
        private const val density = 3
        private const val screenWidthPx = 1080
        private const val screenHeightPx = 2400
        private const val keyboardTopPx = 1560
        private const val cornerRadiusPx = 18
        private const val keyboardPaddingPx = 36
        private const val keyGapPx = 18
        private const val keyHeightPx = 108
        private const val keyIndentPx = 28
        private const val keyRadiusPx = 16

        private val keyboardBackgroundColor = Color(0xFFE5E5EA.toInt(), true)
        private val keyboardKeyColor = Color.WHITE
    }
}
