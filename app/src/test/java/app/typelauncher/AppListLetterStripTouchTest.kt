package app.typelauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A real touch on each label of a letter strip jumps to that letter, and every
 * letter keeps its label even on a strip too short for `labelSmall` (a
 * semantic click would bypass the touch mapping).
 */
// NATIVE graphics, so labels measure as real text: the legacy mode gives every
// glyph a stand-in size, which says nothing about whether labels overlap.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppListLetterStripTouchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sections = ('A'..'Z').mapIndexed { i, c -> c.toString() to i * 10 }

    @Test
    fun aShortStripLabelsEveryLetterAndEachLabelJumpsToItsOwn() {
        var jumped: Int? = null
        composeRule.setContent {
            // 26 letters in 160dp is about 6dp a letter, under labelSmall's
            // 16dp line.
            Box(modifier = Modifier.height(160.dp)) {
                AppListLetterStrip(sections = sections, onJump = { jumped = it }, onDragChanged = {})
            }
        }
        composeRule.waitForIdle()
        val tag = APP_LIST_LETTER_STRIP_START_TAG
        val strip = composeRule.onNodeWithTag(tag).getBoundsInRoot()
        var previousBottom = strip.top
        sections.forEach { (letter, index) ->
            val label = composeRule.onNodeWithTag("$tag:$letter").getBoundsInRoot()
            assertTrue("label $letter overlaps the one above ($label)", label.top >= previousBottom)
            previousBottom = label.bottom
            val centerYInStrip = ((label.top + label.bottom) / 2 - strip.top).value
            jumped = null
            composeRule.onNodeWithTag(tag).performTouchInput {
                click(Offset(centerX, centerYInStrip.dp.toPx()))
            }
            composeRule.waitForIdle()
            assertEquals("tapping label $letter", index, jumped)
        }
        assertTrue("the last label stays inside the strip", previousBottom <= strip.bottom)
    }
}
