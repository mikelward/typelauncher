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
        val activity = manifest.getElementsByTagName("activity").item(0)
        val queryIntent = manifest.getElementsByTagName("queries").item(0).elementChildren().single()
        val queryElements = queryIntent.elementChildren()
        val attrs = activity.attributes
        val permissions = manifest.getElementsByTagName("uses-permission")
        val names = (0 until permissions.length)
            .map { index -> permissions.item(index).attributes.getNamedItem("android:name").nodeValue }

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
    }

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun Node.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
}
