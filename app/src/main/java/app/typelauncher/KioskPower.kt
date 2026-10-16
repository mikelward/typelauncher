package app.typelauncher

import android.content.Intent
import android.os.BatteryManager

/**
 * Whether a battery-status intent (the sticky `ACTION_BATTERY_CHANGED`) says
 * the device is connected to power. Reads the plug type, not the charging
 * status: a phone on mains whose charging is paused (battery protection, a
 * full charge, heat) is still plugged in, and kiosk mode should stay on for it.
 * A missing intent reads as unplugged, so the screen-on hold fails off.
 */
internal fun isPluggedIn(batteryStatus: Intent?): Boolean =
    (batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

/**
 * Kiosk mode keeps the screen on only while power is connected and the display
 * shows, or the Home it stepped out to does: that Home goes back to the display
 * after a quiet minute, which a shorter screen timeout would otherwise beat,
 * leaving a wall display dark.
 */
internal fun kioskKeepsScreenOn(isKioskOnScreen: Boolean, isPluggedIn: Boolean): Boolean =
    isKioskOnScreen && isPluggedIn

/** Whether kiosk mode is on screen for the screen-on hold: its display, or the Home it stepped out to. */
internal val LauncherUiState.isKioskOnScreen: Boolean
    get() = isKioskDisplayShowing || isKioskSteppedOutHome
