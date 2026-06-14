package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cramped-landscape [HomeLandscapeTier.Compact] state overrides two app-list
 * presentation choices: it always renders as an icon grid and always sorts by
 * usage with the most-used app at the visual bottom, regardless of the user's
 * persisted "App list" / "Sort apps by" settings. Every other tier (all of
 * portrait, and Full landscape) honors the persisted choice.
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

    @Test
    fun compactForcesIconGridRegardlessOfPersistedLayout() {
        assertTrue(effectiveAppListIconOnly(persistedIconOnly = false, tier = HomeLandscapeTier.Compact))
        assertTrue(effectiveAppListIconOnly(persistedIconOnly = true, tier = HomeLandscapeTier.Compact))
    }

    @Test
    fun fullHonorsThePersistedLayout() {
        assertFalse(effectiveAppListIconOnly(persistedIconOnly = false, tier = HomeLandscapeTier.Full))
        assertTrue(effectiveAppListIconOnly(persistedIconOnly = true, tier = HomeLandscapeTier.Full))
    }
}
