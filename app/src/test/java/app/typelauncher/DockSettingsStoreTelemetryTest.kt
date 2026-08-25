package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [DockSettingsStore.isTelemetryEnabled], the Settings → "Share crash
 * reports" opt-out. The default matters on its own: `PRIVACY.md` has always
 * declared anonymous crash reporting, so the toggle exists to let a user
 * decline it — flipping the default to off would silently stop reporting for
 * every existing install.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockSettingsStoreTelemetryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() =
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)

    @After
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun defaultsToEnabledWhenNothingPersisted() {
        assertTrue(DockSettingsStore(context).isTelemetryEnabled)
    }

    @Test
    fun optingOutPersistsAcrossStoreInstances() {
        DockSettingsStore(context).isTelemetryEnabled = false

        assertFalse(DockSettingsStore(context).isTelemetryEnabled)
    }

    @Test
    fun optingBackInPersists() {
        DockSettingsStore(context).isTelemetryEnabled = false
        DockSettingsStore(context).isTelemetryEnabled = true

        assertTrue(DockSettingsStore(context).isTelemetryEnabled)
    }
}
