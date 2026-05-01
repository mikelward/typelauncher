package app.typelauncher

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
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

        assertEquals(100.dp, widgetCardHeight(minHeightPx = 300, density))
    }

    @Test
    fun hostedWidgetHeight_usesLauncherFloorWhenProviderHeightIsShorter() {
        val density = Density(density = 3f)

        assertEquals(96.dp, widgetCardHeight(minHeightPx = 120, density))
    }
}
