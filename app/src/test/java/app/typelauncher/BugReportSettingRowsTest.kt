package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportSettingRowsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun rowsReflectStoredSettings() {
        val store = DockSettingsStore(context)
        store.isKeyboardAutoShown = false
        store.isHomeWidgetsShown = true
        store.isWallpaperShown = true
        store.themeMode = ThemeMode.Dark

        val rows = bugReportSettingRows(store).toMap()

        assertEquals("false", rows["Keyboard auto-shown"])
        assertEquals("true", rows["Home widgets shown"])
        assertEquals("true", rows["Wallpaper shown"])
        assertEquals("Dark", rows["Theme"])
    }

    @Test
    fun analyticsRowIsTheConsentGatedChoice() {
        val store = DockSettingsStore(context)
        // A legacy "on" with the consent question never answered collects nothing.
        store.isTelemetryEnabled = true
        assertEquals(
            (!TELEMETRY_REQUIRES_CONSENT).toString(),
            bugReportSettingRows(store).toMap()["Analytics enabled"],
        )
        store.recordTelemetryOptIn()
        assertEquals("true", bugReportSettingRows(store).toMap()["Analytics enabled"])
    }

    @Test
    fun rowsAreChoicesTogglesAndNumbersOnly() {
        // Privacy floor: nothing that could name something of the user's.
        val allowed = Regex("""^(true|false|\d+px \([A-Za-z]+\)|[A-Z][A-Za-z]*)$""")
        bugReportSettingRows(DockSettingsStore(context)).forEach { (name, value) ->
            assertTrue("$name has an unexpected value: $value", allowed.matches(value))
        }
    }
}
