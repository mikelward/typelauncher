package app.typelauncher

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The filter that keeps the user's installed-app inventory out of Crashlytics
 * breadcrumbs while leaving the on-device log — which the user reviews before
 * sending — intact.
 */
class TelemetryRedactionTest {
    private val installed = setOf("com.example.mail", "com.example.maps")

    @After
    fun clear() = TelemetryRedaction.clearForTest()

    @Test
    fun redactsAnInstalledPackageAfterAKey() {
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "launchApp package=<app> work=false",
            TelemetryRedaction.redact("launchApp package=com.example.mail work=false"),
        )
    }

    // `InstalledApp.id` glues user hash, package and class together, and it is
    // what every dock and folder line logs.
    @Test
    fun redactsThePackageInsideAnAppIdKeepingTheShapeAroundIt() {
        TelemetryRedaction.rememberPackages(installed)

        // `InstalledApp.id` flattens the component *fully qualified*, so the
        // class name spells the package out again — the whole token goes, and
        // the profile hash with it.
        assertEquals(
            "reorderDockedApps appId=<app> row=1 column=2",
            TelemetryRedaction.redact(
                "reorderDockedApps appId=10:com.example.mail/com.example.mail.MainActivity row=1 column=2",
            ),
        )
    }

    @Test
    fun redactsAFlattenedComponent() {
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "bindWidget provider=<app>",
            TelemetryRedaction.redact("bindWidget provider=com.example.maps/com.example.maps.Widget"),
        )
    }

    @Test
    fun redactsABareMentionAnywhereInTheLine() {
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "scheduleReload reason=packageAdded:<app>",
            TelemetryRedaction.redact("scheduleReload reason=packageAdded:com.example.mail"),
        )
    }

    // The point of matching the live set rather than a pattern: a filter that
    // guessed at dotted tokens would shred the diagnostics worth keeping.
    @Test
    fun leavesNonPackageDottedTokensAlone() {
        TelemetryRedaction.rememberPackages(installed)

        val line = "launchApp failed err=java.lang.SecurityException version=1.2.3 host=maven.google.com"

        assertEquals(line, TelemetryRedaction.redact(line))
    }

    @Test
    fun leavesAPackageTheLauncherHasNeverSeenAlone() {
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "openPlayStoreListing package=com.example.neverseen",
            TelemetryRedaction.redact("openPlayStoreListing package=com.example.neverseen"),
        )
    }

    // `onPackageAdded` logs the new package before the reload that adds it to
    // the installed list, so the callback remembers it first. Without that the
    // launcher would stream install events unredacted.
    @Test
    fun redactsAPackageRememberedBeforeItIsInstalled() {
        TelemetryRedaction.rememberPackage("com.example.justinstalled")

        assertEquals(
            "scheduleReload reason=packageAdded:<app>",
            TelemetryRedaction.redact("scheduleReload reason=packageAdded:com.example.justinstalled"),
        )
    }

    // The "scheduleReload complete" line repeats the reason after the reload has
    // already dropped an uninstalled package, so the set must never forget one.
    @Test
    fun keepsRedactingAPackageAfterItIsUninstalled() {
        TelemetryRedaction.rememberPackages(installed + "com.example.gone")
        // A later load no longer carries it.
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "scheduleReload complete reason=packageRemoved:<app> apps=2",
            TelemetryRedaction.redact("scheduleReload complete reason=packageRemoved:com.example.gone apps=2"),
        )
    }

    @Test
    fun redactsEveryOccurrenceInOneLine() {
        TelemetryRedaction.rememberPackages(installed)

        assertEquals(
            "mergeDockFolderMemberInto app=<app> target=<app>",
            TelemetryRedaction.redact("mergeDockFolderMemberInto app=com.example.mail target=com.example.maps"),
        )
    }

    @Test
    fun isAPassThroughBeforeAnyAppLoadHasPublishedItsPackages() {
        val line = "cold start began"

        assertEquals(line, TelemetryRedaction.redact(line))
    }
    // A widget provider need not have a launcher activity, so its package is
    // absent from the installed-app list the set is seeded from; the widget
    // call sites remember it explicitly.
    @Test
    fun redactsAWidgetProviderPackageOnceRemembered() {
        TelemetryRedaction.rememberPackage("com.example.widgetsonly")

        assertEquals(
            "bindWidget provider=<app> appWidgetId=7",
            TelemetryRedaction.redact(
                "bindWidget provider=com.example.widgetsonly/com.example.widgetsonly.Provider appWidgetId=7",
            ),
        )
    }

}
