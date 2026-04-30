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
        val searchContainer = layout.getElementsByTagName("FrameLayout").item(0)
        val input = layout.getElementsByTagName("EditText").item(0)
        val clearButton = layout.getElementsByTagName("ImageButton").item(0)
        val installedCard = layout.getElementsByTagName("LinearLayout").item(1)
        val dockCard = layout.getElementsByTagName("LinearLayout").item(2)
        val dockRow = layout.getElementsByTagName("LinearLayout").item(3)
        val list = layout.getElementsByTagName("ListView").item(0)
        val dockHint = layout.getElementsByTagName("TextView").item(0)
        val dockScroller = layout.getElementsByTagName("HorizontalScrollView").item(0)
        val searchContainerAttrs = searchContainer.attributes
        val attrs = input.attributes
        val clearButtonAttrs = clearButton.attributes
        val installedCardAttrs = installedCard.attributes
        val dockCardAttrs = dockCard.attributes
        val dockHintAttrs = dockHint.attributes
        val dockRowAttrs = dockRow.attributes
        val listAttrs = list.attributes
        val dockScrollerAttrs = dockScroller.attributes

        assertEquals("vertical", root.attributes.getNamedItem("android:orientation").nodeValue)
        assertEquals(searchContainer, root.elementChildren()[0])
        assertEquals(installedCard, root.elementChildren()[1])
        assertEquals(dockCard, root.elementChildren()[2])
        assertEquals("@+id/app_search_input_container", searchContainerAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("@+id/app_search_input", attrs.getNamedItem("android:id").nodeValue)
        assertEquals("1", attrs.getNamedItem("android:maxLines").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:singleLine").nodeValue)
        assertEquals("actionSearch", attrs.getNamedItem("android:imeOptions").nodeValue)
        assertEquals("text", attrs.getNamedItem("android:inputType").nodeValue)
        assertEquals("@string/app_search_hint", attrs.getNamedItem("android:hint").nodeValue)
        assertEquals("@+id/app_search_clear_button", clearButtonAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("gone", clearButtonAttrs.getNamedItem("android:visibility").nodeValue)
        assertEquals("@string/app_search_clear_button_description", clearButtonAttrs.getNamedItem("android:contentDescription").nodeValue)
        assertEquals("@+id/installed_apps_card", installedCardAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("0dp", installedCardAttrs.getNamedItem("android:layout_height").nodeValue)
        assertEquals("1", installedCardAttrs.getNamedItem("android:layout_weight").nodeValue)
        assertEquals("@+id/installed_apps_list", listAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("match_parent", listAttrs.getNamedItem("android:layout_height").nodeValue)
        assertEquals("@string/installed_apps_list_label", listAttrs.getNamedItem("android:contentDescription").nodeValue)
        assertEquals("@+id/docked_apps_card", dockCardAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("@+id/docked_apps_hint", dockHintAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("@string/dock_apps_hint", dockHintAttrs.getNamedItem("android:text").nodeValue)
        assertEquals("@string/dock_apps_list_label", dockScrollerAttrs.getNamedItem("android:contentDescription").nodeValue)
        assertEquals("@+id/docked_apps_list", dockRowAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("horizontal", dockRowAttrs.getNamedItem("android:orientation").nodeValue)
        assertEquals("56dp", dockRowAttrs.getNamedItem("android:layout_height").nodeValue)
        assertEquals("gone", dockRowAttrs.getNamedItem("android:visibility").nodeValue)
    }

    @Test
    fun mainActivity_queriesLauncherAppsAndResizesAboveKeyboard() {
        val manifest = parseManifest()
        val activity = manifest.getElementsByTagName("activity").item(0)
        val queryIntent = manifest.getElementsByTagName("queries").item(0).elementChildren().single()
        val queryElements = queryIntent.elementChildren()
        val attrs = activity.attributes

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
        assertTrue(source.contains("launchAndClearQuery(filteredApps[position].launchIntent, appSearchInput)"))
        assertTrue(source.contains("Intent.makeMainActivity"))
        assertTrue(source.contains("asLauncherTaskIntent"))
    }

    @Test
    fun mainActivity_longPressShowsStandardMenuWithAppInfoAndDockToggle() {
        val source = File("src/main/java/app/typelauncher/MainActivity.kt").readText()

        assertTrue(source.contains("PopupMenu(this, anchor)"))
        assertTrue(source.contains("R.string.app_menu_app_info"))
        assertTrue(source.contains("R.string.app_menu_dock"))
        assertTrue(source.contains("R.string.app_menu_undock"))
        assertTrue(source.contains("MENU_ITEM_APP_INFO"))
        assertTrue(source.contains("MENU_ITEM_TOGGLE_DOCK"))
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