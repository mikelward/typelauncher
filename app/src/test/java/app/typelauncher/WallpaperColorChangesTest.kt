package app.typelauncher

import android.app.WallpaperManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the wallpaper-colors refresh that keeps Settings' bare text and
 * the system-bar icons legible after the wallpaper changes.
 *
 * Drives [wallpaperColorChanges] through a fake [WallpaperColorsRegistrar]
 * rather than the framework: Robolectric's `ShadowWallpaperManager` has no
 * colors API, so the real callback can't be dispatched from a JVM test. The
 * seam puts everything worth testing — which changes count, and whether the
 * registration is released — above the framework call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WallpaperColorChangesTest {
    /** Records registration state and lets the test fire callbacks by hand. */
    private class FakeRegistrar(private val available: Boolean = true) : WallpaperColorsRegistrar {
        var registered = false
            private set
        var unregisterCount = 0
            private set
        private var callback: ((Int) -> Unit)? = null

        override fun register(onColorsChanged: (which: Int) -> Unit): (() -> Unit)? {
            if (!available) return null
            registered = true
            callback = onColorsChanged
            return {
                registered = false
                unregisterCount++
            }
        }

        fun emit(which: Int) {
            callback?.invoke(which)
        }
    }

    @Test
    fun systemWallpaperChangeEmits() = runTest {
        val registrar = FakeRegistrar()

        val changes = mutableListOf<Unit>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            wallpaperColorChanges(registrar).collect { changes += it }
        }
        assertTrue("The flow registers as soon as it is collected", registrar.registered)
        assertEquals("Establishing the subscription ticks once on its own", 1, changes.size)

        registrar.emit(WallpaperManager.FLAG_SYSTEM)
        testScheduler.advanceUntilIdle()

        assertEquals("A system wallpaper change ticks the refresh", 2, changes.size)
        collection.cancel()
    }

    // The lock-screen wallpaper never backs the launcher's window, so its
    // colors say nothing about this contrast — ticking on it would re-read the
    // hint (and repaint the bars) for a change the user cannot see here.
    @Test
    fun lockScreenOnlyChangeIsIgnored() = runTest {
        val registrar = FakeRegistrar()

        val changes = mutableListOf<Unit>()
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            wallpaperColorChanges(registrar).collect { changes += it }
        }

        // Drop the establishment tick; what this test is about is what happens
        // to changes arriving after the listener is live.
        testScheduler.advanceUntilIdle()
        val afterSubscribing = changes.size

        registrar.emit(WallpaperManager.FLAG_LOCK)
        testScheduler.advanceUntilIdle()

        assertEquals(
            "A lock-screen-only change must not tick the refresh",
            afterSubscribing,
            changes.size,
        )

        // Same registration, a system change after it: proves the filter is on
        // the flag rather than the flow having quietly stopped.
        registrar.emit(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        testScheduler.advanceUntilIdle()
        assertEquals("A change covering both surfaces still ticks", afterSubscribing + 1, changes.size)
        collection.cancel()
    }

    // The regression this guards is a listener outliving the screen: before the
    // flow owned the lifecycle, a cancel arriving mid-registration could leave
    // the callback registered for the life of the process.
    @Test
    fun cancellationReleasesTheRegistration() = runTest {
        val registrar = FakeRegistrar()

        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            wallpaperColorChanges(registrar).collect { }
        }
        assertTrue(registrar.registered)

        collection.cancel()
        testScheduler.advanceUntilIdle()

        assertFalse("Cancelling the collector unregisters the listener", registrar.registered)
        assertEquals("...exactly once", 1, registrar.unregisterCount)
    }

    @Test
    fun completingTheFlowReleasesTheRegistration() = runTest {
        val registrar = FakeRegistrar()

        // Two: the establishment tick, then a real change — so this covers a
        // collector that stops of its own accord after seeing something.
        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            wallpaperColorChanges(registrar).take(2).toList()
        }
        registrar.emit(WallpaperManager.FLAG_SYSTEM)
        testScheduler.advanceUntilIdle()
        collection.join()

        assertFalse("A collector that stops after one change releases it too", registrar.registered)
        assertEquals(1, registrar.unregisterCount)
    }

    // A device whose framework has no colors callback: the flow finishes
    // instead of hanging, so the caller keeps the contrast it already resolved.
    @Test
    fun anUnavailableCallbackCompletesWithoutEmitting() = runTest {
        val registrar = FakeRegistrar(available = false)

        val changes = wallpaperColorChanges(registrar).toList()

        assertTrue("No callback means no ticks — not even the establishment one — and no hang", changes.isEmpty())
        assertEquals("Nothing was registered, so nothing is released", 0, registrar.unregisterCount)
    }
}
