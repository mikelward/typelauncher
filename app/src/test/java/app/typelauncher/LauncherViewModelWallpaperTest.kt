package app.typelauncher

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Coverage for Settings -> Wallpaper -> Change. The launcher cannot set the
 * wallpaper itself, so the whole behavior is the hand-off: fire
 * `ACTION_SET_WALLPAPER`, and — on a device that ships no handler for it — say
 * so instead of throwing, since an unguarded start crashes the launcher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelWallpaperTest {
    private val application = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun clearPrefs() {
        application.getSharedPreferences("dock_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun openWallpaperPickerStartsTheSystemPicker() {
        val viewModel = newViewModel()
        idle()

        viewModel.openWallpaperPicker()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(
            "Change wallpaper hands off to whichever app owns ACTION_SET_WALLPAPER",
            Intent.ACTION_SET_WALLPAPER,
            started?.action,
        )
        // Settings runs inside the launcher's own (home) task, so the picker
        // needs its own task exactly like the overflow menu's App info does.
        assertTrue(
            "The picker opens in its own task, not stacked on the home task",
            (started?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun openWallpaperPickerWithNoHandlerToastsInsteadOfCrashing() {
        // Robolectric only enforces resolvability when asked to; without this
        // the shadow accepts any intent and the guard is never exercised.
        shadowOf(application).checkActivities(true)
        val viewModel = newViewModel()
        idle()

        viewModel.openWallpaperPicker()

        assertNull(
            "A device with no wallpaper picker starts nothing",
            shadowOf(application).nextStartedActivity,
        )
        assertEquals(
            "The user is told why the tap did nothing",
            application.getString(R.string.settings_wallpaper_picker_unavailable),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    @Config(application = DeniedStartApplication::class)
    fun openWallpaperPickerWhenTheLaunchIsDeniedToastsInsteadOfCrashing() {
        val viewModel = newViewModel()
        idle()

        // Must not throw: an OEM picker that resolves but sits behind a
        // permission this launcher doesn't hold takes the same path as a
        // device with no picker at all.
        viewModel.openWallpaperPicker()

        assertEquals(
            "A denied launch is reported the same way as a missing one",
            application.getString(R.string.settings_wallpaper_picker_unavailable),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    /**
     * Stands in for an OEM build whose `ACTION_SET_WALLPAPER` activity
     * resolves but is permission-protected — `startActivity` throws
     * `SecurityException` there rather than no-opping, and Robolectric's
     * shadow has no other seam for that.
     */
    class DeniedStartApplication : Application() {
        override fun startActivity(intent: Intent) {
            throw SecurityException("test: not permitted to start ${intent.action}")
        }

        override fun startActivity(intent: Intent, options: android.os.Bundle?) {
            throw SecurityException("test: not permitted to start ${intent.action}")
        }
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = ApplicationProvider.getApplicationContext(),
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
