package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class BugReportWidgetProvidersTest {
    private fun payload(widgetProviders: Map<Int, String>) = buildBugReportPayload(
        nowMillis = 1_700_000_000_000L,
        versionName = "1.2.3",
        versionCode = 42L,
        buildType = "debug",
        applicationId = "app.typelauncher",
        isDebuggable = true,
        deviceManufacturer = "Pixel",
        deviceModel = "Pixel Test",
        androidRelease = "14",
        androidSdkInt = 34,
        locale = Locale.US,
        zoneId = ZoneId.of("UTC"),
        isDockEnabled = true,
        appListLayout = AppListLayout.NameBeside,
        dockIconSizeDp = 5,
        appListSortOrder = AppListSortOrder.Usage,
        isAgendaEnabled = true,
        dockedAppIds = emptyList(),
        widgetPages = listOf(listOf(11)),
        homeWidgetIds = listOf(22),
        widgetProviders = widgetProviders,
        log = emptyList(),
    )

    @Test
    fun eachWidgetIsListedWithItsProvider() {
        val text = payload(mapOf(11 to "com.example/.ClockWidget", 22 to "com.example/.WeatherWidget"))

        assertTrue(text.contains("Widget providers:\n  11: com.example/.ClockWidget\n  22: com.example/.WeatherWidget\n"))
    }

    @Test
    fun noWidgetsMeansNoProviderSection() {
        assertFalse(payload(emptyMap()).contains("Widget providers:"))
    }

    @Test
    fun describeShowsTheBindingAndAnyDisagreeingRecord() {
        assertEquals("a/.W", describeWidgetProvider(bound = "a/.W", remembered = "a/.W"))
        assertEquals("a/.W", describeWidgetProvider(bound = "a/.W", remembered = null))
        assertEquals("a/.W (remembered a/.V)", describeWidgetProvider(bound = "a/.W", remembered = "a/.V"))
        assertEquals("unbound (remembered a/.V)", describeWidgetProvider(bound = null, remembered = "a/.V"))
        assertEquals("unbound", describeWidgetProvider(bound = null, remembered = null))
    }

    @Test
    fun aLookupThatThrewIsNotReportedAsUnbound() {
        assertEquals("lookup failed (remembered a/.V)", describeWidgetProvider(bound = null, remembered = "a/.V", lookupFailed = true))
        assertEquals("lookup failed", describeWidgetProvider(bound = null, remembered = null, lookupFailed = true))
    }

    @Test
    fun aWorkWidgetBoundToThePersonalCopyShowsAsAMismatch() {
        val bound = widgetIdentity("a/.W", WidgetProfileKind.PERSONAL)
        val remembered = widgetIdentity("a/.W", WidgetProfileKind.WORK)

        assertEquals("a/.W (remembered a/.W [work])", describeWidgetProvider(bound, remembered))
    }

    @Test
    fun identityNamesOnlyNonPersonalProfiles() {
        assertEquals("a/.W", widgetIdentity("a/.W", WidgetProfileKind.PERSONAL))
        assertEquals("a/.W [profile unknown]", widgetIdentity("a/.W", null))
        assertEquals("a/.W [work]", widgetIdentity("a/.W", WidgetProfileKind.WORK))
        assertEquals("a/.W [other]", widgetIdentity("a/.W", WidgetProfileKind.OTHER))
    }

    @Test
    fun anUnclassifiedBindingDoesNotMatchAPersonalRecord() {
        val bound = widgetIdentity("a/.W", kind = null)
        val remembered = widgetIdentity("a/.W", WidgetProfileKind.PERSONAL)

        assertEquals("a/.W [profile unknown] (remembered a/.W)", describeWidgetProvider(bound, remembered))
    }
}
