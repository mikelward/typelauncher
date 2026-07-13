package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * Guards the fix for widget backup/restore leaving every widget dead on a
 * restored device. App-widget IDs are device-local — they index into this
 * device's `AppWidgetService`, which has no matching bindings on another
 * device — so the `widgets` SharedPreferences must be excluded from both
 * cloud backup and direct device-to-device transfer. Restoring it would
 * render every widget as a permanent "Widget unavailable" card and its stale
 * IDs could collide with IDs the fresh device's host later allocates.
 *
 * The test parses the shipped `data_extraction_rules` resource (the operative
 * one on the app's minSdk) and fails if the exclusion is dropped or the
 * prefs file is renamed out from under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetBackupExclusionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun widgetPrefsAreExcludedFromCloudBackupAndDeviceTransfer() {
        // The path attribute is the SharedPreferences file name; keep the test
        // pinned to the real store name so a rename can't silently re-expose it.
        val prefsFileName = "widgets.xml"

        val sectionsExcludingWidgets = mutableSetOf<String>()
        val parser = context.resources.getXml(R.xml.data_extraction_rules)
        var currentSection: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "cloud-backup", "device-transfer" -> currentSection = parser.name
                    "exclude" -> {
                        val domain = parser.getAttributeValue(null, "domain")
                        val path = parser.getAttributeValue(null, "path")
                        if (domain == "sharedpref" && path == prefsFileName && currentSection != null) {
                            sectionsExcludingWidgets.add(currentSection!!)
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "cloud-backup" || parser.name == "device-transfer") {
                    currentSection = null
                }
            }
            event = parser.next()
        }

        assertTrue(
            "widgets.xml must be excluded from cloud-backup (found: $sectionsExcludingWidgets)",
            "cloud-backup" in sectionsExcludingWidgets,
        )
        assertTrue(
            "widgets.xml must be excluded from device-transfer (found: $sectionsExcludingWidgets)",
            "device-transfer" in sectionsExcludingWidgets,
        )
    }
}
