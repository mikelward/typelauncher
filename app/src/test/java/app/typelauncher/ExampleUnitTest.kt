package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExampleUnitTest {
    @Test
    fun launcherLayout_showsAgendaAndHomePagesInScreenSwitcher() {
        val layout = parseMainLayout()
        val root = layout.documentElement
        val children = root.elementChildren()

        assertEquals("app.typelauncher.LauncherScreenSwitcher", root.nodeName)
        assertEquals("@+id/launcher_screen_switcher", root.attributes.getNamedItem("android:id").nodeValue)
        assertEquals(2, children.size)
        assertEquals("include", children[0].nodeName)
        assertEquals("@layout/launcher_agenda_page", children[0].attributes.getNamedItem("layout").nodeValue)
        assertEquals("include", children[1].nodeName)
        assertEquals("@layout/launcher_home_page", children[1].attributes.getNamedItem("layout").nodeValue)
    }

    @Test
    fun homeLayout_containsSearchClearInstalledAndDockSections() {
        val layout = parseLayout("src/main/res/layout/launcher_home_page.xml")
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
        val workBadge = parseInstalledAppListItem().getElementsByTagName("ImageView").item(0)
        val searchContainerAttrs = searchContainer.attributes
        val attrs = input.attributes
        val clearButtonAttrs = clearButton.attributes
        val installedCardAttrs = installedCard.attributes
        val dockCardAttrs = dockCard.attributes
        val dockHintAttrs = dockHint.attributes
        val dockRowAttrs = dockRow.attributes
        val listAttrs = list.attributes
        val dockScrollerAttrs = dockScroller.attributes
        val workBadgeAttrs = workBadge.attributes

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
        assertEquals("@+id/work_app_badge", workBadgeAttrs.getNamedItem("android:id").nodeValue)
        assertEquals("36dp", workBadgeAttrs.getNamedItem("android:layout_width").nodeValue)
        assertEquals("6dp", workBadgeAttrs.getNamedItem("android:layout_marginEnd").nodeValue)
        assertEquals("@string/work_app_badge_description", workBadgeAttrs.getNamedItem("android:contentDescription").nodeValue)
    }

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
    fun mainActivity_containsAgendaSwipeAndDockHooks() {
        val source = File("src/main/java/app/typelauncher/MainActivity.kt").readText()

        assertTrue(source.contains("launcher_screen_switcher"))
        assertTrue(source.contains("LauncherScreenSwitcher"))
        assertTrue(source.contains("tryShowAgendaScreen"))
        assertTrue(source.contains("CalendarContract.Instances"))
        assertTrue(source.contains("AgendaEventOrganizer.forNow"))
        assertTrue(source.contains("ActivityResultContracts.RequestPermission"))
        assertTrue(source.contains("renderDockedApps"))
        assertTrue(source.contains("launchAndClearQuery"))
        assertTrue(source.contains("R.string.app_menu_dock"))
    }

    private fun parseMainLayout() = parseLayout("src/main/res/layout/activity_main.xml")

    private fun parseLayout(path: String) = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File(path))

    private fun parseInstalledAppListItem() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/res/layout/installed_app_list_item.xml"))

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))

    private fun Node.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }
        .filterIsInstance<Element>()
}
