package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cramped-landscape [HomeLandscapeTier.Compact] state overrides one app-list
 * presentation choice: it always sorts by usage with the most-used app at the
 * visual bottom, regardless of the user's persisted "Sort apps by" setting.
 * Every other tier honors the persisted choice. The "App list" layout setting
 * is honored in *every* tier (the old Compact icon-grid override is gone), so
 * there is no layout counterpart to these tests.
 */
class AppListCompactOverrideTest {
    @Test
    fun compactForcesUsageReversedRegardlessOfPersistedSort() {
        for (persisted in AppListSortOrder.values()) {
            assertEquals(
                "Compact must force UsageReversed, even from $persisted",
                AppListSortOrder.UsageReversed,
                effectiveAppListSortOrder(persisted, HomeLandscapeTier.Compact),
            )
        }
    }

    @Test
    fun fullHonorsThePersistedSort() {
        for (persisted in AppListSortOrder.values()) {
            assertEquals(
                "Full must keep the persisted sort $persisted",
                persisted,
                effectiveAppListSortOrder(persisted, HomeLandscapeTier.Full),
            )
        }
    }

    @Test
    fun compactForcedSortIsReversedSoMostUsedRendersAtBottom() {
        // The override is the *reversed* usage order (item 0 renders at the
        // visual bottom under reverseLayout), so the most-used app sits closest
        // to the typing area a pull-up reveals.
        assertTrue(
            effectiveAppListSortOrder(AppListSortOrder.Alphabetical, HomeLandscapeTier.Compact).isReversed,
        )
    }
}
