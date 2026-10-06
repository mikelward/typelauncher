package app.typelauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetPickerIconCacheTest {
    @Test
    fun aDrawableIsRasterizedOnceHoweverOftenItsRowRecomposes() {
        val icon = CountingDrawable()
        val cache = WidgetPickerIconCache()

        val first = cache.bitmapFor(icon)
        val again = cache.bitmapFor(icon)

        assertSame(first, again)
        assertEquals(1, icon.draws)
    }

    @Test
    fun distinctDrawablesGetTheirOwnBitmaps() {
        val cache = WidgetPickerIconCache()
        val one = cache.bitmapFor(CountingDrawable())
        val other = cache.bitmapFor(CountingDrawable())

        assertNotSame(one, other)
    }

    @Test
    fun aNewPickerStartsWithNoBitmaps() {
        // One cache per picker composition: a reopened picker (or a reloaded
        // provider list) gets a fresh one, so bitmaps never outlive it.
        val icon = CountingDrawable()
        WidgetPickerIconCache().bitmapFor(icon)
        WidgetPickerIconCache().bitmapFor(icon)

        assertEquals(2, icon.draws)
    }

    private class CountingDrawable : Drawable() {
        var draws = 0

        override fun draw(canvas: Canvas) {
            draws++
        }

        override fun getIntrinsicWidth(): Int = 48

        override fun getIntrinsicHeight(): Int = 48

        override fun setAlpha(alpha: Int) = Unit

        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
