package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Unit tests for the pure helpers behind per-day calendar icon resolution in
 * [AppIconLoader]: the metadata key a calendar app advertises its 31-drawable
 * array under, and the day-of-month → array-index mapping (the off-by-one most
 * likely to be wrong).
 */
class DynamicCalendarIconResolutionTest {
    @Test
    fun metadataKeyIsPackageNameSuffixedWithDynamicIcons() {
        // Must match AOSP Launcher3 / the Pixel launcher so we read the same
        // entry Google Calendar declares.
        assertEquals(
            "com.google.android.calendar.dynamic_icons",
            dynamicCalendarIconsMetadataKey("com.google.android.calendar"),
        )
        assertEquals(
            "com.samsung.android.calendar.dynamic_icons",
            dynamicCalendarIconsMetadataKey("com.samsung.android.calendar"),
        )
    }

    @Test
    fun dayIndexIsZeroBasedDayOfMonth() {
        assertEquals(0, dynamicCalendarDayIndex(LocalDate.of(2026, 6, 1)))
        assertEquals(1, dynamicCalendarDayIndex(LocalDate.of(2026, 6, 2)))
    }

    @Test
    fun lastDayOfMonthMapsToFinalArrayEntry() {
        // The 31st is index 30 — the array's last slot. Getting this wrong is
        // exactly the bug that surfaced Google Calendar's static "31" icon.
        assertEquals(30, dynamicCalendarDayIndex(LocalDate.of(2026, 5, 31)))
    }
}
