package app.typelauncher

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Settings preview sketches Home's widget grid: real column fractions,
 * relative heights scaled down to fit the slot, and Home's Add widget button
 * when there are none — all without hosting a widget.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HomeWidgetsSketchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun halfRowWidgetsShareARowAndTheStackFitsTheSlot() {
        setSketch(
            widgetIds = listOf(1, 2, 3),
            heights = mapOf(1 to 200, 3 to 100),
            spans = mapOf(1 to 2, 2 to 2),
        )

        val first = bounds(1)
        val second = bounds(2)
        val third = bounds(3)
        // Side by side, each about half the 300dp slot.
        assertEquals(first.top, second.top)
        // Home's fixed gutter between them, not one scaled with the heights.
        assertDpEquals(HOME_CARD_SPACING_DP.dp, second.left - first.right)
        assertTrue(widthOf(first) in 140.dp..150.dp)
        // The full-row widget sits below and spans the slot.
        assertTrue(third.top > first.bottom)
        assertDpEquals(300.dp, widthOf(third))
        // Real stack is 200 + 8 + 100 = 308dp, scaled into 100dp: heights
        // keep their 2:1 ratio and nothing spills past the slot.
        assertDpEquals(heightOf(first), heightOf(third) * 2)
        assertTrue(third.bottom <= 100.dp + 0.5.dp)
    }

    @Test
    @Config(qualifiers = "420dpi")
    fun scaledRowsNeverRoundPastTheSlot() {
        // Four default-height rows in an 83dp slot: rounding each row and gap
        // to the nearest pixel on its own used to overflow by 2px and clip
        // the last widget's border.
        setSketch(widgetIds = listOf(1, 2, 3, 4), heights = emptyMap(), spans = emptyMap(), slotHeight = 83.dp)

        assertTrue(bounds(4).bottom <= 83.dp)
    }

    @Test
    fun aStackThatFitsIsNotScaledUp() {
        setSketch(widgetIds = listOf(1), heights = mapOf(1 to 64), spans = emptyMap())

        assertDpEquals(64.dp, heightOf(bounds(1)))
    }

    @Test
    fun noWidgetsShowsTheAddWidgetButton() {
        setSketch(widgetIds = emptyList(), heights = emptyMap(), spans = emptyMap())

        composeRule.onNodeWithText("Add widget").assertExists()
    }

    private fun setSketch(
        widgetIds: List<Int>,
        heights: Map<Int, Int>,
        spans: Map<Int, Int>,
        slotHeight: Dp = 100.dp,
    ) {
        composeRule.setContent {
            TypeLauncherTheme {
                HomeWidgetsSketch(
                    widgetIds = widgetIds,
                    widgetHeights = heights,
                    widgetSpans = spans,
                    modifier = Modifier.width(300.dp).height(slotHeight),
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun bounds(widgetId: Int) =
        composeRule.onNodeWithTag("$SETTINGS_PREVIEW_HOME_WIDGET_SKETCH_TAG:$widgetId").getUnclippedBoundsInRoot()

    private fun widthOf(rect: DpRect): Dp = rect.right - rect.left

    private fun heightOf(rect: DpRect): Dp = rect.bottom - rect.top

    private fun assertDpEquals(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, 1f)
    }
}
