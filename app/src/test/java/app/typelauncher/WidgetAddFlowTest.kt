package app.typelauncher

import android.appwidget.AppWidgetManager
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAddFlowTest {
    private val launched = mutableListOf<Int>()
    private val added = mutableListOf<Int>()
    private val deleted = mutableListOf<Int>()

    private fun flow(configureLaunch: WidgetAddFlow.ConfigureLaunch) = WidgetAddFlow(
        launchConfigure = { appWidgetId ->
            if (configureLaunch == WidgetAddFlow.ConfigureLaunch.Launched) {
                launched += appWidgetId
            }
            configureLaunch
        },
        addWidget = { appWidgetId -> added += appWidgetId },
        deleteWidget = { appWidgetId -> deleted += appWidgetId },
    )

    private fun flow(hasConfigureActivity: Boolean) = flow(
        if (hasConfigureActivity) {
            WidgetAddFlow.ConfigureLaunch.Launched
        } else {
            WidgetAddFlow.ConfigureLaunch.NotNeeded
        },
    )

    @Test
    fun configureLaunchFailureDeletesBoundWidget() {
        // The host-mediated configure launch can still fail (provider
        // uninstalled in a race, OEM oddities); the bound ID must be freed
        // rather than added half-configured or leaked.
        val flow = flow(WidgetAddFlow.ConfigureLaunch.Failed)

        flow.onBindStarted(42)
        flow.onBindAllowed(42)

        assertEquals(listOf(42), deleted)
        assertEquals(emptyList<Int>(), added)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

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
    fun restorePendingWidgetIdRecoversInFlightIdAcrossRecreation() {
        // Rotation / theme change mid-configure builds a fresh WidgetAddFlow;
        // restoring the saved pending ID lets the re-delivered configure result
        // add the widget even when the result intent omits the extra (and keeps
        // the startup orphan sweep from deleting the still-pending allocation).
        val flow = flow(hasConfigureActivity = true)

        flow.restorePendingWidgetId(42)
        assertEquals(42, flow.pendingWidgetId)

        flow.onConfigureResult(success = true, resultWidgetId = null)

        assertEquals(listOf(42), added)
        assertEquals(emptyList<Int>(), deleted)
        assertEquals(AppWidgetManager.INVALID_APPWIDGET_ID, flow.pendingWidgetId)
    }

    @Test
    fun restorePendingWidgetIdIgnoresInvalidId() {
        val flow = flow(hasConfigureActivity = true)

        flow.restorePendingWidgetId(AppWidgetManager.INVALID_APPWIDGET_ID)

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
