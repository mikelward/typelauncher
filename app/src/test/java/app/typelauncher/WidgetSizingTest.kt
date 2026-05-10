package app.typelauncher

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetSizingTest {
    @Test
    fun hostedWidgetHeight_convertsProviderPixelsToDpBeforeApplyingFloor() {
        val density = Density(density = 3f)

        assertEquals(100.dp, widgetCardHeight(minHeightPx = 300, targetCellHeight = 1, density))
    }

    @Test
    fun hostedWidgetHeight_usesLauncherFloorWhenProviderHeightIsShorter() {
        val density = Density(density = 3f)

        assertEquals(96.dp, widgetCardHeight(minHeightPx = 120, targetCellHeight = 1, density))
    }

    @Test
    fun hostedWidgetHeight_usesCellHeightWhenLargerThanMinHeight() {
        val density = Density(density = 3f)

        // 2 cells * 80dp = 160dp, which exceeds minHeight of 110dp (330px / 3)
        assertEquals(160.dp, widgetCardHeight(minHeightPx = 330, targetCellHeight = 2, density))
    }

    @Test
    fun hostedWidgetHeight_usesMinHeightWhenLargerThanCellHeight() {
        val density = Density(density = 3f)

        // minHeight = 900px / 3 = 300dp, which exceeds 3 cells * 80dp = 240dp
        assertEquals(300.dp, widgetCardHeight(minHeightPx = 900, targetCellHeight = 3, density))
    }

    @Test
    fun widgetSizeHint_returnsNullForUnmeasuredCard() {
        val density = Density(density = 3f)

        assertNull(widgetSizeHintDp(IntSize.Zero, density))
    }

    @Test
    fun widgetSizeHint_convertsMeasuredPixelsToDp() {
        val density = Density(density = 3f)

        // 1080 px / 3 = 360 dp wide; 720 px / 3 = 240 dp tall.
        assertEquals(WidgetSizeHintDp(360, 240), widgetSizeHintDp(IntSize(1080, 720), density))
    }
}
