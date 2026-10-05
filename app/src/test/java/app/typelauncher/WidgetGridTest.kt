package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetGridTest {
    private fun layout(ids: List<Int>, spans: Map<Int, Int>) =
        widgetGridRows(ids, spans).map { row -> row.cells.map { cell -> Triple(cell.widgetId, cell.startColumn, cell.span) } }

    @Test
    fun widgetsWithoutASpanTakeAFullRowEach() {
        assertEquals(
            listOf(listOf(Triple(1, 0, 4)), listOf(Triple(2, 0, 4))),
            layout(listOf(1, 2), emptyMap()),
        )
    }

    @Test
    fun narrowWidgetsShareARowInOrder() {
        assertEquals(
            listOf(
                listOf(Triple(1, 0, 2), Triple(2, 2, 1), Triple(3, 3, 1)),
                listOf(Triple(4, 0, 4)),
            ),
            layout(listOf(1, 2, 3, 4), mapOf(1 to 2, 2 to 1, 3 to 1)),
        )
    }

    @Test
    fun aWidgetThatDoesNotFitStartsANewRowWithoutReordering() {
        // 3 columns used, a 2-column widget wraps; the 1-column widget after it
        // is not pulled back to fill the gap (order means Move up / down).
        assertEquals(
            listOf(
                listOf(Triple(1, 0, 3)),
                listOf(Triple(2, 0, 2), Triple(3, 2, 1)),
            ),
            layout(listOf(1, 2, 3), mapOf(1 to 3, 2 to 2, 3 to 1)),
        )
    }

    @Test
    fun outOfRangeSpansAreClamped() {
        assertEquals(
            listOf(listOf(Triple(1, 0, 1), Triple(2, 1, 3)), listOf(Triple(3, 0, 4))),
            layout(listOf(1, 2, 3), mapOf(1 to 0, 2 to 3, 3 to 9)),
        )
    }

    @Test
    fun rowKeyIsItsLeadWidget() {
        assertEquals(listOf(1, 3), widgetGridRows(listOf(1, 2, 3), mapOf(1 to 2, 2 to 2)).map { it.key })
    }
}
