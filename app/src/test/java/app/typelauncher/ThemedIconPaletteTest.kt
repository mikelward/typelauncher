package app.typelauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers the themed (monochrome) icon palette following the launcher's own
 * resolved theme — the Settings `Theme` mode combined with the device night
 * mode — rather than `Configuration.UI_MODE_NIGHT_MASK` alone. Regression
 * coverage for monochrome icons rendering light plates on a dark launcher
 * whenever the `Theme` override disagreed with the system night mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ThemedIconPaletteTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun resetLoaderAndPrefs() {
        // AppIconLoader is process-wide and Robolectric doesn't reset it
        // between methods, so restore the defaults the production cold start
        // assumes (and sibling tests may rely on).
        AppIconLoader.iconTheme = IconTheme.Default
        AppIconLoader.setThemedIconsUseDarkPalette(false)
        AppIconLoader.evictAll()
        listOf(
            "docked_apps",
            "dock_settings",
            "app_launch_stats",
            "widgets",
            "app_metadata",
            "hidden_apps",
            "renamed_apps",
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun themedIconColorsFollowResolvedLauncherThemeNotNightMask() {
        // Robolectric's default configuration is notnight, so a palette read
        // from the night mask would stay light here. The mirror must win.
        AppIconLoader.setThemedIconsUseDarkPalette(true)
        val dark = AppIconLoader.themedIconColors(context)
        assertEquals(
            "dark launcher theme must pick the dark (neutral) plate even on a light-mode device",
            context.getColor(android.R.color.system_neutral1_800),
            dark.plate,
        )
        assertEquals(context.getColor(android.R.color.system_accent1_100), dark.glyph)

        AppIconLoader.setThemedIconsUseDarkPalette(false)
        val light = AppIconLoader.themedIconColors(context)
        assertEquals(context.getColor(android.R.color.system_accent1_100), light.plate)
        assertEquals(context.getColor(android.R.color.system_accent1_700), light.glyph)
    }

    @Test
    fun resolvedThemeChangeUnderMonochromeEvictsIconCache() {
        AppIconLoader.iconTheme = IconTheme.Monochrome
        AppIconLoader.setThemedIconsUseDarkPalette(false)
        val id = "0:app.typelauncher.palettetest.evicted/.LaunchActivity"
        AppIconLoader.put(id, SIZE_PX, testBitmap())
        val generationBefore = AppIconLoader.cacheGenerationValue

        AppIconLoader.setThemedIconsUseDarkPalette(true)

        assertTrue(AppIconLoader.themedIconsUseDarkPalette)
        assertNull(
            "a resolved-theme flip under Monochrome must drop cached tiles so plates re-rasterize",
            AppIconLoader.cached(id, SIZE_PX),
        )
        assertTrue(
            "eviction must bump the cache generation so on-screen icons repaint",
            AppIconLoader.cacheGenerationValue > generationBefore,
        )
    }

    @Test
    fun unchangedResolvedThemeKeepsIconCache() {
        AppIconLoader.iconTheme = IconTheme.Monochrome
        AppIconLoader.setThemedIconsUseDarkPalette(true)
        val id = "0:app.typelauncher.palettetest.unchanged/.LaunchActivity"
        AppIconLoader.put(id, SIZE_PX, testBitmap())

        AppIconLoader.setThemedIconsUseDarkPalette(true)

        assertNotNull(
            "re-seeding the same resolved value must not evict anything",
            AppIconLoader.cached(id, SIZE_PX),
        )
    }

    @Test
    fun resolvedThemeChangeUnderDefaultThemeKeepsIconCache() {
        AppIconLoader.iconTheme = IconTheme.Default
        AppIconLoader.setThemedIconsUseDarkPalette(false)
        val id = "0:app.typelauncher.palettetest.defaulttheme/.LaunchActivity"
        AppIconLoader.put(id, SIZE_PX, testBitmap())

        AppIconLoader.setThemedIconsUseDarkPalette(true)

        assertTrue(AppIconLoader.themedIconsUseDarkPalette)
        assertNotNull(
            "Default-theme tiles carry no themed plate, so a theme flip must not evict them",
            AppIconLoader.cached(id, SIZE_PX),
        )
    }

    @Test
    fun setThemeModeDarkOnLightDeviceFlipsLoaderToDarkPalette() {
        val viewModel = newViewModel()
        idle()
        assertFalse(
            "Theme=System on a notnight device must resolve light",
            AppIconLoader.themedIconsUseDarkPalette,
        )

        viewModel.setThemeMode(ThemeMode.Dark)
        assertTrue(
            "Theme=Dark must flip the loader to the dark palette even on a light-mode device",
            AppIconLoader.themedIconsUseDarkPalette,
        )

        viewModel.setThemeMode(ThemeMode.Light)
        assertFalse(AppIconLoader.themedIconsUseDarkPalette)
    }

    @Test
    fun setThemeModeLightOnDarkDeviceKeepsLightPalette() {
        RuntimeEnvironment.setQualifiers("+night")
        val viewModel = newViewModel()
        idle()
        assertTrue(
            "Theme=System on a night-mode device must resolve dark",
            AppIconLoader.themedIconsUseDarkPalette,
        )

        viewModel.setThemeMode(ThemeMode.Light)
        assertFalse(
            "Theme=Light must pin the light palette even on a night-mode device",
            AppIconLoader.themedIconsUseDarkPalette,
        )
    }

    @Test
    fun viewModelInitSeedsPaletteFromPersistedThemeMode() {
        DockSettingsStore(context).themeMode = ThemeMode.Dark

        newViewModel()
        idle()

        assertTrue(
            "init must seed the loader from the persisted Theme setting before the first load",
            AppIconLoader.themedIconsUseDarkPalette,
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun testBitmap(): ImageBitmap =
        Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.MAGENTA) }
            .asImageBitmap()

    private companion object {
        const val SIZE_PX = 24
    }
}
