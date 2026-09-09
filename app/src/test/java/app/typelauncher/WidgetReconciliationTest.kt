package app.typelauncher

import android.appwidget.AppWidgetManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetReconciliationTest {
    @Test
    fun returnsAllocatedIdsMissingFromTheStore() {
        // 7 and 9 were allocated on the host but the launcher never persisted
        // them (crash mid-add, or the historical configure-cancel leak).
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(5, 7, 8, 9),
            knownWidgetIds = listOf(5, 8),
        )

        assertEquals(listOf(7, 9), orphans)
    }

    @Test
    fun returnsEmptyWhenEverythingAllocatedIsTracked() {
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(1, 2, 3),
            knownWidgetIds = listOf(3, 2, 1),
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun neverSweepsTheInFlightPendingId() {
        // A reconciliation racing an active add must not delete the widget whose
        // bind/configure is still in flight — it isn't persisted yet but is not
        // an orphan either.
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(4, 11),
            knownWidgetIds = listOf(4),
            pendingWidgetId = 11,
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun neverSweepsAnIdBeingBound() {
        // An add that has allocated its ID but hasn't finished the bind IPC
        // tracks it as bindingWidgetId, not pendingWidgetId yet. A sweep racing
        // that window must spare it just like a pending one.
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(4, 12),
            knownWidgetIds = listOf(4),
            bindingWidgetId = 12,
        )

        assertEquals(emptyList<Int>(), orphans)
    }

    @Test
    fun ignoresInvalidWidgetIds() {
        val orphans = orphanedAllocatedWidgetIds(
            allocatedIds = intArrayOf(AppWidgetManager.INVALID_APPWIDGET_ID, 6),
            knownWidgetIds = emptyList(),
        )

        assertEquals(listOf(6), orphans)
    }
}
