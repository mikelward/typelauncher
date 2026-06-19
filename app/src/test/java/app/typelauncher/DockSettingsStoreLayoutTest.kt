package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers [DockSettingsStore.appListLayout] persistence and the one-time
 * migration from the pre-enum `app_list_icon_only` boolean.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DockSettingsStoreLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun prefs() =
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE)

    @After
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun defaultsToNameBesideWhenNothingPersisted() {
        assertEquals(AppListLayout.NameBeside, DockSettingsStore(context).appListLayout)
    }

    @Test
    fun roundTripsEachEnumValue() {
        for (layout in AppListLayout.values()) {
            DockSettingsStore(context).appListLayout = layout
            assertEquals(layout, DockSettingsStore(context).appListLayout)
        }
    }

    @Test
    fun migratesLegacyIconOnlyTrueToIconOnly() {
        prefs().edit().putBoolean("app_list_icon_only", true).commit()
        assertEquals(AppListLayout.IconOnly, DockSettingsStore(context).appListLayout)
    }

    @Test
    fun migratesLegacyIconOnlyFalseToNameBeside() {
        prefs().edit().putBoolean("app_list_icon_only", false).commit()
        assertEquals(AppListLayout.NameBeside, DockSettingsStore(context).appListLayout)
    }

    @Test
    fun writtenEnumWinsOverLegacyBoolean() {
        // Once the user picks a layout the new key takes precedence even if the
        // stale legacy boolean is still around.
        prefs().edit().putBoolean("app_list_icon_only", true).commit()
        DockSettingsStore(context).appListLayout = AppListLayout.NameBelow
        assertEquals(AppListLayout.NameBelow, DockSettingsStore(context).appListLayout)
    }

    @Test
    fun unknownPersistedNameFallsBackToNameBeside() {
        prefs().edit().putString("app_list_layout", "SomeFutureLayout").commit()
        assertEquals(AppListLayout.NameBeside, DockSettingsStore(context).appListLayout)
    }

    @Test
    fun dockLayoutDefaultsToIconOnlyWhenNothingPersisted() {
        assertEquals(DockLayout.IconOnly, DockSettingsStore(context).dockLayout)
    }

    @Test
    fun dockLayoutRoundTripsEachEnumValue() {
        for (layout in DockLayout.values()) {
            DockSettingsStore(context).dockLayout = layout
            assertEquals(layout, DockSettingsStore(context).dockLayout)
        }
    }

    @Test
    fun dockLayoutUnknownPersistedNameFallsBackToIconOnly() {
        prefs().edit().putString("dock_layout", "SomeFutureLayout").commit()
        assertEquals(DockLayout.IconOnly, DockSettingsStore(context).dockLayout)
    }
}
