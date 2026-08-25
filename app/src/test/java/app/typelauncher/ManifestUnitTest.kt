package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot

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
        // Three icons, one per build. Only the CI release build is unbadged: the
        // CI debug build used to share that plain icon, which made it
        // indistinguishable from the Play build on a device carrying both.
        assertTrue(buildFile.contains("val devLauncherIcon = \"@mipmap/ic_launcher_local\""))
        assertTrue(buildFile.contains("releaseLauncherIcon = if (isCiBuild) \"@mipmap/ic_launcher\" else devLauncherIcon"))
        assertTrue(buildFile.contains("debugLauncherIcon = if (isCiBuild) \"@mipmap/ic_launcher_debug\" else devLauncherIcon"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherIcon\"] = releaseLauncherIcon"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherIcon\"] = debugLauncherIcon"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherRoundIcon\"] = releaseLauncherRoundIcon"))
        assertTrue(buildFile.contains("manifestPlaceholders[\"launcherRoundIcon\"] = debugLauncherRoundIcon"))
    }

    @Test
    fun `appLabel names the build outside a CI release`() {
        val buildFile = File("build.gradle.kts").readText()

        // Only the Play build keeps the localized name; a CI debug APK and
        // any local one say which build they are, so three co-installed copies
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

        // Without this a local debug build and a CI-built one are both
        // app.typelauncher.debug, and since they carry different signatures the
        // collision is an install failure rather than an upgrade.
        assertTrue(buildFile.contains("val debugApplicationIdSuffix = if (isCiBuild) \".debug\" else \".dev\""))
        assertTrue(buildFile.contains("applicationIdSuffix = debugApplicationIdSuffix"))
        // app.typelauncher.dev has no google-services.json client on purpose, so
        // the plugin's "No matching client" failure is sidestepped for that
        // variant instead of breaking every local build that has a config.
        assertTrue(buildFile.contains("\"processDebugGoogleServices\","))
        assertTrue(buildFile.contains("val debugApplicationId = \"app.typelauncher\$debugApplicationIdSuffix\""))
        // Scoped to non-CI: in CI a missing debug client means a stale
        // GOOGLE_SERVICES_JSON secret, and the plugin's hard failure is the only
        // thing that surfaces it before a build ships without Crashlytics.
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
    fun launcherIcon_badgedResourcesShareOneScheme() {
        // The DEV and DEBUG badges are one scheme: same mark scale and lift, same
        // bar, same lettering group and baseline, same colors, same typeface and
        // cap height — only the word differs.
        listOf("local" to "DEV", "debug" to "DEBUG").forEach { (slug, word) ->
            val icon = File("src/main/res/mipmap-anydpi/ic_launcher_$slug.xml").readText()
            val roundIcon = File("src/main/res/mipmap-anydpi/ic_launcher_round_$slug.xml").readText()
            val foreground = File("src/main/res/drawable/ic_launcher_foreground_$slug.xml").readText()
            val monochrome = File("src/main/res/drawable/ic_launcher_monochrome_$slug.xml").readText()

            listOf(icon, roundIcon).forEach { adaptive ->
                assertTrue(adaptive.contains("@drawable/ic_launcher_background"))
                assertTrue(adaptive.contains("@drawable/ic_launcher_foreground_$slug"))
                assertTrue(adaptive.contains("@drawable/ic_launcher_monochrome_$slug"))
            }
            assertTrue(foreground.contains("reading \"$word\""))
            listOf(foreground, monochrome).forEach { drawable ->
                assertTrue(drawable.contains("android:scaleX=\"0.7\""))
                assertTrue(drawable.contains("android:translateY=\"-12\""))
                assertTrue(drawable.contains("android:scaleX=\"1.5\""))
                assertTrue(drawable.contains("android:translateY=\"68.5\""))
            }
            // The bar sits inside the safe zone (top at y64) rather than the
            // cropped bottom ring.
            assertTrue(foreground.contains("M0,64 H108 V108 H0 Z"))
            assertTrue(foreground.contains("#FFC107"))
            assertTrue(foreground.contains("#1A1A1A"))
            // The themed-icon tint would flatten a second opaque color, so the
            // monochrome badge punches the letters out of the bar instead.
            assertTrue(monochrome.contains("android:fillType=\"evenOdd\""))
        }
    }

    @Test
    fun launcherIcon_badgeLetteringStaysInsideTheSafeCircle() {
        // A badge the launcher crops is worse than no badge — the icon then just
        // looks like the real one — and the crop only shows on a device with a
        // circular mask, which no test here can render. So the geometry is
        // checked against the 66dp safe circle directly, in the resource where it
        // is authored. "DEBUG" is five letters in the bar that holds three, so
        // this is what bounds its cap height.
        val safeRadius = 33.0
        listOf(
            "ic_launcher_foreground_local.xml",
            "ic_launcher_foreground_debug.xml",
        ).forEach { file ->
            val lettering = letteringPaths(File("src/main/res/drawable/$file"), "#1A1A1A")

            assertTrue("no lettering found in $file", lettering.isNotEmpty())
            lettering.forEach { (transform, data) ->
                pathPoints(data).forEach { (localX, localY) ->
                    val x = transform.scaleX * localX + transform.translateX
                    val y = transform.scaleY * localY + transform.translateY
                    val reach = hypot(x - 54.0, y - 54.0)
                    assertTrue(
                        "$file lettering reaches ${reach.toInt()} of the " +
                            "${safeRadius.toInt()}-unit safe circle — a launcher mask will crop it",
                        reach <= safeRadius,
                    )
                }
            }
        }
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

    /**
     * The lettering paths of a badge foreground, each paired with the transform
     * of the group holding it — both badges author their glyphs inside a
     * 1.5x-scaled group, so that has to be applied before measuring reach.
     */
    private fun letteringPaths(source: File, letterColor: String): List<Pair<GroupTransform, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
        val paths = document.getElementsByTagName("path")
        return (0 until paths.length)
            .map { paths.item(it) as Element }
            .filter { it.getAttribute("android:fillColor") == letterColor }
            .map { path ->
                val parent = path.parentNode as Element
                val transform = if (parent.tagName == "group") {
                    GroupTransform(
                        parent.getAttribute("android:scaleX").toDoubleOrNull() ?: 1.0,
                        parent.getAttribute("android:scaleY").toDoubleOrNull() ?: 1.0,
                        parent.getAttribute("android:translateX").toDoubleOrNull() ?: 0.0,
                        parent.getAttribute("android:translateY").toDoubleOrNull() ?: 0.0,
                    )
                } else {
                    GroupTransform(1.0, 1.0, 0.0, 0.0)
                }
                transform to path.getAttribute("android:pathData")
            }
    }

    /**
     * Every on- and off-curve coordinate in [data]. For a quadratic or cubic
     * segment the control points bound the drawn curve from outside, so a point
     * set inside the safe circle proves the ink is too.
     */
    private fun pathPoints(data: String): List<Pair<Double, Double>> {
        val points = mutableListOf<Pair<Double, Double>>()
        var x = 0.0
        var y = 0.0
        Regex("[A-Za-z][^A-Za-z]*").findAll(data).forEach { segment ->
            val command = segment.value.first()
            val numbers = Regex("-?\\d+(?:\\.\\d+)?").findAll(segment.value)
                .map { it.value.toDouble() }
                .toList()
            require(command.isUpperCase()) { "relative command '$command' is not handled" }
            when (command) {
                'Z' -> Unit
                'H' -> numbers.forEach { x = it; points += x to y }
                'V' -> numbers.forEach { y = it; points += x to y }
                else -> numbers.chunked(2).forEach { pair ->
                    x = pair[0]
                    y = pair[1]
                    points += x to y
                }
            }
        }
        return points
    }

    private data class GroupTransform(
        val scaleX: Double,
        val scaleY: Double,
        val translateX: Double,
        val translateY: Double,
    )

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun Node.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
}
