package app.typelauncher

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import androidx.compose.foundation.shape.CircleShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IconShapesTest {

    @Test
    fun squirclePathFillsItsBounds() {
        val path = squircleAndroidPath(100f, 100f)
        assertFalse(path.isEmpty)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertEquals(0f, bounds.left, 1f)
        assertEquals(0f, bounds.top, 1f)
        assertEquals(100f, bounds.right, 1f)
        assertEquals(100f, bounds.bottom, 1f)
    }

    @Test
    fun squircleExtendsBeyondTheInscribedCircleAtCorners() {
        // A point toward the corner that lies outside the inscribed circle but
        // inside the squircle — i.e. the squircle is fuller at the corners,
        // which is what distinguishes it from a plain circle.
        val cornerward = 88 to 88

        val squircle = regionOf(squircleAndroidPath(100f, 100f))
        val circle = regionOf(Path().apply { addCircle(50f, 50f, 50f, Path.Direction.CW) })

        assertTrue("squircle should contain the cornerward point", squircle.contains(cornerward.first, cornerward.second))
        assertFalse("circle should not contain the cornerward point", circle.contains(cornerward.first, cornerward.second))
    }

    @Test
    fun toComposeShapeMapsEachValue() {
        assertSame(CircleShape, IconShape.Circle.toComposeShape())
        assertSame(SquircleShape, IconShape.Squircle.toComposeShape())
        assertSame(SystemIconShape, IconShape.System.toComposeShape())
    }

    private fun regionOf(path: Path): Region =
        Region().apply { setPath(path, Region(0, 0, 100, 100)) }
}
