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

    @Test
    fun addStaysInFlightForTheWholeLaunchedConfigureFlow() {
        // MainActivity.bindWidget refuses a second Add while isAddInFlight is
        // true, so the flag must cover every non-terminal state: from bind
        // start, across the system bind dialog, through the configure launch,
        // until the result lands. A gap would re-open the double-tap clobber
        // (see secondAddStartedMidFlightClobbersTheFirstFlow).
        val flow = flow(hasConfigureActivity = true)
        assertEquals(false, flow.isAddInFlight)

        flow.onBindStarted(42)
        assertEquals("in flight while the bind dialog is up", true, flow.isAddInFlight)

        flow.onBindResult(success = true, resultWidgetId = 42)
        assertEquals("in flight while configure is foreground", true, flow.isAddInFlight)

        flow.onConfigureResult(success = true, resultWidgetId = 42)
        assertEquals("terminal result ends the flight", false, flow.isAddInFlight)
    }

    @Test
    fun addStopsBeingInFlightOnEveryTerminalPath() {
        // No-configure add commits immediately.
        val direct = flow(hasConfigureActivity = false)
        direct.onBindStarted(1)
        direct.onBindAllowed(1)
        assertEquals(false, direct.isAddInFlight)

        // Failed configure launch frees the ID and ends the flight.
        val failed = flow(WidgetAddFlow.ConfigureLaunch.Failed)
        failed.onBindStarted(2)
        failed.onBindAllowed(2)
        assertEquals(false, failed.isAddInFlight)

        // Canceled bind dialog frees the ID and ends the flight.
        val canceled = flow(hasConfigureActivity = true)
        canceled.onBindStarted(3)
        canceled.onBindResult(success = false, resultWidgetId = null)
        assertEquals(false, canceled.isAddInFlight)

        // A bind dialog that never launched frees the ID and ends the flight.
        val launchFailed = flow(hasConfigureActivity = true)
        launchFailed.onBindStarted(4)
        launchFailed.onBindLaunchFailed()
        assertEquals(false, launchFailed.isAddInFlight)
    }

    @Test
    fun secondAddStartedMidFlightClobbersTheFirstFlow() {
        // Documents WHY the host must gate on isAddInFlight (the double-tap
        // bug the MainActivity guard fixes): if a second add starts while flow
        // A's configure activity is in flight, A's data-less cancel resolves
        // to B's ID — deleting B's bound widget out from under its own
        // configure activity while A's ID leaks. The flow itself cannot
        // distinguish the two launches (both share one pendingWidgetId and one
        // request code), so prevention lives at the entry point.
        val flow = flow(hasConfigureActivity = true)
        flow.onBindStarted(42)
        flow.onBindAllowed(42) // A's configure is now in flight.

        // The second Add tap — exactly what MainActivity now refuses.
        assertEquals(true, flow.isAddInFlight)
        flow.onBindStarted(43)

        // A's back-press cancel (null data) now resolves to B's ID: the wrong
        // widget is deleted and A's ID is never freed.
        flow.onConfigureResult(success = false, resultWidgetId = null)
        assertEquals(listOf(43), deleted)
    }
}
