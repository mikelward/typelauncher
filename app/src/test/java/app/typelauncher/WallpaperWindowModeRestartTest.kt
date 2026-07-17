package app.typelauncher

import android.content.Intent
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Covers the launch-time half of the "Show wallpaper" window contract. A
 * runtime toggle never patches the live window — it finishes the activity and
 * starts a fresh instance (`MainActivity.restartForWallpaperWindowMode`),
 * because both a live surface-format flip and an in-place `recreate()` left
 * the window compositing as opaque on-device, stranding the previous screen's
 * pixels in Home's transparent wallpaper slot. That makes the launch path the
 * only place the flag and surface format are ever applied, so it is what
 * these tests pin. The toggle-to-restart wiring itself is covered by
 * `MainActivityRobolectricScreenshotTest.wallpaperShownSetting_toggleRestartsThroughFreshLaunch`.
 *
 * Uses `Robolectric.buildActivity` + `create()` directly (no compose rule):
 * everything asserted here is decided in `onCreate`, and building the activity
 * by hand is what lets each test choose the persisted preference and launch
 * intent before the activity exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
class WallpaperWindowModeRestartTest {

    @Test
    fun launchWithWallpaperOn_buildsWallpaperWindowFromFirstFrame() {
        DockSettingsStore(RuntimeEnvironment.getApplication()).isWallpaperShown = true

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val attributes = controller.get().window.attributes
            assertTrue(
                "window should request FLAG_SHOW_WALLPAPER",
                attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER != 0,
            )
            // The translucent format is what makes the renderer clear the
            // surface every frame; an opaque surface would strand whatever was
            // last drawn in Home's transparent wallpaper slot.
            assertEquals(
                "surface format with wallpaper on",
                PixelFormat.TRANSLUCENT,
                attributes.format,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun launchWithWallpaperOff_keepsOpaqueWindow() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val attributes = controller.get().window.attributes
            assertTrue(
                "window should not request FLAG_SHOW_WALLPAPER",
                attributes.flags and WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER == 0,
            )
            assertEquals(
                "surface format with wallpaper off",
                PixelFormat.OPAQUE,
                attributes.format,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun reopenSettingsExtra_opensSettingsOnFreshLaunch() {
        val application = RuntimeEnvironment.getApplication()
        val intent = Intent(application, MainActivity::class.java)
            .putExtra(EXTRA_REOPEN_SETTINGS, true)

        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        try {
            assertTrue(
                "restarted instance should land back in Settings",
                controller.get().viewModel.uiState.value.isSettingsOpen,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun reopenSettingsExtra_ignoredWhenRestoringInstanceState() {
        val application = RuntimeEnvironment.getApplication()
        val intent = Intent(application, MainActivity::class.java)
            .putExtra(EXTRA_REOPEN_SETTINGS, true)

        // A configuration change redelivers the original intent with a saved
        // instance state; the extra must only apply to the genuinely fresh
        // start, or Settings would reopen on every rotation after the user
        // closed them.
        val controller = Robolectric
            .buildActivity(MainActivity::class.java, intent)
            .create(Bundle())
        try {
            assertFalse(
                "redelivered extra should not reopen Settings",
                controller.get().viewModel.uiState.value.isSettingsOpen,
            )
        } finally {
            controller.destroy()
        }
    }
}
