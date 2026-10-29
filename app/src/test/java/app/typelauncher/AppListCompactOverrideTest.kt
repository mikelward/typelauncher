package app.typelauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cramped-landscape [HomeLandscapeTier.Compact] state overrides one app-list
 * presentation choice: it always sorts by usage with the most-used app at the
 * visual bottom, regardless of the user's persisted "Sort apps by" setting.
 * Every other tier — all of portrait, the Full landscape tier, and the
 * keyboard-down DockNoKeyboard tier — honors the persisted choice, as does
 * Compact for a name sort while the letter strip is on. The "App
 * list" layout setting is honored in *every* tier (the old Compact icon-grid
 * override is gone), so there is no layout counterpart to these tests.
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
    fun dockNoKeyboardHonorsThePersistedSort() {
        // The keyboard-down landscape tier renders the dock and the search box,
        // so the app list is not the only launch surface there — the persisted
        // sort stays in charge, like Full.
        for (persisted in AppListSortOrder.values()) {
            assertEquals(
                "DockNoKeyboard must keep the persisted sort $persisted",
                persisted,
                effectiveAppListSortOrder(persisted, HomeLandscapeTier.DockNoKeyboard),
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
    fun compactKeepsANameSortWhileTheLetterStripIsOn() {
        // The letter strip reaches apps by name where the keyboard doesn't fit,
        // so a name sort survives Compact while the strip is on; a usage sort
        // still gets the thumb-reach override.
        for (persisted in AppListSortOrder.values()) {
            val expected = if (persisted.isAlphabetical) persisted else AppListSortOrder.UsageReversed
            assertEquals(
                "Compact with the strip on, from $persisted",
                expected,
                effectiveAppListSortOrder(persisted, HomeLandscapeTier.Compact, letterStrip = true),
            )
        }
    }

    @Test
    fun theLetterStripNeverChangesTheSortOutsideCompact() {
        for (tier in HomeLandscapeTier.values().filter { it != HomeLandscapeTier.Compact }) {
            for (persisted in AppListSortOrder.values()) {
                assertEquals(
                    "$tier with the strip on keeps $persisted",
                    persisted,
                    effectiveAppListSortOrder(persisted, tier, letterStrip = true),
                )
            }
        }
    }

    @Test
    fun theStripShowsOnlyWhenOnAndSortedByName() {
        for (sortOrder in AppListSortOrder.values()) {
            assertEquals("on, $sortOrder", sortOrder.isAlphabetical, showsLetterStrip(true, sortOrder))
            assertEquals("off, $sortOrder", false, showsLetterStrip(false, sortOrder))
        }
    }
}
