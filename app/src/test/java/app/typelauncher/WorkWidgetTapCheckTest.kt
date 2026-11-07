package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkWidgetTapCheckTest {
    private val check = WorkWidgetTapCheck(staleWindowMillis = 30_000)

    @Test
    fun tapSoonAfterProfileReturnsOnSavedContentIsStale() {
        check.onWorkProfileAvailable(nowMillis = 1_000)
        check.onTap(widgetId = 33, providerUpdated = false, nowMillis = 2_500)

        val tap = check.onCheckDue()!!
        assertTrue(tap.stale)
        assertEquals(33, tap.widgetId)
        assertEquals(1_500L, tap.sinceProfileAvailableMillis)
    }

    @Test
    fun tapAfterTheProviderSentContentIsNotStale() {
        check.onWorkProfileAvailable(nowMillis = 1_000)
        check.onTap(widgetId = 33, providerUpdated = true, nowMillis = 2_500)

        val tap = check.onCheckDue()!!
        assertFalse(tap.stale)
        assertTrue(tap.providerUpdated)
    }

    @Test
    fun tapAfterTheWindowIsNotStale() {
        check.onWorkProfileAvailable(nowMillis = 1_000)
        check.onTap(widgetId = 33, providerUpdated = false, nowMillis = 31_001)

        assertFalse(check.onCheckDue()!!.stale)
    }

    @Test
    fun tapWithoutTheProfileComingBackIsReportedButNotStale() {
        check.onTap(widgetId = 33, providerUpdated = false, nowMillis = 2_500)

        val tap = check.onCheckDue()!!
        assertFalse(tap.stale)
        assertNull(tap.sinceProfileAvailableMillis)
    }

    @Test
    fun launcherPausingMeansTheTapOpenedSomething() {
        check.onWorkProfileAvailable(nowMillis = 1_000)
        check.onTap(widgetId = 33, providerUpdated = false, nowMillis = 2_500)
        check.onLauncherPaused()

        assertNull(check.onCheckDue())
    }

    @Test
    fun aTapIsReportedOnce() {
        check.onTap(widgetId = 33, providerUpdated = false, nowMillis = 2_500)
        check.onCheckDue()

        assertNull(check.onCheckDue())
    }
}
