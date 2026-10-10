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
        store.isKioskMode = true
        store.isKioskDimWhenIdle = true
        store.isKioskBlankAtNight = true
        store.kioskBlankStartMinutes = 22 * 60
        store.kioskBlankEndMinutes = 6 * 60
        store.isWallpaperShown = true
        store.themeMode = ThemeMode.Dark

        val rows = bugReportSettingRows(store).toMap()

        assertEquals("false", rows["Keyboard auto-shown"])
        assertEquals("true", rows["Home widgets shown"])
        assertEquals("true", rows["Kiosk mode"])
        assertEquals("true", rows["Kiosk dim when idle"])
        assertEquals("true", rows["Kiosk blank at night"])
        assertEquals("22:00", rows["Kiosk blank from"])
        assertEquals("06:00", rows["Kiosk blank until"])
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
    fun rowsAreChoicesTogglesNumbersAndTimesOnly() {
        // Privacy floor: nothing that could name something of the user's. The
        // kiosk blank times are allowed (maintainer's call): they explain a
        // display that went dark.
        val allowed = Regex("""^(true|false|\d+px \([A-Za-z]+\)|[A-Z][A-Za-z]*|\d{2}:\d{2})$""")
        bugReportSettingRows(DockSettingsStore(context)).forEach { (name, value) ->
            assertTrue("$name has an unexpected value: $value", allowed.matches(value))
        }
    }
}
