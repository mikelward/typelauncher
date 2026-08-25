package app.typelauncher

import android.app.WallpaperManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the wiring between the colors callback and the Compose state the
 * contrast readers key on — that a system wallpaper change actually moves
 * [rememberWallpaperColorsGeneration]'s value, and that nothing is registered
 * while the wallpaper isn't being shown.
 *
 * Hosts the generation composable alone, deliberately. Composing the real
 * `SettingsScreen` with `isWallpaperShown = true` is not an option: it renders
 * through a transparent window and corrupts the next activity launch in its
 * class (see the comment on `SettingsScreenTest`). What the readers do with the
 * value is covered by the recorded screenshots and, for the contrast flip
 * itself, a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WallpaperColorsGenerationTest {
    @get:Rule
    val composeRule = createComposeRule()

    // Registration happens on Dispatchers.IO, which `waitForIdle` does not
    // synchronize, so both fields are @Volatile and the test waits on the
    // generation itself rather than assuming the effect has got there.
    private class FakeRegistrar : WallpaperColorsRegistrar {
        @Volatile
        var registered = false
            private set

        @Volatile
        private var callback: ((Int) -> Unit)? = null

        override fun register(onColorsChanged: (which: Int) -> Unit): (() -> Unit)? {
            callback = onColorsChanged
            registered = true
            return { registered = false }
        }

        fun emit(which: Int) {
            callback?.invoke(which)
        }
    }

    @Test
    fun aSystemWallpaperChangeMovesTheGeneration() {
        val registrar = FakeRegistrar()
        var generation = -1

        composeRule.setContent { generation = GenerationProbe(enabled = true, registrar = registrar) }

        // The establishment tick is the synchronization point: the flow emits
        // it only once the listener is actually live, so waiting for the value
        // to reach 1 means registration has completed — no sleeping on a race
        // between this thread and the IO dispatcher.
        composeRule.waitUntil(timeoutMillis = 5_000) { generation == 1 }
        assertTrue("The wallpaper is shown, so the listener is registered", registrar.registered)

        registrar.emit(WallpaperManager.FLAG_SYSTEM)

        // The value itself is the contract: both contrast readers key their
        // effects on it, so a change that doesn't move it is a change they
        // never see.
        composeRule.waitUntil(timeoutMillis = 5_000) { generation == 2 }

        registrar.emit(WallpaperManager.FLAG_SYSTEM)
        composeRule.waitUntil(timeoutMillis = 5_000) { generation == 3 }
    }

    @Test
    fun nothingIsRegisteredWhileTheWallpaperIsHidden() {
        val registrar = FakeRegistrar()
        var generation = -1

        composeRule.setContent { generation = GenerationProbe(enabled = false, registrar = registrar) }
        composeRule.waitForIdle()

        // Nothing to wait for here — the assertion is that no registration is
        // ever attempted, so a value that stayed 0 is the whole point. If the
        // effect ever did register, the establishment tick would move it and
        // this would fail rather than pass by timing.
        assertFalse("No wallpaper backdrop means no listener at all", registrar.registered)
        assertEquals("...and the value stays put", 0, generation)
    }

    @Composable
    private fun GenerationProbe(enabled: Boolean, registrar: WallpaperColorsRegistrar): Int =
        rememberWallpaperColorsGeneration(enabled = enabled, registrar = registrar)
}
