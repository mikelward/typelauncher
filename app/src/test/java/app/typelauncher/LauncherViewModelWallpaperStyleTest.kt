package app.typelauncher

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `openWallpaperAndStyle` deep-links to the system "Wallpaper & style" screen
 * (which hosts the Material You color picker the launcher's UI chrome and
 * monochrome icons already follow) via the public ACTION_SET_WALLPAPER intent,
 * launched as a fresh launcher task.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LauncherViewModelWallpaperStyleTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun openWallpaperAndStyleStartsSetWallpaperIntent() {
        val viewModel = newViewModel()

        viewModel.openWallpaperAndStyle()

        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_SET_WALLPAPER, started.action)
        assertTrue(
            "Must launch as a new task so it leaves the launcher's own task",
            started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    private fun newViewModel(): LauncherViewModel = LauncherViewModel(
        app = application,
        workPackages = emptySet(),
        ioDispatcher = Dispatchers.Unconfined,
    )
}
