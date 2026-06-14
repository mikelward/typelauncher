package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the dp-based dock sizing: the persisted *target icon size* drives the
 * rendered per-row count (how many fit) and the grown-to-fill icon size. The
 * system Display size is honored implicitly — a larger Display size shrinks the
 * dp-width, so fewer, bigger icons render — and screen resolution is irrelevant
 * (dp absorbs both), so there is no density input at all.
 */
class DockIconSizingTest {

    @Test
    fun growToFillNeverRoundsTheIconBelowTheTarget() {
        // The invariant behind "43dp never quantizes down to 32": count-from-size
        // and size-from-count use identical chrome, so growing the icon to fill
        // the row at the fitted count always yields an icon >= the target on
        // every real screen width. A drifting chrome constant would break this.
        val target = DEFAULT_DOCK_APP_ICON_SIZE_DP
        for (widthDp in 240..1100) {
            val sizing = dockIconSizing(widthDp, target)
            assertTrue(
                "icon (${sizing.iconSizeDp}) must be >= target ($target) at ${widthDp}dp",
                sizing.iconSizeDp >= target,
            )
        }
    }

    @Test
    fun everyAchievableSliderStopRoundTrips() {
        // The Settings slider offers the counts in `dockSlotCountRange`; picking
        // one stores the icon size that fills it, and reading that size back must
        // render the same count (so the slider self-heals to the real number).
        for (widthDp in 280..960) {
            for (count in dockSlotCountRange(widthDp)) {
                val storedSize = dockIconSizeForSlotCount(widthDp, count)
                val sizing = dockIconSizing(widthDp, storedSize)
                assertEquals(
                    "size $storedSize must render $count columns at ${widthDp}dp",
                    count,
                    sizing.slotCount,
                )
                assertEquals(storedSize, sizing.iconSizeDp)
            }
        }
    }

    @Test
    fun defaultSizeYieldsSixPerRowAtTheReferenceWidth() {
        val sizing = dockIconSizing(DEFAULT_DOCK_SCREEN_WIDTH_DP, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        assertEquals(DEFAULT_DOCK_ICON_COUNT, sizing.slotCount)
        assertEquals(DEFAULT_DOCK_APP_ICON_SIZE_DP, sizing.iconSizeDp)
    }

    @Test
    fun enlargingDisplaySizeRendersFewerBiggerIcons() {
        // Display size enlarged: the same physical 411dp/420dpi phone reports a
        // smaller width in dp (411 * 420/630 = 274). The target size is a dp, so
        // it is unchanged; fewer fit and each grows to fill — the dock scales up
        // with no density input at all.
        val default = dockIconSizing(411, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        val enlarged = dockIconSizing(274, DEFAULT_DOCK_APP_ICON_SIZE_DP)
        assertTrue(
            "fewer icons fit once the dp-width shrinks (${enlarged.slotCount} < ${default.slotCount})",
            enlarged.slotCount < default.slotCount,
        )
        assertTrue(
            "each icon grows to fill the narrower row (${enlarged.iconSizeDp} >= ${default.iconSizeDp})",
            enlarged.iconSizeDp >= default.iconSizeDp,
        )
    }

    @Test
    fun countAndSizeStayWithinTheConfiguredBounds() {
        // A tiny target on a wide screen is capped at MAX_DOCK_ICON_COUNT columns,
        // and the rendered icon stays within the icon-size bounds.
        val dense = dockIconSizing(1100, MIN_DOCK_APP_ICON_SIZE_DP)
        assertTrue(dense.slotCount in MIN_DOCK_ICON_COUNT..MAX_DOCK_ICON_COUNT)
        assertTrue(dense.iconSizeDp in MIN_DOCK_APP_ICON_SIZE_DP..MAX_DOCK_APP_ICON_SIZE_DP)
        // A huge target on a narrow screen still renders at least one icon.
        val sparse = dockIconSizing(300, MAX_DOCK_APP_ICON_SIZE_DP)
        assertTrue(sparse.slotCount >= MIN_DOCK_ICON_COUNT)
    }
}
