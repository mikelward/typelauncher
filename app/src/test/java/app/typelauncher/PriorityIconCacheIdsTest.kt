package app.typelauncher

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the one place the in-memory retention set and the on-disk snapshot set are
 * allowed to disagree: dynamic calendar icons.
 *
 * Both come from a single derivation on purpose — two independently-maintained lists
 * would drift, and "what is worth keeping warm" and "what is worth persisting" are
 * otherwise the same question. But they are not the same question for a calendar icon,
 * whose cache id carries the day it depicts. On disk that day-stamp is a hazard: a
 * saved bitmap outlives the process, so it must not be written. In memory the same
 * stamp is the protection: a new day derives a new cache id, so yesterday's entry can
 * never be served for today, and dropping it just costs a docked calendar app its warm
 * icon on the way back for nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PriorityIconCacheIdsTest {

    private fun app(packageName: String, token: String? = null) = InstalledApp(
        name = "App",
        packageName = packageName,
        launchIntent = Intent.makeMainActivity(ComponentName(packageName, "$packageName.Main")),
        user = Process.myUserHandle(),
        isWorkApp = false,
        launchWithLauncherApps = true,
        iconCacheToken = token,
    )

    private val calendar = app("com.google.android.calendar", token = "stamp@2026-08-28")
    private val ordinary = app("com.example.ordinary")
    private val apps = listOf(calendar, ordinary)
    private val allIds = apps.map { it.id }.toSet()

    @Test
    fun theSnapshotSetLeavesOutDynamicCalendarIcons() {
        val persisted = priorityIconCacheIdsFor(apps, allIds, includeDynamicCalendar = false)

        assertFalse(
            "a day-specific bitmap must never be persisted -- it outlives the process " +
                "and is wrong on any later day",
            calendar.iconCacheId in persisted,
        )
        assertTrue("an ordinary docked app is still persisted", ordinary.iconCacheId in persisted)
    }

    @Test
    fun theRetentionSetKeepsDynamicCalendarIcons() {
        // The regression this pins: reusing the snapshot's set for the background trim
        // evicted a docked calendar app's still-valid icon, so returning to the
        // launcher showed a placeholder on the dock while it re-resolved.
        val retained = priorityIconCacheIdsFor(apps, allIds, includeDynamicCalendar = true)

        assertTrue(
            "a docked calendar icon is valid for today and must stay warm",
            calendar.iconCacheId in retained,
        )
        assertTrue("an ordinary docked app stays warm too", ordinary.iconCacheId in retained)
    }

    @Test
    fun bothSetsStillDropAppsOutsideThePrioritySet() {
        // The divergence above is the only one; everything else about the two sets is
        // shared, which is why they are one derivation rather than two lists.
        val onlyCalendarIsPriority = setOf(calendar.id)

        for (includeDynamicCalendar in listOf(true, false)) {
            val ids = priorityIconCacheIdsFor(apps, onlyCalendarIsPriority, includeDynamicCalendar)
            assertFalse(
                "a non-priority app is dropped whether or not calendars are kept",
                ordinary.iconCacheId in ids,
            )
        }
    }
}
