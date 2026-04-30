package app.typelauncher

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun launcherInput_isSingleLineSearchField() {
        val layout = parseLayout()
        val input = layout.getElementsByTagName("EditText").item(0)
        val attrs = input.attributes

        assertEquals("@+id/app_search_input", attrs.getNamedItem("android:id").nodeValue)
        assertEquals("1", attrs.getNamedItem("android:maxLines").nodeValue)
        assertEquals("true", attrs.getNamedItem("android:singleLine").nodeValue)
        assertEquals("actionSearch", attrs.getNamedItem("android:imeOptions").nodeValue)
        assertEquals("text", attrs.getNamedItem("android:inputType").nodeValue)
        assertEquals("@string/app_search_hint", attrs.getNamedItem("android:hint").nodeValue)
    }

    @Test
    fun mainActivity_requestsKeyboardOnOpen() {
        val manifest = parseManifest()
        val activity = manifest.getElementsByTagName("activity").item(0)
        val attrs = activity.attributes

        assertEquals("stateAlwaysVisible", attrs.getNamedItem("android:windowSoftInputMode").nodeValue)
    }

    private fun parseLayout() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/res/layout/activity_main.xml"))

    private fun parseManifest() = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(File("src/main/AndroidManifest.xml"))
}