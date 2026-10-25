package app.typelauncher

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

/**
 * A real touch on a visible letter of the landscape letter strip jumps to that
 * letter — including on a strip too short to label every letter, where only a
 * subset is labeled (a semantic click would bypass the touch mapping).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS")
class AppListLetterStripTouchTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sections = ('A'..'Z').mapIndexed { i, c -> c.toString() to i * 10 }

    @Test
    fun tappingEachVisibleLabelJumpsToItsOwnLetter() {
        var jumped: Int? = null
        composeRule.setContent {
            Box(modifier = Modifier.height(160.dp)) {
                AppListLetterStrip(sections = sections, onJump = { jumped = it }, onDragChanged = {})
            }
        }
        composeRule.waitForIdle()
        val strip = composeRule.onNodeWithTag(APP_LIST_LETTER_STRIP_TAG).getBoundsInRoot()
        val shown = sections.filter { (letter, _) ->
            composeRule.onAllNodesWithTag("$APP_LIST_LETTER_TAG:$letter").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue("a 160dp strip labels only some of 26 letters, got ${shown.size}", shown.size in 2..25)
        shown.forEach { (letter, index) ->
            val label = composeRule.onNodeWithTag("$APP_LIST_LETTER_TAG:$letter").getBoundsInRoot()
            val centerYInStrip = ((label.top + label.bottom) / 2 - strip.top).value
            jumped = null
            composeRule.onNodeWithTag(APP_LIST_LETTER_STRIP_TAG).performTouchInput {
                click(Offset(centerX, centerYInStrip.dp.toPx()))
            }
            composeRule.waitForIdle()
            assertEquals("tapping label $letter", index, jumped)
        }
    }
}
