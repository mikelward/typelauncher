package app.typelauncher

import android.content.Intent
import android.os.BatteryManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Kiosk mode's screen-on hold follows the plug, not the charge: a phone on
 * mains whose charging is paused (battery protection, full) still counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class KioskPowerTest {
    private fun battery(plugged: Int, status: Int): Intent =
        Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, plugged)
            .putExtra(BatteryManager.EXTRA_STATUS, status)

    @Test
    fun pluggedInWhileCharging() {
        assertTrue(isPluggedIn(battery(BatteryManager.BATTERY_PLUGGED_AC, BatteryManager.BATTERY_STATUS_CHARGING)))
    }

    @Test
    fun pluggedInWhileChargingIsPaused() {
        assertTrue(
            isPluggedIn(battery(BatteryManager.BATTERY_PLUGGED_USB, BatteryManager.BATTERY_STATUS_NOT_CHARGING)),
        )
        assertTrue(isPluggedIn(battery(BatteryManager.BATTERY_PLUGGED_DOCK, BatteryManager.BATTERY_STATUS_FULL)))
    }

    @Test
    fun unpluggedOrUnknownIsNotPluggedIn() {
        assertFalse(isPluggedIn(battery(0, BatteryManager.BATTERY_STATUS_DISCHARGING)))
        assertFalse(isPluggedIn(Intent(Intent.ACTION_BATTERY_CHANGED)))
        assertFalse(isPluggedIn(null))
    }

    @Test
    fun screenStaysOnOnlyWhileTheDisplayShowsAndPowerIsConnected() {
        assertTrue(kioskKeepsScreenOn(isKioskDisplayShowing = true, isPluggedIn = true))
        assertFalse(kioskKeepsScreenOn(isKioskDisplayShowing = true, isPluggedIn = false))
        assertFalse(kioskKeepsScreenOn(isKioskDisplayShowing = false, isPluggedIn = true))
    }
}
