package app.typelauncher

import android.appwidget.AppWidgetManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAddFlowTest {
    private val launched = mutableListOf<Int>()
    private val added = mutableListOf<Int>()
    private val deleted = mutableListOf<Int>()

    private fun flow(hasConfigureActivity: Boolean) = WidgetAddFlow(
        launchConfigure = { appWidgetId ->
            if (hasConfigureActivity) {
                launched += appWidgetId
            }
            hasConfigureActivity
        },
        addWidget = { appWidgetId -> added += appWidgetId },
        deleteWidget = { appWidgetId -> deleted += appWidgetId },
    )

    @Test
    fun directBindWithoutConfigureAddsImmediately() {
        val flow = flow(hasConfigureActivity = false)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)

        assertEquals(listOf(42), added)
        assertEquals(emptyList<Int>(), deleted)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

    @Test
    fun configureCanceledWithNullDataDeletesBoundWidget() {
        // Regression test for the pendingWidgetId clobber: MainActivity used
        // to reset the pending ID right after configureOrAddWidget launched
        // the configure activity, so a back-press cancel (RESULT_CANCELED,
        // null data) resolved to INVALID_APPWIDGET_ID and the
        // already-bound widget ID leaked instead of being deleted.
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)
        assertEquals(listOf(42), launched)

        flow.onConfigureResult(success = false, resultWidgetId = null)

        assertEquals(listOf(42), deleted)
        assertEquals(emptyList<Int>(), added)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

    @Test
    fun configureCanceledAfterSystemBindDialogDeletesBoundWidget() {
        // Same leak through the other entry point: bind granted via the
        // system permission dialog instead of bindAppWidgetIdIfAllowed.
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindResult(success = true, resultWidgetId = 42)
        assertEquals(listOf(42), launched)

        flow.onConfigureResult(success = false, resultWidgetId = null)

        assertEquals(listOf(42), deleted)
        assertEquals(emptyList<Int>(), added)
    }

    @Test
    fun configureSuccessWithoutDataStillAddsWidget() {
        // Not every configure activity passes EXTRA_APPWIDGET_ID back; the
        // flow must fall back to the in-flight ID rather than dropping the
        // bound-and-configured widget.
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)
        flow.onConfigureResult(success = true, resultWidgetId = null)

        assertEquals(listOf(42), added)
        assertEquals(emptyList<Int>(), deleted)
    }

    @Test
    fun configureResultWithMissingExtraFallsBackToPendingId() {
        // A result intent that exists but lacks the extra surfaces as
        // INVALID_APPWIDGET_ID (getIntExtra's default), not null.
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)
        flow.onConfigureResult(
            success = false,
            resultWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
        )

        assertEquals(listOf(42), deleted)
    }

    @Test
    fun bindDialogCanceledDeletesAllocatedId() {
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindResult(success = false, resultWidgetId = null)

        assertEquals(listOf(42), deleted)
        assertEquals(emptyList<Int>(), launched)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

    @Test
    fun bindDialogLaunchFailureDeletesAllocatedId() {
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindLaunchFailed()

        assertEquals(listOf(42), deleted)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

    @Test
    fun successfulConfigureFlowAddsAndClearsPending() {
        val flow = flow(hasConfigureActivity = true)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)
        assertEquals(42, flow.pendingWidgetId)

        flow.onConfigureResult(success = true, resultWidgetId = 42)

        assertEquals(listOf(42), added)
        assertEquals(emptyList<Int>(), deleted)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }
}
