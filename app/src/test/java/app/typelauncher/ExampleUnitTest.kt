package app.typelauncher

import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun launcherLayout_showsSearchThenInstalledAppsList() {
        val layout = parseLayout()
        val root = layout.documentElement
        val input = layout.getElementsByTagName("EditText").item(0)
        val list = layout.getElementsByTagName("ListView").item(0)
        val attrs = input.attributes
        val listAttrs = list.attributes

        assertEquals("vertical", root.attributes.getNamedItem("android:orientation").nodeValue)
        assertEquals(input, root.elementChildren()[0])
        assertEquals(list, root.elementChildren()[1])
        assertEquals("@+id/app_search_input", attrs.getNamedItem("android:id").nodeValue)
        assertEquals("1", attrs.getNamedItem("android:maxLines").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:singleLine").nodeValue)
        assertEquals("actionSearch", attrs.getNamedItem("android:imeOptions").nodeValue)
        assertEquals("text", attrs.getNamedItem("android:inputType").nodeValue)
        assertEquals("@string/app_search_hint", attrs.getNamedItem("android:hint").nodeValue)
        assertEquals("@+id/installed_apps_list", listAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("0dp", listAttrs.getNamedItem("android:layout_height").nodeValue)
        assertEquals("1", listAttrs.getNamedItem("android:layout_weight").nodeValue)
        assertEquals("@string/installed_apps_list_label", listAttrs.getNamedItem("android:contentDescription").nodeValue)
    }

    @Test
    fun mainActivity_queriesLauncherAppsAndResizesAboveKeyboard() {
        val manifest = parseManifest()
        val activity = manifest.getElementsByTagName("activity").item(0)
        val queryIntent = manifest.getElementsByTagName("queries").item(0).elementChildren().single()
        val queryElements = queryIntent.elementChildren()
        val attrs = activity.attributes

        assertEquals("stateAlwaysVisible|adjustResize", attrs.getNamedItem("android:windowSoftInputMode").nodeValue)
        assertEquals("intent", queryIntent.nodeName)
        assertEquals("action", queryElements[0].nodeName)
        assertEquals("android.intent.action.MAIN", queryElements[0].attributes.getNamedItem("android:name").nodeValue)
        assertEquals("category", queryElements[1].nodeName)
        assertEquals("android.intent.category.LAUNCHER", queryElements[1].attributes.getNamedItem("android:name").nodeValue)
    }

    @Test
    fun mainLayout_hasRootViewForImeInsetsDispatch() {
        val layout = parseLayout()
        val root = layout.documentElement
        assertEquals("@+id/main_root", root.attributes.getNamedItem("android:id").nodeValue)
    }

    @Test
    fun mainActivity_launchesTappedAppListItem() {
        val source = File("src/main/java/app/typelauncher/MainActivity.kt").readText()

        assertTrue(source.contains("setOnItemClickListener"))
        assertTrue(source.contains("startActivity(filteredApps[position].launchIntent)"))
        assertTrue(source.contains("Intent.makeMainActivity"))
    }

    @Test
    fun mainActivity_launchesSettingsFromSearchAction() {
        val source = File("src/main/java/app/typelauncher/MainActivity.kt").readText()

        assertTrue(source.contains("setOnEditorActionListener"))
        assertTrue(source.contains("EditorInfo.IME_ACTION_SEARCH"))
        assertTrue(source.contains("KeyEvent.KEYCODE_ENTER"))
        assertTrue(source.contains("SETTINGS_QUERY = \"settings\""))
        assertTrue(source.contains("Settings.ACTION_SETTINGS"))
        assertTrue(source.contains("ignoreCase = true"))
        assertTrue(source.contains("trim()"))
    }

    private fun parseLayout() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/res/layout/activity_main.xml"))

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun Node.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
}