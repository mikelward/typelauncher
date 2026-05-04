package app.typelauncher

import org.junit.Assert.assertEquals
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
        assertEquals("true", attrs.getNamedItem("android:clearTaskOnLaunch").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:excludeFromRecents").nodeValue)
        assertEquals("singleTask", attrs.getNamedItem("android:launchMode").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:stateNotNeeded").nodeValue)
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
    fun launcherIcon_localResourcesIncludeGoogleConstructionBadge() {
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
        assertTrue(localForeground.contains("Google Material Symbols \"construction\" icon"))
        assertTrue(localForeground.contains("M83,66 A17,17 0,1 1,83,100 A17,17 0,1 1,83,66"))
        assertTrue(localForeground.contains("android:scaleX=\"0.035416667\""))
        assertTrue(localForeground.contains("M756,-120 L537,-339l84,-84 219,219 -84,84"))
        assertTrue(localForeground.contains("#FFFFC107"))
        assertTrue(localMonochrome.contains("Google Material Symbols \"construction\" icon"))
        assertTrue(localMonochrome.contains("android:scaleX=\"0.035416667\""))
        assertTrue(localMonochrome.contains("M756,-120 L537,-339l84,-84 219,219 -84,84"))
        assertTrue(localMonochrome.contains("#FF000000"))
    }

    @Test
    fun mainActivity_sourceUsesComposeMaterialThemeAndLifecycleState() {
        val mainActivitySource = File("src/main/java/app/typelauncher/MainActivity.kt").readText()
        val typeLauncherAppSource = File("src/main/java/app/typelauncher/ui/TypeLauncherApp.kt").readText()
        val homeScreenSource = File("src/main/java/app/typelauncher/ui/HomeScreen.kt").readText()
        val themeSource = File("src/main/java/app/typelauncher/ui/TypeLauncherTheme.kt").readText()
        val viewModelSource = File("src/main/java/app/typelauncher/LauncherViewModel.kt").readText()

        assertTrue(mainActivitySource.contains("setContent"))
        assertTrue(themeSource.contains("MaterialTheme"))
        assertTrue(typeLauncherAppSource.contains("Scaffold"))
        assertTrue(homeScreenSource.contains("Card"))
        assertTrue(homeScreenSource.contains("OutlinedTextField"))
        assertTrue(typeLauncherAppSource.contains("collectAsStateWithLifecycle"))
        assertTrue(viewModelSource.contains("ViewModel"))
        assertTrue(themeSource.contains("dynamicLightColorScheme"))
        assertTrue(viewModelSource.contains("AgendaEventOrganizer.forNow"))
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
