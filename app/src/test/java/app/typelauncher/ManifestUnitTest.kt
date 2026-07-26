package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ManifestUnitTest {
    @Test
    fun mainActivity_manifestHasLauncherFlagsAndCalendarPermission() {
        val manifest = parseManifest()
        val application = manifest.getElementsByTagName("application").item(0)
        val activity = manifest.getElementsByTagName("activity").item(0)
        val queryIntent = manifest.getElementsByTagName("queries").item(0).elementChildren().single()
        val queryElements = queryIntent.elementChildren()
        val applicationAttrs = application.attributes
        val attrs = activity.attributes
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length)
            .map { index -> permissions.item(index).attributes.getNamedItem("android:name").nodeValue }

        assertEquals("\${launcherIcon}", applicationAttrs.getNamedItem("android:icon").nodeValue)
        assertEquals("\${launcherRoundIcon}", applicationAttrs.getNamedItem("android:roundIcon").nodeValue)
        assertEquals("\${appLabel}", applicationAttrs.getNamedItem("android:label").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:clearTaskOnLaunch").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:excludeFromRecents").nodeValue)
        assertEquals("singleTask", attrs.getNamedItem("android:launchMode").nodeValue)
        // stateNotNeeded must NOT be declared: it lets Android restart the
        // launcher after a low-memory process death with a null saved-state
        // bundle, which silently breaks every in-flight-result recovery the
        // app carries through instance state — the rememberSaveable
        // pendingIconPickAppId (and the ActivityResultRegistry's own
        // pending-request record) for the icon picker, and MainActivity's
        // KEY_PENDING_WIDGET_ID for the widget bind/configure flow. All the
        // state saved is tiny and restore-safe, so the flag's
        // crash-loop-on-restore protection isn't worth re-breaking those
        // flows for.
        assertEquals(null, attrs.getNamedItem("android:stateNotNeeded"))
        assertEquals("stateAlwaysVisible|adjustResize", attrs.getNamedItem("android:windowSoftInputMode").nodeValue)
        assertEquals("intent", queryIntent.nodeName)
        assertEquals("action", queryElements[0].nodeName)
        assertEquals("android.intent.action.MAIN", queryElements[0].attributes.getNamedItem("android:name").nodeValue)
        assertEquals("category", queryElements[1].nodeName)
        assertEquals("android.intent.category.LAUNCHER", queryElements[1].attributes.getNamedItem("android:name").nodeValue)
        assertTrue(names.contains("android.permission.READ_CALENDAR"))
    }

    @Test
    fun launcherIcon_manifestPlaceholdersUseLocalBadgeOutsideCi() {
        val buildFile = File("build.gradle.kts").readText()

        assertTrue(buildFile.contains("providers.environmentVariable(\"CI\")"))
        assertTrue(buildFile.contains("launcherIconResource = if (isCiBuild) \"@mipmap/ic_launcher\" else \"@mipmap/ic_launcher_local\""))
        assertTrue(buildFile.contains("launcherRoundIconResource = if (isCiBuild) \"@mipmap/ic_launcher_round\" else \"@mipmap/ic_launcher_round_local\""))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherIcon\"] = launcherIconResource"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherRoundIcon\"] = launcherRoundIconResource"))
    }

    @Test
    fun `appLabel names the build outside a CI release`() {
        val buildFile = File("build.gradle.kts").readText()

        // Only the Play build keeps the localized name; the Firebase tester and
        // any local APK say which build they are, so three co-installed copies
        // are distinguishable in the app list and the home-role picker.
        assertTrue(buildFile.contains("val devAppLabel = \"Type Launcher Dev\""))
        assertTrue(
            buildFile.contains(
                "val releaseAppLabel = if (isCiBuild) \"@string/app_name\" else devAppLabel",
            ),
        )
        assertTrue(
            buildFile.contains(
                "val debugAppLabel = if (isCiBuild) \"Type Launcher Debug\" else devAppLabel",
            ),
        )
        assertTrue(buildFile.contains("manifestPlaceholders[\"appLabel\"] = releaseAppLabel"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"appLabel\"] = debugAppLabel"))
    }

    @Test
    fun `a local debug build gets its own application ID`() {
        val buildFile = File("build.gradle.kts").readText()

        // Without this a local debug build and the Firebase tester build are both
        // app.typelauncher.debug, and since they carry different signatures the
        // collision is an install failure rather than an upgrade. CI must keep
        // ".debug" so Firebase App Distribution's app ID still matches.
        assertTrue(buildFile.contains("val debugApplicationIdSuffix = if (isCiBuild) \".debug\" else \".dev\""))
        assertTrue(buildFile.contains("applicationIdSuffix = debugApplicationIdSuffix"))
        // app.typelauncher.dev has no google-services.json client on purpose, so
        // the plugin's "No matching client" failure is sidestepped for that
        // variant instead of breaking every local build that has a config.
        assertTrue(buildFile.contains("\"processDebugGoogleServices\","))
        assertTrue(buildFile.contains("val debugApplicationId = \"app.typelauncher\$debugApplicationIdSuffix\""))
        // Scoped to non-CI: in CI a missing debug client means a stale
        // GOOGLE_SERVICES_JSON secret, and the plugin's hard failure is the only
        // thing that surfaces it before a tester build ships without Crashlytics.
        assertTrue(buildFile.contains("if (!isCiBuild && !firebaseConfig.contains("))
        // Disabling the task leaves its earlier output in place, which the resource
        // merge would package into the .dev APK — so the stale directory is purged.
        assertTrue(buildFile.contains("purgeDebugGoogleServicesResources"))
        assertTrue(buildFile.contains("dependsOn(purgeForeignFirebaseResources)"))
        // installAndRun uninstalls and launches by package name, so it has to
        // follow the suffix rather than hardcode one.
        assertTrue(buildFile.contains("applicationId.set(debugApplicationId)"))
        assertFalse(buildFile.contains("DEBUG_APPLICATION_ID"))
    }

    @Test
    fun `the badged labels stay out of the translation pipeline`() {
        // They are manifest literals, never string resources, so no locale ever
        // has to carry "Dev" / "Debug" and no MissingTranslation lint applies.
        val baseStrings = File("src/main/res/values/strings.xml").readText()

        assertTrue(baseStrings.contains("<string name=\"app_name\">Type Launcher</string>"))
        assertFalse(baseStrings.contains("Type Launcher Dev"))
        assertFalse(baseStrings.contains("Type Launcher Debug"))
    }

    @Test
    fun launcherIcon_localResourcesIncludeDevBar() {
        val localIcon = File("src/main/res/mipmap-anydpi/ic_launcher_local.xml").readText()
        val localRoundIcon = File("src/main/res/mipmap-anydpi/ic_launcher_round_local.xml").readText()
        val localForeground = File("src/main/res/drawable/ic_launcher_foreground_local.xml").readText()
        val localMonochrome = File("src/main/res/drawable/ic_launcher_monochrome_local.xml").readText()

        assertTrue(localIcon.contains("@drawable/ic_launcher_background"))
        assertTrue(localIcon.contains("@drawable/ic_launcher_foreground_local"))
        assertTrue(localIcon.contains("@drawable/ic_launcher_monochrome_local"))
        assertTrue(localRoundIcon.contains("@drawable/ic_launcher_background"))
        assertTrue(localRoundIcon.contains("@drawable/ic_launcher_foreground_local"))
        assertTrue(localRoundIcon.contains("@drawable/ic_launcher_monochrome_local"))
        assertTrue(localForeground.contains("DEV bar"))
        // The badge bar sits inside the safe zone (top at y64) instead of the
        // cropped bottom ring (the old y92 band that never reached the screen).
        assertTrue(localForeground.contains("M0,64 H108 V108 H0 Z"))
        assertTrue(localForeground.contains("android:scaleX=\"1.5\""))
        assertTrue(localForeground.contains("android:translateY=\"68.5\""))
        assertTrue(localForeground.contains("#FFC107"))
        assertTrue(localForeground.contains("M37.01,8.5"))
        assertTrue(localMonochrome.contains("DEV bar"))
        // The monochrome badge punches the letters out of the bar as even-odd
        // cut-outs so the themed-icon tint can't flatten it into a solid block.
        assertTrue(localMonochrome.contains("android:fillType=\"evenOdd\""))
        assertTrue(localMonochrome.contains("android:scaleX=\"1.5\""))
        assertTrue(localMonochrome.contains("android:translateY=\"68.5\""))
        assertTrue(localMonochrome.contains("M37.01,8.5"))
    }

    @Test
    fun mainActivity_sourceUsesComposeMaterialThemeAndLifecycleState() {
        val mainActivitySource = File("src/main/java/app/typelauncher/MainActivity.kt").readText()
        val typeLauncherAppSource = File("src/main/java/app/typelauncher/ui/TypeLauncherApp.kt").readText()
        val homeScreenSource = File("src/main/java/app/typelauncher/ui/HomeScreen.kt").readText()
        val launcherFilterFieldSource = File("src/main/java/app/typelauncher/ui/LauncherFilterField.kt").readText()
        val themeSource = File("src/main/java/app/typelauncher/ui/TypeLauncherTheme.kt").readText()
        val viewModelSource = File("src/main/java/app/typelauncher/LauncherViewModel.kt").readText()

        assertTrue(mainActivitySource.contains("setContent"))
        assertTrue(themeSource.contains("MaterialTheme"))
        assertTrue(typeLauncherAppSource.contains("Scaffold"))
        assertTrue(homeScreenSource.contains("Card"))
        assertTrue(homeScreenSource.contains("LauncherFilterField"))
        assertTrue(launcherFilterFieldSource.contains("OutlinedTextField"))
        assertTrue(typeLauncherAppSource.contains("collectAsStateWithLifecycle"))
        assertTrue(viewModelSource.contains("ViewModel"))
        assertTrue(themeSource.contains("dynamicLightColorScheme"))
        // The agenda path routes through the organizer as loadAgendaEvents'
        // default `organize` parameter (a function reference, since the search
        // index passes forSearch through the same seam).
        assertTrue(viewModelSource.contains("AgendaEventOrganizer::forNow"))
        assertTrue(mainActivitySource.contains("onWindowFocusChanged"))
        assertTrue(mainActivitySource.contains("onTrimMemory"))
        assertTrue(typeLauncherAppSource.contains("LauncherDebugLog.event"))
        assertTrue(viewModelSource.contains("loadInstalledApps complete"))
    }

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun Node.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
}
