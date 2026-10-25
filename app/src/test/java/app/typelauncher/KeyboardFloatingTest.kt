package app.typelauncher

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers floating-keyboard detection (`observeKeyboardFloating`), its
 * persistence, and its effect on the landscape fit: a keyboard that takes no
 * screen space keeps the landscape search box.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KeyboardFloatingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.getSharedPreferences("dock_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun visibleKeyboardWithNoInsetIsFloating() {
        assertEquals(true, observeKeyboardFloating(imeVisible = true, imeBottomPx = 0, imeTargetBottomPx = 0, navBottomPx = 0))
        // At or below the navigation bar is still no keyboard space.
        assertEquals(true, observeKeyboardFloating(imeVisible = true, imeBottomPx = 63, imeTargetBottomPx = 63, navBottomPx = 63))
    }

    @Test
    fun visibleKeyboardAboveTheNavBarIsDocked() {
        assertEquals(false, observeKeyboardFloating(imeVisible = true, imeBottomPx = 900, imeTargetBottomPx = 900, navBottomPx = 63))
    }

    @Test
    fun hiddenKeyboardIsNoObservation() {
        assertNull(observeKeyboardFloating(imeVisible = false, imeBottomPx = 0, imeTargetBottomPx = 0, navBottomPx = 0))
    }

    @Test
    fun dockedKeyboardStartingToOpenIsNotFloating() {
        // First frames of a docked open: visible, inset still 0, target already
        // at the keyboard's height. That must not read as floating.
        assertNull(observeKeyboardFloating(imeVisible = true, imeBottomPx = 0, imeTargetBottomPx = 900, navBottomPx = 0))
    }

    @Test
    fun storeDefaultsToDockedAndPersists() {
        val store = DockSettingsStore(context)
        assertFalse(store.isKeyboardFloating)
        store.isKeyboardFloating = true
        assertTrue(DockSettingsStore(context).isKeyboardFloating)
    }

    @Test
    fun floatingKeyboardKeepsTheLandscapeSearchBox() {
        // A phone landscape with a measured docked keyboard too tall for the
        // box + keyboard + one row: the box is hidden. Floating, it fits.
        fun metrics(isKeyboardFloating: Boolean): HomeLandscapeMetrics {
            val fingerprint = KeyboardReservationConfig(
                orientation = 2,
                screenWidthDp = 851,
                screenHeightDp = 393,
                densityDpi = 420,
                navBottomPx = 0,
            )
            return homeLandscapeMetrics(
                screenWidthDp = 851,
                screenHeightDp = 393,
                densityDpi = 420,
                targetDockIconSizeDp = dockIconSizeForSlotCount(393, 4),
                isPersonalDockEnabled = true,
                personalDockOccupantIds = listOf("a", "b"),
                isWorkDockVisible = false,
                workDockedAppIds = emptyList(),
                workDockPositions = emptyMap(),
                keyboardReservation = KeyboardReservation(
                    bottomPx = 700,
                    configFingerprint = fingerprint,
                    source = KeyboardReservationSource.VisibleIme,
                ),
                reservationFingerprint = fingerprint,
                isKeyboardFloating = isKeyboardFloating,
            )
        }
        val docked = metrics(isKeyboardFloating = false)
        assertFalse(docked.searchBoxFitsWithKeyboard)
        val floating = metrics(isKeyboardFloating = true)
        assertTrue(floating.searchBoxFitsWithKeyboard)
        assertEquals(0, floating.predictedKeyboardHeightDp)
        assertEquals(HomeLandscapeTier.Full, resolveHomeLandscapeTier(floating))
    }
}
