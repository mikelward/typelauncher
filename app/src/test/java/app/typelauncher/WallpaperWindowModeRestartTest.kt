package app.typelauncher

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowWallpaperManager

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
 * intent before the activity exists. The wallpaper-offset tests are the one
 * addition past `onCreate`: they invoke the offset report with a hand-made
 * window token, exactly as `onAttachedToWindow` does with the real one, so the
 * centered-offset contract is pinned without attaching the full Compose window.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    qualifiers = "w411dp-h914dp-420dpi",
    shadows = [OffsetRecordingShadowWallpaperManager::class],
)
class WallpaperWindowModeRestartTest {

    @Before
    fun resetRecordedWallpaperOffsets() {
        OffsetRecordingShadowWallpaperManager.reset()
    }

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

    // The system pans a wallpaper crop wider than the display by the offsets
    // the wallpaper-target window reports, and a window that never reports any
    // is defaulted to the wallpaper's left edge (right edge in RTL), not its
    // center — which showed a picker-centered wallpaper shifted sideways
    // behind Home. Window attach is what hands `MainActivity` its window
    // token, so the tests exercise the report the way the attach callback
    // does: directly with a token, on a synchronous dispatcher (the production
    // report hops onto `ioDispatcher` because it is a Binder IPC on the
    // first-frame path).
    @Test
    fun wallpaperOffsetReport_centersWallpaperOnWindowToken() {
        DockSettingsStore(RuntimeEnvironment.getApplication()).isWallpaperShown = true

        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.ioDispatcher = Dispatchers.Unconfined
            val windowToken: IBinder = Binder()

            activity.reportCenteredWallpaperOffsets(windowToken)

            val offsets = OffsetRecordingShadowWallpaperManager.recordedOffsets
            assertTrue("offsets should be reported", offsets != null)
            assertSame("offsets should target the launcher window", windowToken, offsets!!.windowToken)
            assertEquals("horizontal offset should be centered", 0.5f, offsets.xOffset)
            assertEquals("vertical offset should be centered", 0.5f, offsets.yOffset)
            // Zero steps tell live wallpapers there are no pages to parallax
            // between — the launcher never scrolls the wallpaper.
            assertEquals(
                "offset steps should declare a single page",
                0f to 0f,
                OffsetRecordingShadowWallpaperManager.recordedOffsetSteps,
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun wallpaperOffsetReport_skippedWhileWallpaperOff() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val activity = controller.get()
            activity.ioDispatcher = Dispatchers.Unconfined

            activity.reportCenteredWallpaperOffsets(Binder())

            // With `Show wallpaper` off the window has no FLAG_SHOW_WALLPAPER,
            // so nothing composites a wallpaper and the Binder IPC is skipped
            // outright.
            assertNull(
                "no offsets should be reported",
                OffsetRecordingShadowWallpaperManager.recordedOffsets,
            )
            assertNull(
                "no offset steps should be reported",
                OffsetRecordingShadowWallpaperManager.recordedOffsetSteps,
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

/**
 * Extends Robolectric's stock [ShadowWallpaperManager] (which no-ops but does
 * not record the offset APIs) to capture the wallpaper-offset reports
 * `MainActivity.reportCenteredWallpaperOffsets` makes, so tests can assert on
 * the centered values instead of the call disappearing into the stub window
 * session.
 */
@Implements(WallpaperManager::class)
class OffsetRecordingShadowWallpaperManager : ShadowWallpaperManager() {

    data class OffsetReport(val windowToken: IBinder, val xOffset: Float, val yOffset: Float)

    @Implementation
    fun setWallpaperOffsetSteps(xStep: Float, yStep: Float) {
        recordedOffsetSteps = xStep to yStep
    }

    @Implementation
    fun setWallpaperOffsets(windowToken: IBinder, xOffset: Float, yOffset: Float) {
        recordedOffsets = OffsetReport(windowToken, xOffset, yOffset)
    }

    companion object {
        var recordedOffsetSteps: Pair<Float, Float>? = null
        var recordedOffsets: OffsetReport? = null

        fun reset() {
            recordedOffsetSteps = null
            recordedOffsets = null
        }
    }
}
