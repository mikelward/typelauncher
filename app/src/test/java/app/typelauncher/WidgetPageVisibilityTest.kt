package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetPageVisibilityTest {
    @Test
    fun filterWidgetPagesForDisplayDropsHiddenIdsAndKeepsSourcePageMapping() {
        val visible = listOf(
            listOf(1, 2),
            listOf(3),
            listOf(4, 5),
        ).filterWidgetPagesForDisplay { widgetId -> widgetId !in setOf(2, 3) }

        assertEquals(listOf(listOf(1), listOf(4, 5)), visible.pages)
        assertEquals(listOf(0, 2), visible.sourcePageIndices)
        assertEquals(listOf(1, 4, 5), visible.widgetIds)
        assertEquals(0, visible.sourcePageIndexFor(0))
        assertEquals(2, visible.sourcePageIndexFor(1))
    }

    @Test
    fun filterWidgetPagesForDisplayUsesLastStoredPageForEmptyDisplayPlaceholder() {
        val visible = listOf(
            listOf(1),
            listOf(2),
        ).filterWidgetPagesForDisplay { false }

        assertEquals(listOf(emptyList<Int>()), visible.pages)
        assertEquals(listOf(1), visible.sourcePageIndices)
        assertEquals(emptyList<Int>(), visible.widgetIds)
        assertEquals(1, visible.sourcePageIndexFor(0))
        assertEquals(1, visible.sourcePageIndexFor(99))
    }

    @Test
    fun pageIndexForReturnsDisplayedPageContainingWidgetId() {
        val visible = listOf(
            listOf(1, 2),
            listOf(3),
            listOf(4, 5),
        ).filterWidgetPagesForDisplay { widgetId -> widgetId != 3 }

        assertEquals(0, visible.pageIndexFor(2))
        assertEquals(1, visible.pageIndexFor(5))
        assertNull(visible.pageIndexFor(3))
    }
}
